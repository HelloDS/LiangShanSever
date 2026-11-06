package com.ruoyi.game.transport;

import java.time.*;
import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.outbox.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxWorkerTest {
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
