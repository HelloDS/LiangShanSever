package com.ruoyi.game.room;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;
import com.ruoyi.game.room.RoomPersistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomMailboxesTest {
    private static final RoomMailboxes.Engine<Integer,Integer,Integer> ADD=(before,value)->
            new RoomTransition<>(before.round(),before.phase(),Math.addExact(before.state(),value),value);
    private static RoomSnapshot<Integer> state(String id){return new RoomSnapshot<>(id,1,0,0,"WAITING",0);}
    private static RoomMailboxes.Config config(int commands,int perRoom){return new RoomMailboxes.Config(1,commands,8,perRoom,2,8,5);}
    private static final class Store implements RoomPersistence<Integer,Integer,Integer>{
        final AtomicInteger commits=new AtomicInteger(),resolves=new AtomicInteger();
        volatile Function<Write<Integer,Integer,Integer>,Decision> commit=write->Decision.COMMITTED;
        volatile Function<Write<Integer,Integer,Integer>,Decision> resolve=write->Decision.COMMITTED;
        public Decision commit(Write<Integer,Integer,Integer> write){commits.incrementAndGet();return commit.apply(write);}
        public Decision resolve(Write<Integer,Integer,Integer> write){resolves.incrementAndGet();return resolve.apply(write);}
    }
    private static void waitFor(BooleanSupplier condition){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)LockSupport.parkNanos(1_000_000);
        assertTrue(condition.getAsBoolean(),"有界等待内未观察到目标状态");
    }
    private static void await(CountDownLatch latch){
        try{if(!latch.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("fixture interrupted");}
    }
    private static RoomSnapshot<Integer> result(CompletionStage<RoomSnapshot<Integer>> future) throws Exception{return future.toCompletableFuture().get(3,TimeUnit.SECONDS);}
    private static String rejection(CompletionStage<?> future){
        var failure=assertThrows(ExecutionException.class,()->future.toCompletableFuture().get(3,TimeUnit.SECONDS));
        return assertInstanceOf(RoomRejected.class,failure.getCause()).code();
    }

    @Test void serialCandidatesArePublishedOnlyAfterPersistenceAndAnotherRoomKeepsRunning() throws Exception {
        Store store=new Store();String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        store.commit=write->{if(write.previous().roomId().equals(first)){entered.countDown();await(release);}return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(16,4),store)){
            var a=rooms.register(state(first),ADD);var b=rooms.register(state(second),ADD);
            var one=rooms.submit(a,1);await(entered);var two=rooms.submit(a,2);
            assertFalse(one.toCompletableFuture().isDone());assertEquals(0,rooms.snapshot(a).state());
            assertEquals(1,result(rooms.submit(b,1)).state());
            release.countDown();assertEquals(1,result(one).state());assertEquals(3,result(two).state());assertEquals(2,rooms.snapshot(a).version());
        }finally{release.countDown();}
    }
    @Test void acceptedCommandsHaveOneSerialEngineAndMonotonicPersistedVersions() throws Exception {
        Store store=new Store();List<Long> versions=new CopyOnWriteArrayList<>();Set<String> ioThreads=ConcurrentHashMap.newKeySet(),engineThreads=ConcurrentHashMap.newKeySet();
        store.commit=write->{versions.add(write.candidate().version());ioThreads.add(Thread.currentThread().getName());return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(64,64),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),(before,value)->{engineThreads.add(Thread.currentThread().getName());return ADD.apply(before,value);});
            List<CompletionStage<RoomSnapshot<Integer>>> actions=new ArrayList<>();
            for(int i=0;i<32;i++)actions.add(rooms.submit(handle,1));
            for(int i=0;i<32;i++){assertEquals(i+1,result(actions.get(i)).state());assertEquals(i+1,result(actions.get(i)).version());}
            assertEquals(32,store.commits.get());assertEquals(1,engineThreads.size());assertTrue(engineThreads.iterator().next().startsWith("room-shard-"));
            assertTrue(ioThreads.stream().allMatch(name->name.startsWith("room-io-")));assertEquals(32,versions.stream().distinct().count());
            for(int i=0;i<32;i++)assertEquals(i+1,versions.get(i));
        }
    }
    @Test void roomAndShardAdmissionAreBoundedWithoutBlockingCaller() throws Exception {
        Store store=new Store();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        store.commit=write->{entered.countDown();await(release);return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(4,2),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);
            var first=rooms.submit(handle,1);await(entered);var second=rooms.submit(handle,1);
            assertEquals("ROOM_BUSY",rejection(rooms.submit(handle,1)));assertEquals(2,rooms.pendingCommands());
            release.countDown();result(first);assertEquals(2,result(second).state());waitFor(()->rooms.pendingCommands()==0);
        }finally{release.countDown();}
    }
    @Test void completionUsesReservedCapacityWhenOrdinaryAdmissionIsExhausted() throws Exception {
        Store store=new Store();String aId=UUID.randomUUID().toString();
        CountDownLatch dbEntered=new CountDownLatch(1),dbRelease=new CountDownLatch(1),engineEntered=new CountDownLatch(1),engineRelease=new CountDownLatch(1);
        store.commit=write->{if(write.previous().roomId().equals(aId)){dbEntered.countDown();await(dbRelease);}return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(4,2),store)){
            var a=rooms.register(state(aId),ADD);
            var b=rooms.register(state(UUID.randomUUID().toString()),(before,value)->{engineEntered.countDown();await(engineRelease);return ADD.apply(before,value);});
            var c=rooms.register(state(UUID.randomUUID().toString()),ADD);var d=rooms.register(state(UUID.randomUUID().toString()),ADD);
            var first=rooms.submit(a,1);await(dbEntered);var second=rooms.submit(b,1);await(engineEntered);
            var third=rooms.submit(c,1);var fourth=rooms.submit(d,1);
            assertEquals("SHARD_BUSY",rejection(rooms.submit(c,1)));assertEquals(4,rooms.pendingCommands());
            dbRelease.countDown();waitFor(()->rooms.pendingCompletions()>0);assertFalse(first.toCompletableFuture().isDone());
            engineRelease.countDown();assertEquals(1,result(first).state());result(second);result(third);result(fourth);waitFor(()->rooms.pendingCommands()==0);
        }finally{dbRelease.countDown();engineRelease.countDown();}
    }
    @Test void unknownCommitKeepsRoomPausedUntilPersistentResultIsConfirmed() throws Exception {
        Store store=new Store();AtomicBoolean confirmed=new AtomicBoolean();AtomicInteger applied=new AtomicInteger();
        store.commit=write->store.commits.get()==1?Decision.UNKNOWN:Decision.COMMITTED;
        store.resolve=write->confirmed.get()?Decision.COMMITTED:Decision.UNKNOWN;
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),(before,value)->{applied.incrementAndGet();return ADD.apply(before,value);});
            var first=rooms.submit(handle,1);waitFor(()->store.resolves.get()>0);var second=rooms.submit(handle,1);
            assertEquals(0,rooms.snapshot(handle).state());assertFalse(first.toCompletableFuture().isDone());assertEquals(1,applied.get());
            assertTrue(rooms.pendingReconciliations()<=1);confirmed.set(true);
            assertEquals(1,result(first).state());assertEquals(2,result(second).state());assertEquals(2,applied.get());assertEquals(2,store.commits.get());
        }
    }
    @Test void retriesKeepOriginalServerOccurrenceTime() throws Exception {
        Store store=new Store();var instant=java.time.Instant.parse("2026-11-06T15:59:59.123456Z");
        List<java.time.Instant> observed=new CopyOnWriteArrayList<>();
        store.commit=write->{observed.add(write.occurredAt());return Decision.UNKNOWN;};
        store.resolve=write->{observed.add(write.occurredAt());return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store,java.time.Clock.fixed(instant,java.time.ZoneOffset.UTC))){
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);assertEquals(1,result(rooms.submit(handle,1)).state());
            assertEquals(List.of(instant,instant),observed);
        }
    }
    @Test void knownRollbackDiscardsCandidateAndNextCommandUsesCommittedState() throws Exception {
        Store store=new Store();store.commit=write->store.commits.get()==1?Decision.NOT_COMMITTED:Decision.COMMITTED;
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);
            assertEquals("NOT_COMMITTED",rejection(rooms.submit(handle,5)));assertEquals(0,rooms.snapshot(handle).state());
            var next=result(rooms.submit(handle,1));assertEquals(1,next.state());assertEquals(1,next.version());
        }
    }
    @Test void storageExceptionIsUnknownAndDoesNotSendPrematureFailure() throws Exception {
        Store store=new Store();AtomicBoolean confirmed=new AtomicBoolean();
        store.commit=write->{throw new IllegalStateException("commit result unavailable");};
        store.resolve=write->confirmed.get()?Decision.COMMITTED:Decision.UNKNOWN;
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);var first=rooms.submit(handle,1);
            waitFor(()->store.resolves.get()>0);assertFalse(first.toCompletableFuture().isDone());assertEquals(0,rooms.snapshot(handle).state());
            confirmed.set(true);assertEquals(1,result(first).state());assertEquals(1,store.commits.get());
        }
    }
    @Test void staleTimerAndWrongOwnerNeverReachEngineOrDatabase() throws Exception {
        Store store=new Store();
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);var fence=RoomMailboxes.Fence.of(rooms.snapshot(handle));
            result(rooms.submit(handle,1));assertEquals("STALE_TIMER",rejection(rooms.submitTimer(handle,fence,1)));
            assertEquals("NOT_OWNED",rejection(rooms.submit(new RoomMailboxes.Handle(handle.roomId(),2),1)));assertEquals(1,store.commits.get());
        }
    }
    @Test void ownershipLossRejectsBufferedCommandsAndAllowsNewEpochRegistration() throws Exception {
        Store store=new Store();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        store.commit=write->{entered.countDown();await(release);return Decision.OWNER_CHANGED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            String id=UUID.randomUUID().toString();var old=rooms.register(state(id),ADD);
            var first=rooms.submit(old,1);await(entered);var second=rooms.submit(old,1);release.countDown();
            assertEquals("OWNER_CHANGED",rejection(first));assertTrue(Set.of("OWNER_CHANGED","NOT_OWNED").contains(rejection(second)));
            var fresh=rooms.register(new RoomSnapshot<>(id,2,1,0,"WAITING",1),ADD);
            assertEquals("NOT_OWNED",rejection(rooms.submit(old,1)));assertEquals(1,rooms.snapshot(fresh).state());
        }finally{release.countDown();}
    }
    @Test void slowResultCallbackRunsOutsideShardAndDoesNotBlockAnotherRoom() throws Exception {
        Store store=new Store();CountDownLatch dbEntered=new CountDownLatch(1),dbRelease=new CountDownLatch(1),callbackEntered=new CountDownLatch(1),callbackRelease=new CountDownLatch(1);
        String firstId=UUID.randomUUID().toString();AtomicReference<String> callbackThread=new AtomicReference<>();
        store.commit=write->{if(write.previous().roomId().equals(firstId)){dbEntered.countDown();await(dbRelease);}return Decision.COMMITTED;};
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var a=rooms.register(state(firstId),ADD);var b=rooms.register(state(UUID.randomUUID().toString()),ADD);
            var first=rooms.submit(a,1);await(dbEntered);
            first.whenComplete((value,failure)->{callbackThread.set(Thread.currentThread().getName());callbackEntered.countDown();await(callbackRelease);});
            dbRelease.countDown();await(callbackEntered);assertTrue(callbackThread.get().startsWith("room-result-"));
            assertEquals(1,result(rooms.submit(b,1)).state());assertEquals(1,rooms.snapshot(a).state());callbackRelease.countDown();
        }finally{dbRelease.countDown();callbackRelease.countDown();}
    }
    @Test void shutdownCompletesPendingAsUnknownAndReleasesAdmission() throws Exception {
        Store store=new Store();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        store.commit=write->{entered.countDown();await(release);return Decision.COMMITTED;};
        var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store);
        try{
            var handle=rooms.register(state(UUID.randomUUID().toString()),ADD);var first=rooms.submit(handle,1);await(entered);var second=rooms.submit(handle,1);
            rooms.close();assertEquals("STOPPED_OUTCOME_UNKNOWN",rejection(first));assertEquals("STOPPED_OUTCOME_UNKNOWN",rejection(second));
            waitFor(()->rooms.pendingCommands()==0);assertEquals("NOT_OWNED",rejection(rooms.submit(handle,1)));rooms.close();
        }finally{release.countDown();rooms.close();}
    }
    @Test void rejectedEngineDoesNotPersistAndRoomCanAcceptNextLegalAction() throws Exception {
        Store store=new Store();
        try(var rooms=new RoomMailboxes<Integer,Integer,Integer>(config(8,4),store)){
            var handle=rooms.register(state(UUID.randomUUID().toString()),(before,value)->{if(value<0)throw new RoomRejected("INVALID_ACTION");return ADD.apply(before,value);});
            assertEquals("INVALID_ACTION",rejection(rooms.submit(handle,-1)));assertEquals(0,store.commits.get());assertEquals(1,result(rooms.submit(handle,1)).state());
        }
    }
}
