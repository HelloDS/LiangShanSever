package com.ruoyi.game.room;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomTimersTest {
    private static final RoomMailboxes.Config CONFIG=new RoomMailboxes.Config(1,64,16,16,2,16,10);
    private RoomPersistence<Integer,Integer,Integer> persistence(){return new RoomPersistence<>(){public Decision commit(Write<Integer,Integer,Integer> write){return Decision.COMMITTED;}public Decision resolve(Write<Integer,Integer,Integer> write){return Decision.COMMITTED;}};}
    private RoomMailboxes.Handle register(RoomMailboxes<Integer,Integer,Integer> rooms,String id,long epoch){return rooms.register(new RoomSnapshot<>(id,epoch,0,1,"PLAYING",0),(before,command)->new RoomTransition<>(1,"PLAYING",before.state()+command,command));}
    private void await(BooleanSupplier condition) throws InterruptedException {
        long began=System.nanoTime();while(!condition.getAsBoolean()){if(System.nanoTime()-began>2_000_000_000L)fail("定时器结果未在限定时间到达");Thread.sleep(2);}
    }
    @Test void oneSharedTimerReentersMailboxAndInstallsOnlyCommittedState() throws Exception {
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence());var timers=new RoomTimers<>(rooms,4)){
            var owner=register(rooms,UUID.randomUUID().toString(),1);timers.schedule(owner,RoomMailboxes.Fence.of(rooms.snapshot(owner)),RoomTimers.Kind.TURN_TIMEOUT,Duration.ofMillis(20),1);
            await(()->rooms.snapshot(owner).state()==1);assertEquals(1,timers.stats().fired());assertEquals(0,timers.stats().pendingKeys());
        }
    }
    @Test void aTimerQueuedDuringCommitCannotActOnTheNextVersion() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var persistence=new RoomPersistence<Integer,Integer,Integer>(){
            public Decision commit(Write<Integer,Integer,Integer> write){entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("gate timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return Decision.COMMITTED;}
            public Decision resolve(Write<Integer,Integer,Integer> write){return Decision.COMMITTED;}
        };
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence);var timers=new RoomTimers<>(rooms,4)){
            var owner=register(rooms,UUID.randomUUID().toString(),1);var fence=RoomMailboxes.Fence.of(rooms.snapshot(owner));
            var first=rooms.submit(owner,1);assertTrue(entered.await(1,TimeUnit.SECONDS));
            timers.schedule(owner,fence,RoomTimers.Kind.AUTO_PLAY,Duration.ofMillis(20),99);await(()->timers.stats().fired()==1);release.countDown();first.toCompletableFuture().get(2,TimeUnit.SECONDS);
            await(()->timers.stats().stale()==1);assertEquals(1,rooms.snapshot(owner).state());
        }finally{release.countDown();}
    }
    @Test void replacementAtCapacityKeepsOnlyTheLatestCommand() throws Exception {
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence());var timers=new RoomTimers<>(rooms,1)){
            var owner=register(rooms,UUID.randomUUID().toString(),1);var fence=RoomMailboxes.Fence.of(rooms.snapshot(owner));
            timers.schedule(owner,fence,RoomTimers.Kind.VOTE_EXPIRY,Duration.ofHours(1),1);
            timers.schedule(owner,fence,RoomTimers.Kind.VOTE_EXPIRY,Duration.ofHours(1),2);assertEquals(1,timers.stats().pendingKeys());
            timers.schedule(owner,fence,RoomTimers.Kind.VOTE_EXPIRY,Duration.ofMillis(20),2);
            await(()->rooms.snapshot(owner).state()==2);assertEquals(2,timers.stats().replaced());assertEquals(1,timers.stats().fired());
        }
    }
    @Test void capacityCancellationAndShutdownReleaseAllRegistrations(){
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence());var timers=new RoomTimers<>(rooms,1)){
            var first=register(rooms,UUID.randomUUID().toString(),1);var second=register(rooms,UUID.randomUUID().toString(),1);
            var firstFence=RoomMailboxes.Fence.of(rooms.snapshot(first));var secondFence=RoomMailboxes.Fence.of(rooms.snapshot(second));
            timers.schedule(first,firstFence,RoomTimers.Kind.WAITING_EXPIRY,Duration.ofHours(1),1);
            assertEquals("TIMERS_BUSY",assertThrows(RoomRejected.class,()->timers.schedule(second,secondFence,RoomTimers.Kind.WAITING_EXPIRY,Duration.ofHours(1),1)).code());
            assertTrue(timers.cancel(first,RoomTimers.Kind.WAITING_EXPIRY));timers.schedule(second,secondFence,RoomTimers.Kind.WAITING_EXPIRY,Duration.ofHours(1),1);
            timers.close();assertEquals(0,timers.stats().pendingKeys());assertEquals(0,timers.stats().queuedTasks());
            assertEquals("TIMERS_STOPPED",assertThrows(RoomRejected.class,()->timers.schedule(first,firstFence,RoomTimers.Kind.WAITING_EXPIRY,Duration.ofMillis(20),1)).code());
        }
    }
    @Test void delayedOldOwnerCleanupCannotCancelNewOwnerTimer() throws Exception {
        AtomicBoolean rejectOld=new AtomicBoolean(true);
        var persistence=new RoomPersistence<Integer,Integer,Integer>(){
            public Decision commit(Write<Integer,Integer,Integer> write){return rejectOld.get()?Decision.OWNER_CHANGED:Decision.COMMITTED;}
            public Decision resolve(Write<Integer,Integer,Integer> write){return Decision.COMMITTED;}
        };
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence);var timers=new RoomTimers<>(rooms,1)){
            String id=UUID.randomUUID().toString();var old=register(rooms,id,1);timers.schedule(old,RoomMailboxes.Fence.of(rooms.snapshot(old)),RoomTimers.Kind.TURN_TIMEOUT,Duration.ofHours(1),1);
            assertThrows(ExecutionException.class,()->rooms.submit(old,1).toCompletableFuture().get(2,TimeUnit.SECONDS));rejectOld.set(false);
            var fresh=register(rooms,id,2);var fence=RoomMailboxes.Fence.of(rooms.snapshot(fresh));timers.schedule(fresh,fence,RoomTimers.Kind.TURN_TIMEOUT,Duration.ofHours(1),2);
            assertEquals(0,timers.cancelRoom(old));assertEquals(1,timers.stats().pendingKeys());
            timers.schedule(fresh,fence,RoomTimers.Kind.TURN_TIMEOUT,Duration.ofMillis(20),2);await(()->rooms.snapshot(fresh).state()==2);
        }
    }
    @Test void repeatedCancellationDoesNotAccumulateScheduledTasksOrLeakCapacity(){
        try(var rooms=new RoomMailboxes<>(CONFIG,persistence());var timers=new RoomTimers<>(rooms,1)){
            var owner=register(rooms,UUID.randomUUID().toString(),1);var fence=RoomMailboxes.Fence.of(rooms.snapshot(owner));
            for(int cycle=0;cycle<500;cycle++){
                timers.schedule(owner,fence,RoomTimers.Kind.TURN_TIMEOUT,Duration.ofHours(1),1);
                assertTrue(timers.cancel(owner,RoomTimers.Kind.TURN_TIMEOUT));assertEquals(0,timers.stats().pendingKeys());assertEquals(0,timers.stats().queuedTasks());
            }
        }
    }
}
