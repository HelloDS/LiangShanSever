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
        Binding old = players.put(identity.uid(), binding);
        if (old != null && old.channel() != channel) old.channel().close();
        return binding;
    }
    public boolean current(Binding binding) { return binding != null && players.get(binding.identity().uid()) == binding; }
    public boolean remove(Binding binding) { return binding != null && players.remove(binding.identity().uid(), binding); }
    public Collection<Binding> all() { return List.copyOf(players.values()); }
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
                    if(!binding.channel().isWritable()){release.run();binding.channel().close();return;}
                    try{binding.channel().writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(packet)).addListener(ignored->release.run());}
                    catch(RuntimeException e){release.run();binding.channel().close();}
                });
            }catch(java.util.concurrent.RejectedExecutionException e){release.run();binding.channel().close();}
        }
    }
    public int pendingPushes(){return 32768-pushSlots.availablePermits();}
    public void disconnect(long uid) {
        Binding binding = players.remove(uid);
        if (binding != null) binding.channel().close();
    }
    public static final class Binding {
        private final GuestIdentity identity;
        private final Channel channel;
        private final long epoch;
        private final String token;
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
