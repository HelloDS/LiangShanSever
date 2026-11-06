package com.ruoyi.game.transport;

import java.util.*;
import java.util.concurrent.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionTest {
    @Test void unauthenticatedConnectionsAreCappedAndClosingReleasesPermit(){
        ConnectionAdmission admission=new ConnectionAdmission(2);
        EmbeddedChannel first=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),true,false,admission),second=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),true,false,admission),third=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),true,false,admission);
        EmbeddedChannel replacement=null;
        try{
            assertTrue(first.isActive());assertTrue(second.isActive());assertFalse(third.isActive());assertEquals(2,admission.active());
            first.close();assertEquals(1,admission.active());replacement=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),true,false,admission);assertTrue(replacement.isActive());assertEquals(2,admission.active());
            admission.closeAll();assertFalse(second.isActive());assertFalse(replacement.isActive());assertEquals(0,admission.active());
        }finally{first.finishAndReleaseAll();second.finishAndReleaseAll();third.finishAndReleaseAll();if(replacement!=null)replacement.finishAndReleaseAll();}
    }
    @Test void incompleteHttpHandshakeExpiresFromConnectionTime(){
        EmbeddedChannel channel=new EmbeddedChannel(new HandshakeDeadline());
        try{channel.freezeTime();channel.advanceTimeBy(11,TimeUnit.SECONDS);channel.runScheduledPendingTasks();assertFalse(channel.isActive());}
        finally{channel.finishAndReleaseAll();}
    }
    @Test void successfulHandshakeCancelsDeadline(){
        EmbeddedChannel channel=new EmbeddedChannel(new HandshakeDeadline());
        try{
            channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete("/",EmptyHttpHeaders.INSTANCE,null));
            channel.freezeTime();channel.advanceTimeBy(11,TimeUnit.SECONDS);channel.runScheduledPendingTasks();assertTrue(channel.isActive());assertNull(channel.pipeline().get(HandshakeDeadline.class));
        }finally{channel.finishAndReleaseAll();}
    }
    @Test void byteBudgetRejectsExcessAndCanBeReusedAfterRelease(){
        InboundBudget budget=new InboundBudget(262144);assertTrue(budget.acquire(262144));assertFalse(budget.acquire(1));budget.release(262144);
        assertEquals(0,budget.used());assertTrue(budget.acquire(1));budget.release(1);assertThrows(IllegalStateException.class,()->budget.release(1));assertEquals(0,budget.used());
    }
    @Test void concurrentReservationsNeverOvercommitBudget() throws Exception {
        InboundBudget budget=new InboundBudget(262144);
        try(ExecutorService workers=Executors.newFixedThreadPool(16)){
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++)tasks.add(()->{for(int n=0;n<1000;n++){if(budget.acquire(65536)){try{assertTrue(budget.used()<=262144);}finally{budget.release(65536);}}}return null;});
            for(var result:workers.invokeAll(tasks))result.get();
        }
        assertEquals(0,budget.used());
    }
}
