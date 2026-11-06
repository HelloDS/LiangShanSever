package com.ruoyi;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.game.admin.AdminOperations;
import com.ruoyi.game.persistence.mapper.GameAdminMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminOperationTimeTest {
    @Test void completionAndAuditUseObservedTimeAfterTheBusinessEffect(){
        Instant received=Instant.parse("2026-11-06T22:00:00Z");var observed=new AtomicReference<>(received);
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return observed.get();}};
        var mapper=mock(GameAdminMapper.class);AtomicReference<Map<String,Object>> beginning=new AtomicReference<>();
        when(mapper.beginOperation(anyMap())).thenAnswer(call->{beginning.set(call.getArgument(0));return 1;});
        when(mapper.operation(anyLong(),anyString())).thenAnswer(call->Map.of("operationId",beginning.get().get("operationId"),"requestHash",beginning.get().get("hash")));
        when(mapper.auditCommand(anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(1);when(mapper.complete(anyString(),anyString(),any())).thenReturn(1);
        var operations=new AdminOperations(mapper,new GameJsonCodec(),clock);
        operations.execute(1,"notice:publish","1","完成时刻来源验收",UUID.randomUUID().toString(),Map.of(),()->{
            observed.set(received.plusSeconds(15));return new AdminOperations.Change(Map.of("published",false),Map.of("published",true),Map.of("id","1"));
        });
        var auditTime=ArgumentCaptor.forClass(LocalDateTime.class);var completedTime=ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).auditCommand(anyString(),eq(1L),eq("notice:publish"),eq("1"),anyString(),anyString(),anyString(),auditTime.capture());verify(mapper).complete(anyString(),anyString(),completedTime.capture());
        assertEquals(LocalDateTime.ofInstant(received,ZoneOffset.UTC),beginning.get().get("now"));assertEquals(LocalDateTime.ofInstant(received.plusSeconds(15),ZoneOffset.UTC),auditTime.getValue());assertEquals(auditTime.getValue(),completedTime.getValue());
    }
}
