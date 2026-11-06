package com.ruoyi;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.game.economy.WalletReconciliationJobs;
import com.ruoyi.game.persistence.mapper.WalletReconciliationMapper;
import com.ruoyi.web.game.WalletReconciliationWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletReconciliationWorkerTest {
    private final WalletReconciliationJobs jobs=mock(WalletReconciliationJobs.class);
    private final WalletReconciliationMapper mapper=mock(WalletReconciliationMapper.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    WalletReconciliationWorkerTest(){when(mapper.backlog()).thenReturn(List.of());}
    private WalletReconciliationWorker worker(String time){return new WalletReconciliationWorker(jobs,mapper,Clock.fixed(Instant.parse(time),ZoneOffset.UTC),metrics,4,15);}
    private WalletReconciliationJobs.Lease lease(){return new WalletReconciliationJobs.Lease("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002");}
    @Test void beforeAutomaticScheduleItStillProcessesAnExistingManualJob() throws Exception {
        var lease=lease();when(jobs.claim()).thenReturn(lease);when(jobs.process(lease)).thenReturn(WalletReconciliationJobs.Step.DONE);
        CountDownLatch finished=new CountDownLatch(1);when(jobs.release(lease)).thenAnswer(invocation->{finished.countDown();return false;});
        var worker=worker("2026-11-06T20:00:00Z");
        try{worker.start();assertTrue(finished.await(2,TimeUnit.SECONDS));}finally{worker.stop();}
        verify(jobs,never()).ensureToday(anyString(),any());verify(jobs).process(lease);assertFalse(worker.isRunning());
        assertEquals(0,metrics.get("game.reconciliation.scheduled").gauge().value());
    }
    @Test void afterScheduleItCreatesOneDailyRunAndCountsOnlyCommittedChecks() throws Exception {
        var lease=lease();when(jobs.claim()).thenReturn(lease);when(jobs.process(lease)).thenReturn(WalletReconciliationJobs.Step.PROGRESSED,WalletReconciliationJobs.Step.DONE);
        when(jobs.ensureToday("SYSTEM",null)).thenReturn(Map.of("id",lease.id()));CountDownLatch finished=new CountDownLatch(1);var claims=new java.util.concurrent.atomic.AtomicInteger();
        when(jobs.claim()).thenAnswer(invocation->{if(claims.incrementAndGet()==1)return lease;finished.countDown();return null;});
        var worker=worker("2026-11-06T20:15:00Z");
        try{worker.start();assertTrue(finished.await(2,TimeUnit.SECONDS));}finally{worker.stop();}
        verify(jobs).ensureToday("SYSTEM",null);assertEquals(1,metrics.get("game.reconciliation.checked").counter().count());
    }
    @Test void unknownCheckResultIsRetriedThroughPersistentLeaseWithoutClaimingSuccess() throws Exception {
        var lease=lease();when(jobs.claim()).thenReturn(lease);when(jobs.process(lease)).thenThrow(new DataAccessResourceFailureException("injected unknown check result"));
        CountDownLatch failed=new CountDownLatch(1);when(jobs.failed(lease,"CHECK_RESULT_UNKNOWN")).thenAnswer(invocation->{failed.countDown();return true;});
        var worker=worker("2026-11-06T20:00:00Z");try{worker.start();assertTrue(failed.await(2,TimeUnit.SECONDS));}finally{worker.stop();}
        assertEquals(0,metrics.get("game.reconciliation.checked").counter().count());assertEquals(1,metrics.get("game.reconciliation.failed").counter().count());verify(jobs,never()).release(lease);
    }
    @Test void unavailableBacklogIsUnknownInsteadOfZero() throws Exception {
        CountDownLatch failed=new CountDownLatch(1);when(mapper.backlog()).thenAnswer(invocation->{failed.countDown();throw new DataAccessResourceFailureException("injected unavailable jobs");});
        var worker=worker("2026-11-06T20:00:00Z");try{worker.start();assertTrue(failed.await(2,TimeUnit.SECONDS));}finally{worker.stop();}
        assertEquals(0,metrics.get("game.reconciliation.backlog.available").gauge().value());assertTrue(Double.isNaN(metrics.get("game.reconciliation.runs").tag("status","FAILED").gauge().value()));verify(jobs,never()).claim();
    }
    @Test void persistedDifferencesRemainObservableWithoutBeingCountedAsTechnicalFailure() throws Exception {
        when(mapper.backlog()).thenReturn(List.of(Map.of("status","DONE_WITH_ISSUES","total",2L)));CountDownLatch sampled=new CountDownLatch(1);
        when(jobs.claim()).thenAnswer(invocation->{sampled.countDown();return null;});var worker=worker("2026-11-06T20:00:00Z");
        try{worker.start();assertTrue(sampled.await(2,TimeUnit.SECONDS));}finally{worker.stop();}
        assertEquals(2,metrics.get("game.reconciliation.runs").tag("status","DONE_WITH_ISSUES").gauge().value());assertEquals(0,metrics.get("game.reconciliation.failed").counter().count());
    }
    @Test void lateStoppedGenerationCannotScheduleAnExtraPollInRestartedWorker() throws Exception {
        CountDownLatch firstEntered=new CountDownLatch(1),releaseOld=new CountDownLatch(1),oldExited=new CountDownLatch(1),newEntered=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var oldThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(jobs.claim()).thenAnswer(invocation->{
            if(calls.incrementAndGet()==1){oldThread.set(Thread.currentThread());firstEntered.countDown();boolean released=false;while(!released)try{releaseOld.await();released=true;}catch(InterruptedException ignored){/* 模拟不能由线程中断取消的数据库工作。 */}oldExited.countDown();}
            else newEntered.countDown();return null;
        });
        var worker=worker("2026-11-06T20:00:00Z");
        try{
            worker.start();assertTrue(firstEntered.await(2,TimeUnit.SECONDS));worker.stop();worker.start();assertTrue(newEntered.await(2,TimeUnit.SECONDS));releaseOld.countDown();assertTrue(oldExited.await(2,TimeUnit.SECONDS));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((oldThread.get().isAlive()||metrics.get("game.reconciliation.scheduled").gauge().value()!=1)&&System.nanoTime()<until)Thread.sleep(1);
            assertFalse(oldThread.get().isAlive());assertEquals(1,metrics.get("game.reconciliation.scheduled").gauge().value());assertEquals(2,calls.get());
        }finally{releaseOld.countDown();worker.stop();}
    }
}
