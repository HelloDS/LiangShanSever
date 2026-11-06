package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.ruoyi.game.auth.GuestIdentity;
import org.springframework.stereotype.Component;
import io.netty.channel.Channel;

@Component
public class ConnectionRegistry {
    private final Map<Long, Binding> players = new ConcurrentHashMap<>();
    private final AtomicLong epochs = new AtomicLong(System.currentTimeMillis() * 1000);
    private final Clock clock;
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
