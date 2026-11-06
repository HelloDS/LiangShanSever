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
    private final GameDispatcher dispatcher;
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

    public GameSocketHandler(BusinessExecutor executor,GameDispatcher dispatcher,ConnectionRegistry registry,
                             OnlinePresence presence,GameJsonCodec codec,Clock clock,GameTelemetry telemetry,InboundBudget inboundBudget) {
        this.executor=executor;this.dispatcher=dispatcher;this.registry=registry;this.presence=presence;this.codec=codec;this.clock=clock;this.telemetry=telemetry;this.inboundBudget=inboundBudget;
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
        long now=clock.millis();
        if(now-rateWindow>=1000) {rateWindow=now;requestCount=0;}
        boolean limited=++requestCount>40;
        try {
            executor.execute(()->{
                GameDispatcher.Result result=null;
                String output;
                List<EncodedPush> pushes=List.of();
                String api="unknown",category="success";
                try {
                    if(limited) throw new GameFailure(-2,"请求过于频繁");
                    var request=codec.request(input.text());api=request.api();
                    result=dispatcher.dispatch(request,previous,ctx.channel());
                    output=codec.write(result.ack());
                    pushes=result.notifications().stream().map(n->new EncodedPush(n.recipients(),codec.write(n.packet()))).toList();
                } catch(GameFailure e) { category="business_error";output=codec.write(GameJsonCodec.error(e.state(),e.getMessage())); }
                catch(IllegalArgumentException e) { category="business_error";output=codec.write(GameJsonCodec.error(-4,"请求参数无效")); }
                catch(Exception e) { category="unavailable";output=codec.write(GameJsonCodec.error(-5,"服务暂不可用，请稍后重试")); }
                var completed=result;
                String response=output;
                List<EncodedPush> notifications=pushes;
                String apiName=api,resultCategory=category;
                Runnable completion=()->{
                    inboundBudget.release(input.bytes());
                    telemetry.complete(apiName,resultCategory,System.nanoTime()-input.receivedNanos());
                    if(completed!=null) binding=completed.binding();
                    if(ctx.channel().isActive()) {
                        if(!ctx.channel().isWritable()) {close(ctx,1013,"输出队列已满");}
                        else ctx.writeAndFlush(new TextWebSocketFrame(response));
                    } else cleanup();
                    notifications.forEach(push->registry.push(push.recipients(),push.packet()));
                    busy=false;drain(ctx);
                };
                try{ctx.executor().execute(completion);}
                catch(RejectedExecutionException e){
                    inboundBudget.release(input.bytes());
                    if(completed!=null)registry.remove(completed.binding());
                    ctx.channel().close();
                }
            });
        } catch(RejectedExecutionException e) {
            inboundBudget.release(input.bytes());
            telemetry.complete("unknown","unavailable",System.nanoTime()-input.receivedNanos());
            ctx.writeAndFlush(new TextWebSocketFrame(codec.write(GameJsonCodec.error(-5,"服务繁忙，请稍后重试"))));
            busy=false; drain(ctx);
        }
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
