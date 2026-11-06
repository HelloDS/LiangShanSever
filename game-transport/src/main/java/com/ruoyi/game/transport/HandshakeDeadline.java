package com.ruoyi.game.transport;

import java.util.concurrent.TimeUnit;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.ScheduledFuture;

/** 从 TCP 接入起计时，慢 HTTP 头不能绕过 WebSocket 握手期限。 */
public class HandshakeDeadline extends ChannelInboundHandlerAdapter {
    private ScheduledFuture<?> deadline;
    @Override public void channelActive(ChannelHandlerContext context)throws Exception{
        deadline=context.executor().schedule(()->{context.close();},10,TimeUnit.SECONDS);super.channelActive(context);
    }
    @Override public void userEventTriggered(ChannelHandlerContext context,Object event)throws Exception{
        if(event instanceof WebSocketServerProtocolHandler.HandshakeComplete){cancel();context.pipeline().remove(this);}
        super.userEventTriggered(context,event);
    }
    @Override public void channelInactive(ChannelHandlerContext context)throws Exception{cancel();super.channelInactive(context);}
    @Override public void handlerRemoved(ChannelHandlerContext context){cancel();}
    private void cancel(){if(deadline!=null){deadline.cancel(false);deadline=null;}}
}
