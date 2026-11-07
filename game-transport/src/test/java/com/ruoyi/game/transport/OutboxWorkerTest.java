package com.ruoyi.game.transport;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.outbox.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxWorkerTest {
    @Test void roomCompletionUsesAtomicConsumerAndStaleLeaseDoesNotFailANewOwner(){
        var store=mock(OutboxStore.class);var consumer=mock(com.ruoyi.game.room.RoomEventConsumer.class);var metrics=new SimpleMeterRegistry();
        var first=new OutboxEvent("first","room-event:first:1","ROOM_EVENT_COMMITTED",1,"fixture","{}",1,"one",LocalDateTime.now());
        var stale=new OutboxEvent("stale","room-event:stale:1","ROOM_EVENT_COMMITTED",1,"fixture","{}",1,"old",LocalDateTime.now());
        when(store.backlog()).thenReturn(new OutboxStore.Backlog(0,0,0,0,"fixture"));when(store.claim(anyInt())).thenReturn(List.of(first,stale),List.of());doThrow(new com.ruoyi.game.room.RoomEventConsumer.StaleLease()).when(consumer).consumeAndComplete(stale);
        var worker=new OutboxWorker(store,new ConnectionRegistry(Clock.systemUTC()),new GameJsonCodec(),metrics,consumer);
        try{worker.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((metrics.counter("game.outbox.completed").count()!=1||metrics.counter("game.outbox.lease.lost").count()!=1)&&System.nanoTime()<deadline)LockSupport.parkNanos(1_000_000);
            assertEquals(1,metrics.counter("game.outbox.completed").count());assertEquals(1,metrics.counter("game.outbox.lease.lost").count());assertEquals(0,metrics.counter("game.outbox.failed").count());
            verify(consumer).consumeAndComplete(first);verify(consumer).consumeAndComplete(stale);verify(store,never()).complete(any());verify(store,never()).failed(any(),anyString());
        }finally{worker.stop();metrics.close();}
    }
    @Test void replayAfterEffectBeforeReceiptDoesNotRevokeNewVersion(){
        ConnectionRegistry connections=new ConnectionRegistry(Clock.systemUTC());
        var worker=new OutboxWorker(mock(OutboxStore.class),connections,new GameJsonCodec(),new SimpleMeterRegistry(),mock(com.ruoyi.game.room.RoomEventConsumer.class));
        var event=new OutboxEvent("fixture","player-revoked:100001:1","PLAYER_REVOKED",1,"session-version-v1",
                "{\"uid\":100001,\"sessionVersion\":1}",1,"lease",LocalDateTime.now(Clock.systemUTC()));
        EmbeddedChannel old=new EmbeddedChannel(),fresh=new EmbeddedChannel();
        try{
            connections.bind(new GuestIdentity(1,"fixture",100001,0,true),old,"old");
            worker.handle(event);assertFalse(old.isActive());
            connections.bind(new GuestIdentity(1,"fixture",100001,1,true),fresh,"new");
            worker.handle(event);assertTrue(fresh.isActive());
            var invalid=new OutboxEvent("fixture","fixture","PLAYER_REVOKED",2,"session-version-v1",event.payload(),1,"lease",event.occurredAt());
            assertThrows(IllegalArgumentException.class,()->worker.handle(invalid));assertTrue(fresh.isActive());
        }finally{old.finishAndReleaseAll();fresh.finishAndReleaseAll();}
    }
}
