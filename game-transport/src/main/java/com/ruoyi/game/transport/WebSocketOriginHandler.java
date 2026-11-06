package com.ruoyi.game.transport;

import java.util.Set;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;

public class WebSocketOriginHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Set<String> origins;
    public WebSocketOriginHandler(Set<String> origins) { super(false);this.origins=origins; }
    @Override protected void channelRead0(ChannelHandlerContext ctx,FullHttpRequest request) {
        String origin=request.headers().get(HttpHeaderNames.ORIGIN);
        if(origin==null||!origins.contains(origin)||!"/".equals(request.uri())) {
            io.netty.util.ReferenceCountUtil.release(request);
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
        } else ctx.fireChannelRead(request);
    }
}
