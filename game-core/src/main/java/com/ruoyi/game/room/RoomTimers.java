package com.ruoyi.game.room;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 有界共享定时器。只重新投递带围栏的不可变命令，不在计时线程执行引擎或数据库。 */
public final class RoomTimers<S,C,E> implements AutoCloseable {
    public enum Kind { TURN_TIMEOUT,AUTO_PLAY,VOTE_EXPIRY,WAITING_EXPIRY }
    private record Key(String roomId,Kind kind){ }
    private final class Ticket {
        final RoomMailboxes.Handle owner;
        final RoomMailboxes.Fence fence;
        final C command;
        ScheduledFuture<?> future;
        Ticket(RoomMailboxes.Handle owner,RoomMailboxes.Fence fence,C command){this.owner=owner;this.fence=fence;this.command=command;}
    }
    public record Stats(int pendingKeys,int queuedTasks,long fired,long stale,long rejected,long replaced){ }
    private final RoomMailboxes<S,C,E> rooms;
    private final Semaphore slots;
    private final ConcurrentMap<Key,Ticket> registrations=new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock();
    private final AtomicLong fired=new AtomicLong(),stale=new AtomicLong(),rejected=new AtomicLong(),replaced=new AtomicLong();
    private boolean accepting=true;
    public RoomTimers(RoomMailboxes<S,C,E> rooms,int maximumKeys){
        this.rooms=Objects.requireNonNull(rooms);
        if(maximumKeys<1||maximumKeys>32768)throw new IllegalArgumentException("房间定时器容量无效");
        slots=new Semaphore(maximumKeys);
        scheduler=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("room-timers").daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    /** 每个永久房间/用途只保留最新待触发项。delay 必须来自冻结规则，此处没有超时默认值。 */
    public void schedule(RoomMailboxes.Handle owner,RoomMailboxes.Fence fence,Kind kind,Duration delay,C command){
        Objects.requireNonNull(owner);Objects.requireNonNull(fence);Objects.requireNonNull(kind);Objects.requireNonNull(delay);Objects.requireNonNull(command);
        if(delay.compareTo(Duration.ofMillis(1))<0||delay.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("房间定时器延迟无效");
        if(owner.ownerEpoch()!=fence.ownerEpoch())throw new IllegalArgumentException("定时器所有者围栏不一致");
        lifecycle.readLock().lock();
        try{
            if(!accepting)throw new RoomRejected("TIMERS_STOPPED");
            if(!RoomMailboxes.Fence.of(rooms.snapshot(owner)).equals(fence))throw new RoomRejected("STALE_TIMER");
            Key key=new Key(owner.roomId(),kind);
            registrations.compute(key,(ignored,previous)->{
                // 已有键转移同一票据，容量满时仍能安全替换，不先取消旧项再发现无法准入。
                boolean acquired=previous==null;
                if(acquired&&!slots.tryAcquire())throw new RoomRejected("TIMERS_BUSY");
                Ticket next=new Ticket(owner,fence,command);
                try{next.future=scheduler.schedule(()->fire(key,next),delay.toNanos(),TimeUnit.NANOSECONDS);}
                catch(RejectedExecutionException failure){if(acquired)slots.release();throw new RoomRejected("TIMERS_STOPPED");}
                if(previous!=null){previous.future.cancel(false);replaced.incrementAndGet();}
                return next;
            });
        }finally{lifecycle.readLock().unlock();}
    }
    private void fire(Key key,Ticket ticket){
        lifecycle.readLock().lock();
        try{
            if(!accepting||!registrations.remove(key,ticket))return;
            slots.release();fired.incrementAndGet();
            // 触发后改由邮箱本身的有界命令预算持有；计时线程不等待数据库结果。
            rooms.submitTimer(ticket.owner,ticket.fence,ticket.command).whenComplete((state,error)->{
                if(error==null)return;
                Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                if(cause instanceof RoomRejected failure&&"STALE_TIMER".equals(failure.code()))stale.incrementAndGet();else rejected.incrementAndGet();
            });
        }catch(RuntimeException failure){rejected.incrementAndGet();}
        finally{lifecycle.readLock().unlock();}
    }
    public boolean cancel(RoomMailboxes.Handle owner,Kind kind){
        Objects.requireNonNull(owner);Objects.requireNonNull(kind);AtomicBoolean cancelled=new AtomicBoolean();
        lifecycle.readLock().lock();
        try{
            registrations.computeIfPresent(new Key(owner.roomId(),kind),(ignored,ticket)->{
                if(ticket.owner.ownerEpoch()!=owner.ownerEpoch())return ticket;
                ticket.future.cancel(false);slots.release();cancelled.set(true);return null;
            });
            return cancelled.get();
        }finally{lifecycle.readLock().unlock();}
    }
    public int cancelRoom(RoomMailboxes.Handle owner){int cancelled=0;for(Kind kind:Kind.values())if(cancel(owner,kind))cancelled++;return cancelled;}
    public Stats stats(){return new Stats(registrations.size(),scheduler.getQueue().size(),fired.get(),stale.get(),rejected.get(),replaced.get());}
    @Override public void close(){
        lifecycle.writeLock().lock();
        try{
            if(!accepting)return;accepting=false;scheduler.shutdownNow();
            registrations.forEach((key,ticket)->ticket.future.cancel(false));slots.release(registrations.size());registrations.clear();
        }finally{lifecycle.writeLock().unlock();}
    }
}
