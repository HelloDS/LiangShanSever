package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.game.persistence.mapper.GameAdminMapper;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.auth.GameSessionService;

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
    private final GameAdminMapper players;
    private final GameSessionService sessions;
    private volatile String status = "REBUILDING";
    public OnlinePresence(ConnectionRegistry registry, StringRedisTemplate redis, Clock clock, GameAdminMapper players, GameSessionService sessions) {
        this.registry=registry; this.redis=redis; this.clock=clock; this.players=players; this.sessions=sessions;
    }
    public void update(ConnectionRegistry.Binding binding) {
        if (!registry.current(binding)) return;
        long expires = binding.lastActivity()+90_000;
        if (expires <= clock.millis()) return;
        Long updated=redis.execute(UPDATE, List.of("game:online:"+binding.identity().uid(),INDEX),
                Long.toString(binding.epoch()),Long.toString(expires),Long.toString(binding.identity().uid()));
        if (!Long.valueOf(1).equals(updated)) throw new IllegalStateException("在线索引版本未更新");
    }
    public void connected(ConnectionRegistry.Binding binding){
        try{update(binding);}catch(RuntimeException e){status="UNAVAILABLE";}
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
            for (var binding : registry.all()) {
                try {sessions.verify(binding.token());update(binding);}
                catch(GameFailure e){registry.disconnect(binding);remove(binding);}
            }
            redis.opsForZSet().removeRangeByScore(INDEX,Double.NEGATIVE_INFINITY,clock.millis());
            status="AVAILABLE";
        } catch (RuntimeException e) { status="UNAVAILABLE"; }
    }
    public record Sample(String dataStatus,String asOf,Set<Long> uids) { }
    public Sample sample(){return sample(false);}
    public Sample sample(boolean includeTest) {
        String asOf=clock.instant().toString();
        try {
            Set<String> uids=redis.opsForZSet().rangeByScore(INDEX,clock.millis()+1,Double.POSITIVE_INFINITY);
            if (!"AVAILABLE".equals(status)) return new Sample(status,asOf,null);
            if(uids!=null&&uids.size()>10000)return new Sample("UNAVAILABLE",asOf,null);
            Set<Long> indexed=new HashSet<>();
            if(uids!=null)for(String uid:uids)indexed.add(Long.parseLong(uid));
            var live=registry.all().stream().filter(binding->registry.current(binding)
                    && binding.channel().isActive() && binding.lastActivity()+90_000>clock.millis()).toList();
            // 单进程连接表是在线事实；重启遗留的 Redis UID 不能制造幽灵在线。
            // 索引丢失时返回未知，等刷新重建，不能把有连接的玩家显示为零。
            if(live.stream().anyMatch(binding->!indexed.contains(binding.identity().uid()))) {
                status="REBUILDING";return new Sample(status,asOf,null);
            }
            if(live.isEmpty())return new Sample("AVAILABLE",asOf,Set.of());
            Set<Long> valid=new HashSet<>(players.activeIdentities(live.stream().map(ConnectionRegistry.Binding::identity).toList(),
                    includeTest,java.time.LocalDateTime.ofInstant(clock.instant(),java.time.ZoneOffset.UTC)));
            return new Sample("AVAILABLE",asOf,Set.copyOf(valid));
        } catch (RuntimeException e) { status="UNAVAILABLE"; return new Sample(status,asOf,null); }
    }
    public Map<String,Object> snapshot() {
        Sample sample=sample();
        Map<String,Object> result=new LinkedHashMap<>(); result.put("asOf",sample.asOf());
        result.put("dataStatus",sample.dataStatus());result.put("onlineCount",sample.uids()==null?null:sample.uids().size());
        return result;
    }
}
