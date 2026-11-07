package com.ruoyi.web.game;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import com.ruoyi.game.room.RoomExports;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoomExportWorkerTest {
    private static void waitFor(BooleanSupplier condition){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)LockSupport.parkNanos(1_000_000);assertTrue(condition.getAsBoolean());}
    @Test void storageFailureBeforeClaimIsVisibleAndLeavesAtMostOneScheduledPoll(){
        var jobs=mock(RoomExports.class);var files=mock(RoomExportFiles.class);var metrics=new SimpleMeterRegistry();
        when(jobs.claim()).thenThrow(new IllegalStateException("private diagnostic"));var worker=new RoomExportWorker(jobs,files,Clock.systemUTC(),metrics);
        try{worker.start();worker.start();waitFor(()->metrics.counter("game.exports.failed").count()==1);assertTrue(metrics.get("game.exports.scheduled").gauge().value()<=1);verify(jobs,times(1)).claim();}
        finally{worker.stop();assertEquals(0,metrics.get("game.exports.scheduled").gauge().value());metrics.close();}
    }
    @Test void stopDuringClaimReleasesTheLeaseWithoutStartingFileGeneration() throws Exception {
        var jobs=mock(RoomExports.class);var files=mock(RoomExportFiles.class);var metrics=new SimpleMeterRegistry();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var lease=new RoomExports.Lease(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"hash",null,LocalDateTime.now());
        when(jobs.claim()).thenAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return lease;});var worker=new RoomExportWorker(jobs,files,Clock.systemUTC(),metrics);
        try(var stop=Executors.newSingleThreadExecutor()){
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));var stopped=stop.submit((Runnable)worker::stop);waitFor(()->!worker.isRunning());release.countDown();stopped.get(3,TimeUnit.SECONDS);
            verify(files,never()).begin(any());verify(jobs).failed(lease,"STOPPED",false);verify(jobs,never()).complete(any(),anyInt(),anyInt());assertEquals(0,metrics.get("game.exports.scheduled").gauge().value());
        }finally{release.countDown();worker.stop();metrics.close();}
    }
}
