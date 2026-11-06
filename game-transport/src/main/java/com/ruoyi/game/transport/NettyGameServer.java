package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class NettyGameServer implements SmartLifecycle {
    private final int port;
    private final Set<String> origins;
    private final BusinessExecutor executor;
    private final GameDispatcher dispatcher;
    private final ConnectionRegistry registry;
    private final OnlinePresence presence;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final GameTelemetry telemetry;
    private EventLoopGroup boss,workers;
    private Channel server;
    public NettyGameServer(@Value("${game.ws.port:8081}") int port,@Value("${game.allowed-origins}") String origins,
                           BusinessExecutor executor,GameDispatcher dispatcher,ConnectionRegistry registry,
                           OnlinePresence presence,GameJsonCodec codec,Clock clock,GameTelemetry telemetry) {
        this.port=port;this.origins=Set.copyOf(Arrays.asList(origins.split(",")));this.executor=executor;this.dispatcher=dispatcher;
        this.registry=registry;this.presence=presence;this.codec=codec;this.clock=clock;this.telemetry=telemetry;
    }
    @Override public void start() {
        boss=new NioEventLoopGroup(1);workers=new NioEventLoopGroup(Math.min(4,Runtime.getRuntime().availableProcessors()));
        try {
            server=new ServerBootstrap().group(boss,workers).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY,true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,new WriteBufferWaterMark(512*1024,1024*1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new HttpServerCodec(),new HttpObjectAggregator(GameJsonCodec.MAX_BYTES),
                                    new WebSocketOriginHandler(origins),
                                    new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder().websocketPath("/")
                                            .maxFramePayloadLength(GameJsonCodec.MAX_BYTES).dropPongFrames(false).build()),
                                    new WebSocketFrameAggregator(GameJsonCodec.MAX_BYTES),new IdleStateHandler(90,30,0),
                                    new GameSocketHandler(executor,dispatcher,registry,presence,codec,clock,telemetry));
                        }
                    }).bind("127.0.0.1",port).syncUninterruptibly().channel();
        } catch(RuntimeException e) {stop();throw e;}
    }
    @Override public void stop() {
        if(server!=null)server.close().syncUninterruptibly();
        for(var binding:registry.all())binding.channel().close();
        if(workers!=null)workers.shutdownGracefully();if(boss!=null)boss.shutdownGracefully();server=null;
    }
    @Override public boolean isRunning() { return server!=null&&server.isActive(); }
    @Override public int getPhase() { return Integer.MAX_VALUE-100; }
}
