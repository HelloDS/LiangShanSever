package com.ruoyi.web.game;

import java.time.*;
import java.util.Set;
import com.ruoyi.game.admin.OnlineHistoryService;
import com.ruoyi.game.transport.OnlinePresence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OnlineHistoryWorkerTest {
    private final Instant at=Instant.parse("2026-11-06T22:01:02Z");
    @Test void persistsOnlyActualSampleTimeAndDefaultNonTestPopulation(){
        var online=mock(OnlinePresence.class);var history=mock(OnlineHistoryService.class);var metrics=new SimpleMeterRegistry();
        when(online.sample()).thenReturn(new OnlinePresence.Sample("AVAILABLE",at.toString(),Set.of(1L,2L)));
        var worker=new OnlineHistoryWorker(online,history,Clock.fixed(at.plusSeconds(30),ZoneOffset.UTC),metrics);worker.capture();
        verify(online).sample();verify(online,never()).sample(true);verify(history).record(eq(at),eq("AVAILABLE"),eq(2),matches("[a-f0-9-]{36}"));
        assertEquals(1,metrics.get("game.online.history.recorded").counter().count());assertFalse(worker.isRunning());worker.stop();
    }
    @Test void unavailablePopulationIsNullRatherThanZero(){
        var online=mock(OnlinePresence.class);var history=mock(OnlineHistoryService.class);var metrics=new SimpleMeterRegistry();
        when(online.sample()).thenReturn(new OnlinePresence.Sample("REBUILDING",at.toString(),null));
        var worker=new OnlineHistoryWorker(online,history,Clock.fixed(at,ZoneOffset.UTC),metrics);worker.capture();verify(history).record(eq(at),eq("REBUILDING"),isNull(),anyString());worker.stop();
    }
    @Test void failedWriteDoesNotIncrementConfirmedObservationCounter(){
        var online=mock(OnlinePresence.class);var history=mock(OnlineHistoryService.class);var metrics=new SimpleMeterRegistry();
        when(online.sample()).thenReturn(new OnlinePresence.Sample("AVAILABLE",at.toString(),Set.of()));doThrow(new IllegalStateException("fixture")).when(history).record(any(),anyString(),any(),anyString());
        var worker=new OnlineHistoryWorker(online,history,Clock.fixed(at,ZoneOffset.UTC),metrics);assertThrows(IllegalStateException.class,worker::capture);assertEquals(0,metrics.get("game.online.history.recorded").counter().count());worker.stop();
    }
}
