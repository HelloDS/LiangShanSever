package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import com.ruoyi.game.auth.GuestIdentity;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionRegistryTest {
    private static GuestIdentity identity(){return new GuestIdentity(1,"fixture",100001,0,true);}
    @Test void delayedCloseOfOldConnectionDoesNotRemoveNewBinding(){
        ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());
        EmbeddedChannel old=new EmbeddedChannel(),fresh=new EmbeddedChannel();
        try{
            var previous=registry.bind(identity(),old,"first");var current=registry.bind(identity(),fresh,"second");
            assertFalse(old.isActive());assertFalse(registry.remove(previous));assertTrue(registry.current(current));registry.ackQueued(current);
            registry.push(List.of(100001L),"{\"cmd\":\"fixture\"}");fresh.runPendingTasks();
            Object frame=fresh.readOutbound();assertNotNull(frame);ReferenceCountUtil.release(frame);assertEquals(0,registry.pendingPushes());
        }finally{old.finishAndReleaseAll();fresh.finishAndReleaseAll();}
    }
    @Test void delayedRevocationCannotDisconnectFreshSessionVersion(){
        ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());
        EmbeddedChannel old=new EmbeddedChannel(),fresh=new EmbeddedChannel();
        try{
            var previous=registry.bind(identity(),old,"old");
            registry.bind(new GuestIdentity(1,"fixture",100001,1,true),fresh,"fresh");
            assertFalse(registry.disconnect(previous));
            registry.disconnectBeforeVersion(100001,1);
            assertTrue(fresh.isActive());
            registry.disconnectBeforeVersion(100001,2);
            assertFalse(fresh.isActive());
        }finally{old.finishAndReleaseAll();fresh.finishAndReleaseAll();}
    }
    @Test void pushBeforeLoginAckIsHeldAndReleasedInWireOrder(){
        ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());EmbeddedChannel channel=new EmbeddedChannel();
        try{
            var binding=registry.bind(identity(),channel,"fixture");registry.push(List.of(100001L),"{\"cmd\":\"fixture\"}");channel.runPendingTasks();
            assertNull(channel.readOutbound());assertEquals(1,registry.pendingPushes());
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame("{\"state\":0}"));registry.ackQueued(binding);channel.runPendingTasks();
            var ack=(io.netty.handler.codec.http.websocketx.TextWebSocketFrame)channel.readOutbound();var push=(io.netty.handler.codec.http.websocketx.TextWebSocketFrame)channel.readOutbound();
            assertEquals("{\"state\":0}",ack.text());assertEquals("{\"cmd\":\"fixture\"}",push.text());ReferenceCountUtil.release(ack);ReferenceCountUtil.release(push);assertEquals(0,registry.pendingPushes());
            registry.requestStarted(binding);registry.push(List.of(100001L),"{\"cmd\":\"next\"}");channel.runPendingTasks();assertNull(channel.readOutbound());
            channel.close();channel.runPendingTasks();assertEquals(0,registry.pendingPushes());
        }finally{channel.finishAndReleaseAll();}
    }
    @Test void preAckPushOverflowClosesAndReleasesTheSameBoundedBudget(){
        ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());EmbeddedChannel channel=new EmbeddedChannel();
        try{
            registry.bind(identity(),channel,"fixture");
            for(int i=0;i<64;i++){registry.push(List.of(100001L),"{\"cmd\":\"fixture\"}");channel.runPendingTasks();}
            assertEquals(64,registry.pendingPushes());assertTrue(channel.isActive());
            registry.push(List.of(100001L),"{\"cmd\":\"overflow\"}");channel.runPendingTasks();assertFalse(channel.isActive());assertEquals(0,registry.pendingPushes());
        }finally{channel.finishAndReleaseAll();}
    }
    @Test void slowWritesHaveBoundedPendingPushesAndReleaseOnFailure(){
        ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());
        List<ChannelPromise> promises=new ArrayList<>();List<Object> frames=new ArrayList<>();
        EmbeddedChannel channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter(){
            @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise){frames.add(message);promises.add(promise);}
        });
        try{
            registry.ackQueued(registry.bind(identity(),channel,"fixture"));
            for(int i=0;i<64;i++){registry.push(List.of(100001L),"{\"cmd\":\"fixture\"}");channel.runPendingTasks();}
            assertEquals(64,registry.pendingPushes());assertTrue(channel.isActive());
            registry.push(List.of(100001L),"{\"cmd\":\"overflow\"}");channel.runPendingTasks();
            assertFalse(channel.isActive());assertTrue(registry.pendingPushes()<=64);
            for(var promise:promises)promise.tryFailure(new java.nio.channels.ClosedChannelException());
            assertEquals(0,registry.pendingPushes());
        }finally{channel.finishAndReleaseAll();frames.forEach(ReferenceCountUtil::release);}
    }
}
