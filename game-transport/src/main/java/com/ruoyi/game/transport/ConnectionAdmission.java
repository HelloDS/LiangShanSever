package com.ruoyi.game.transport;

import java.util.concurrent.Semaphore;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 认证前的 TCP/握手连接同样受限，停止时包含尚未注册的连接。 */
@Component
@ChannelHandler.Sharable
public class ConnectionAdmission extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<Boolean> ADMITTED=AttributeKey.valueOf(ConnectionAdmission.class,"admitted");
    private final int maximum;
    private final Semaphore slots;
    private final ChannelGroup channels=new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    public ConnectionAdmission(@Value("${game.ws.max-connections:2048}") int maximum){
        if(maximum<1||maximum>100000)throw new IllegalArgumentException("连接上限配置无效");
        this.maximum=maximum;this.slots=new Semaphore(maximum);
    }
    @Override public void channelActive(ChannelHandlerContext context)throws Exception{
        if(!slots.tryAcquire()){context.close();return;}
        context.channel().attr(ADMITTED).set(true);channels.add(context.channel());super.channelActive(context);
    }
    @Override public void channelInactive(ChannelHandlerContext context)throws Exception{
        if(Boolean.TRUE.equals(context.channel().attr(ADMITTED).getAndSet(null)))slots.release();
        super.channelInactive(context);
    }
    public int active(){return maximum-slots.availablePermits();}
    public void closeAll(){channels.close().awaitUninterruptibly(10000);}
}
