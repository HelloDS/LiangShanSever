package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import com.ruoyi.game.auth.GuestIdentity;
import org.springframework.stereotype.Component;
import io.netty.channel.Channel;

@Component
public class ConnectionRegistry {
    private final Map<Long, Binding> players = new ConcurrentHashMap<>();
    private final AtomicLong epochs = new AtomicLong(System.currentTimeMillis() * 1000);
    private final Clock clock;
    private final Semaphore pushSlots=new Semaphore(32768);
    public ConnectionRegistry(Clock clock) { this.clock = clock; }
    public Binding bind(GuestIdentity identity, Channel channel, String token) {
        Binding binding = new Binding(identity, channel, epochs.incrementAndGet(), token, clock.millis());
        channel.closeFuture().addListener(ignored->binding.discardDeferred());
        Binding old = players.put(identity.uid(), binding);
        if (old != null && old.channel() != channel) old.channel().close();
        return binding;
    }
    public boolean current(Binding binding) { return binding != null && players.get(binding.identity().uid()) == binding; }
    public boolean remove(Binding binding) { return binding != null && players.remove(binding.identity().uid(), binding); }
    public Collection<Binding> all() { return List.copyOf(players.values()); }
    /** 仅在连接 EventLoop 中调用，普通请求在途期间先暂存有界推送。 */
    public void requestStarted(Binding binding){
        if(binding!=null){if(!binding.channel().eventLoop().inEventLoop())throw new IllegalStateException("请求推送围栏必须在连接 EventLoop");binding.pushReady=false;}
    }
    /** 必须在普通 ACK 排入同一连接输出后调用，包含首次登录 ACK。 */
    public void ackQueued(Binding binding){
        if(binding==null)return;
        if(!binding.channel().eventLoop().inEventLoop())throw new IllegalStateException("推送放行必须在连接 EventLoop");
        binding.pushReady=true;
        PendingPush pending;while((pending=binding.deferred.poll())!=null)write(binding,pending);
    }
    private record PendingPush(String packet,Runnable release){ }
    private void write(Binding binding,PendingPush pending){
        if(!current(binding)||!binding.channel().isActive()){pending.release().run();return;}
        if(!binding.channel().isWritable()){pending.release().run();binding.channel().close();return;}
        try{binding.channel().writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(pending.packet())).addListener(ignored->pending.release().run());}
        catch(RuntimeException failure){pending.release().run();binding.channel().close();}
    }
    /** 调用方先将引发本次变更的 ACK 排入输出，再投递推送。 */
    public void push(List<Long> recipients,String packet){
        int bytes=io.netty.buffer.ByteBufUtil.utf8Bytes(packet);
        for(long uid:recipients){
            Binding binding=players.get(uid);if(binding==null)continue;
            if(!pushSlots.tryAcquire()){binding.channel().close();continue;}
            if(!binding.reservePush(bytes)){pushSlots.release();binding.channel().close();continue;}
            AtomicBoolean released=new AtomicBoolean();
            Runnable release=()->{if(released.compareAndSet(false,true)){binding.releasePush(bytes);pushSlots.release();}};
            try{
                binding.channel().eventLoop().execute(()->{
                    if(!current(binding)||!binding.channel().isActive()){release.run();return;}
                    PendingPush pending=new PendingPush(packet,release);
                    if(!binding.pushReady)binding.deferred.add(pending);else write(binding,pending);
                });
            }catch(java.util.concurrent.RejectedExecutionException e){release.run();binding.channel().close();}
        }
    }
    public int pendingPushes(){return 32768-pushSlots.availablePermits();}
    public void disconnect(long uid) {
        Binding binding = players.remove(uid);
        if (binding != null) binding.channel().close();
    }
    /** 只关闭被观察到的连接，异步清理不能误伤同 UID 的新连接。 */
    public boolean disconnect(Binding binding) {
        if (!remove(binding)) return false;
        binding.channel().close();
        return true;
    }
    /** 管理命令只撤销命令提交前的会话版本。 */
    public void disconnectBeforeVersion(long uid, long version) {
        Binding binding = players.get(uid);
        if (binding != null && binding.identity().sessionVersion() < version) disconnect(binding);
    }
    public static final class Binding {
        private final GuestIdentity identity;
        private final Channel channel;
        private final long epoch;
        private final String token;
        private final Queue<PendingPush> deferred=new java.util.concurrent.ConcurrentLinkedQueue<>();
        private boolean pushReady;
        private void discardDeferred(){PendingPush pending;while((pending=deferred.poll())!=null)pending.release().run();}
        private volatile long lastActivity;
        private final AtomicInteger pendingPushes=new AtomicInteger();
        private final AtomicLong pendingPushBytes=new AtomicLong();
        private boolean reservePush(int bytes){
            if(pendingPushes.incrementAndGet()>64){pendingPushes.decrementAndGet();return false;}
            if(pendingPushBytes.addAndGet(bytes)>1024*1024){pendingPushBytes.addAndGet(-bytes);pendingPushes.decrementAndGet();return false;}
            return true;
        }
        private void releasePush(int bytes){pendingPushBytes.addAndGet(-bytes);pendingPushes.decrementAndGet();}
        Binding(GuestIdentity identity, Channel channel, long epoch, String token, long now) {
            this.identity=identity; this.channel=channel; this.epoch=epoch; this.token=token; lastActivity=now;
        }
        public GuestIdentity identity() { return identity; }
        public Channel channel() { return channel; }
        public long epoch() { return epoch; }
        public String token() { return token; }
        public long lastActivity() { return lastActivity; }
        public void activity(long now) { lastActivity = now; }
    }
}
