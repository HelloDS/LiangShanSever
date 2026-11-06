package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OnlinePresence {
    private static final String INDEX = "game:online:index";
    private static final DefaultRedisScript<Long> UPDATE = new DefaultRedisScript<>("""
        local epoch = redis.call('HGET', KEYS[1], 'epoch')
        if epoch and tonumber(epoch) > tonumber(ARGV[1]) then return 0 end
        redis.call('HSET', KEYS[1], 'epoch', ARGV[1])
        redis.call('PEXPIREAT', KEYS[1], ARGV[2])
        redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'epoch') == ARGV[1] then
          redis.call('DEL', KEYS[1]); return redis.call('ZREM', KEYS[2], ARGV[2])
        end
        return 0
        """, Long.class);
    private final ConnectionRegistry registry;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private volatile String status = "REBUILDING";
    public OnlinePresence(ConnectionRegistry registry, StringRedisTemplate redis, Clock clock) {
        this.registry=registry; this.redis=redis; this.clock=clock;
    }
    public void update(ConnectionRegistry.Binding binding) {
        if (!registry.current(binding)) return;
        long expires = binding.lastActivity()+90_000;
        if (expires <= clock.millis()) return;
        redis.execute(UPDATE, List.of("game:online:"+binding.identity().uid(),INDEX),
                Long.toString(binding.epoch()),Long.toString(expires),Long.toString(binding.identity().uid()));
    }
    public void remove(ConnectionRegistry.Binding binding) {
        if (binding == null) return;
        redis.execute(REMOVE,List.of("game:online:"+binding.identity().uid(),INDEX),
                Long.toString(binding.epoch()),Long.toString(binding.identity().uid()));
    }
    @Scheduled(fixedDelay = 30_000, initialDelay = 0)
    public void refresh() {
        try {
            redis.hasKey(INDEX); // Redis 无法连接时不能把空 registry 当成在线为零。
            for (var binding : registry.all()) update(binding);
            redis.opsForZSet().removeRangeByScore(INDEX,Double.NEGATIVE_INFINITY,clock.millis());
            status="AVAILABLE";
        } catch (RuntimeException e) { status="UNAVAILABLE"; }
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("asOf",clock.instant().toString());
        try {
            Set<String> uids=redis.opsForZSet().rangeByScore(INDEX,clock.millis()+1,Double.POSITIVE_INFINITY);
            if (!"AVAILABLE".equals(status)) { result.put("dataStatus",status); result.put("onlineCount",null); }
            else { result.put("dataStatus","AVAILABLE"); result.put("onlineCount",uids==null?0:uids.size()); }
        } catch (RuntimeException e) { status="UNAVAILABLE"; result.put("dataStatus",status); result.put("onlineCount",null); }
        return result;
    }
}
