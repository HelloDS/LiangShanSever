package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.*;

/** 每连接至多一个在途业务，普通响应始终按入站顺序输出。 */
public class GameSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final BusinessExecutor executor;
    private final GameRequestDispatcher dispatcher;
    private final ReplyExecutor replies;
    private final ConnectionRegistry registry;
    private final OnlinePresence presence;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final GameTelemetry telemetry;
    private final InboundBudget inboundBudget;
    private record Inbound(String text,long receivedNanos,int bytes){ }
    private record EncodedPush(List<Long> recipients,String packet){ }
    private final Queue<Inbound> inbox=new ArrayDeque<>();
    private ConnectionRegistry.Binding binding;
    private boolean busy;
    private long rateWindow;
    private int requestCount;
    private int queuedBytes;

    public GameSocketHandler(BusinessExecutor executor,GameRequestDispatcher dispatcher,ConnectionRegistry registry,
                             OnlinePresence presence,GameJsonCodec codec,Clock clock,GameTelemetry telemetry,InboundBudget inboundBudget,ReplyExecutor replies) {
        this.executor=executor;this.dispatcher=dispatcher;this.registry=registry;this.presence=presence;this.codec=codec;this.clock=clock;this.telemetry=telemetry;this.inboundBudget=inboundBudget;this.replies=replies;
    }
    @Override protected void channelRead0(ChannelHandlerContext ctx,WebSocketFrame frame) {
        if(binding!=null) binding.activity(clock.millis());
        if(frame instanceof PongWebSocketFrame) return;
        if(!(frame instanceof TextWebSocketFrame text)) { close(ctx,1003,"只接受 JSON 文本消息"); return; }
        int frameBytes=text.content().readableBytes();
        if(inbox.size()>=64||queuedBytes+frameBytes>1024*1024) { close(ctx,1013,"请求队列已满"); return; }
        if(!inboundBudget.acquire(frameBytes)){close(ctx,1013,"入站队列已满");return;}
        queuedBytes+=frameBytes;
        inbox.add(new Inbound(text.text(),System.nanoTime(),frameBytes)); drain(ctx);
    }
    private void drain(ChannelHandlerContext ctx) {
        if(busy||inbox.isEmpty()||!ctx.channel().isActive()) return;
        busy=true;
        Inbound input=inbox.remove();
        queuedBytes-=input.bytes();
        ConnectionRegistry.Binding previous=binding;
        registry.requestStarted(previous);
        long now=clock.millis();
        if(now-rateWindow>=1000) {rateWindow=now;requestCount=0;}
        boolean limited=++requestCount>40;
        ReplyExecutor.Ticket ticket=replies.reserve();
        if(ticket==null){rejectBeforeDispatch(ctx,input);return;}
        try {
            executor.execute(()->{
                String api="unknown";
                try {
                    if(limited) throw new GameFailure(-2,"请求过于频繁");
                    var request=codec.request(input.text());api=request.api();
                    String apiName=api;
                    java.util.Objects.requireNonNull(dispatcher.dispatchAsync(request,previous,ctx.channel()))
                            .whenComplete((result,failure)->ready(ctx,input,ticket,apiName,result,failure));
                } catch(Exception failure){ready(ctx,input,ticket,api,null,failure);}
            });
        } catch(RejectedExecutionException e) {
            ticket.close();rejectBeforeDispatch(ctx,input);
        }
    }
    private void rejectBeforeDispatch(ChannelHandlerContext ctx,Inbound input){
        inboundBudget.release(input.bytes());telemetry.complete("unknown","unavailable",System.nanoTime()-input.receivedNanos());
        ctx.writeAndFlush(new TextWebSocketFrame(codec.write(GameJsonCodec.error(-5,"服务繁忙，请稍后重试"))));
        registry.ackQueued(binding);busy=false;drain(ctx);
    }
    private void ready(ChannelHandlerContext ctx,Inbound input,ReplyExecutor.Ticket ticket,String api,GameDispatcher.Result result,Throwable failure){
        try{ticket.reply(()->encode(ctx,input,ticket,api,result,failure));}
        catch(RejectedExecutionException stopped){
            // 已受理动作不能因响应执行器停机被改报为业务失败；关闭后按持久状态恢复。
            ticket.close();inboundBudget.release(input.bytes());if(result!=null)registry.remove(result.binding());ctx.close();
        }
    }
    private void encode(ChannelHandlerContext ctx,Inbound input,ReplyExecutor.Ticket ticket,String api,GameDispatcher.Result result,Throwable failure){
        Throwable cause=failure;while((cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();
        // 未分类异常（包括超时/取消）不能证明事务回滚；只有显式业务/参数拒绝走普通失败 ACK。
        boolean unknown=cause!=null&&!(cause instanceof GameFailure)&&!(cause instanceof IllegalArgumentException);
        String category="success",output=null;List<EncodedPush> pushes=List.of();
        try{
            if(cause!=null)throw cause instanceof RuntimeException runtime?runtime:new IllegalStateException(cause);
            if(result==null)throw new IllegalStateException("响应为空");
            output=codec.write(result.ack());pushes=result.notifications().stream().map(n->new EncodedPush(n.recipients(),codec.write(n.packet()))).toList();
        }catch(GameRequestDispatcher.OutcomeUnknown outcome){category="outcome_unknown";}
        catch(GameFailure business){category="business_error";output=codec.write(GameJsonCodec.error(business.state(),business.getMessage()));}
        catch(IllegalArgumentException invalid){
            if(result!=null){unknown=true;category="encoding_failure";}
            else{category="business_error";output=codec.write(GameJsonCodec.error(-4,"请求参数无效"));}
        }
        catch(Exception unavailable){
            unknown=true;category=result!=null?"encoding_failure":"outcome_unknown";
        }
        String response=output,resultCategory=category;List<EncodedPush> notifications=pushes;boolean closeForOutcome=unknown;
        Runnable completion=()->{
            try{
                inboundBudget.release(input.bytes());telemetry.complete(api,resultCategory,System.nanoTime()-input.receivedNanos());
                if(result!=null)binding=result.binding();
                if(closeForOutcome){close(ctx,1012,"操作状态需重新同步");}
                else if(ctx.channel().isActive()){
                    if(!ctx.channel().isWritable())close(ctx,1013,"输出队列已满");
                    else{ctx.writeAndFlush(new TextWebSocketFrame(response));registry.ackQueued(binding);}
                }else cleanup();
                notifications.forEach(push->registry.push(push.recipients(),push.packet()));
            }finally{ticket.close();busy=false;if(!closeForOutcome)drain(ctx);}
        };
        try{ctx.executor().execute(completion);}
        catch(RejectedExecutionException stopped){ticket.close();inboundBudget.release(input.bytes());if(result!=null)registry.remove(result.binding());ctx.close();}
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx,Object event) throws Exception {
        if(event instanceof IdleStateEvent idle) {
            if(idle.state()==IdleState.READER_IDLE) close(ctx,1001,"连接已失活");
            else if(idle.state()==IdleState.WRITER_IDLE) ctx.writeAndFlush(new PingWebSocketFrame());
        } else super.userEventTriggered(ctx,event);
    }
    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if(!ctx.channel().isWritable()) close(ctx,1013,"输出队列已满");
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) { inbox.clear();inboundBudget.release(queuedBytes);queuedBytes=0;cleanup(); }
    private void cleanup() {
        ConnectionRegistry.Binding old=binding;
        registry.remove(old);
        if(old!=null) {
            try {executor.execute(()->{try{presence.remove(old);}catch(RuntimeException ignored){}});}
            catch(RejectedExecutionException ignored) { }
        }
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause) { ctx.close(); }
    private void close(ChannelHandlerContext ctx,int code,String message) {
        ctx.writeAndFlush(new CloseWebSocketFrame(code,message)).addListener(ChannelFutureListener.CLOSE);
    }
}
