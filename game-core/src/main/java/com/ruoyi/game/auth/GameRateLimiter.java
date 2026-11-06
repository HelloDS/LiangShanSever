package com.ruoyi.game.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class GameRateLimiter {
    private static final DefaultRedisScript<Long> BUCKET=new DefaultRedisScript<>("""
        local stored=redis.call('HMGET',KEYS[1],'tokens','last')
        local tokens=tonumber(stored[1]) or tonumber(ARGV[2])
        local last=tonumber(stored[2]) or tonumber(ARGV[1])
        tokens=math.min(tonumber(ARGV[2]),tokens+math.max(0,tonumber(ARGV[1])-last)*tonumber(ARGV[3]))
        local allowed=0
        if tokens>=1 then tokens=tokens-1;allowed=1 end
        redis.call('HSET',KEYS[1],'tokens',tokens,'last',ARGV[1])
        redis.call('PEXPIRE',KEYS[1],ARGV[4])
        return allowed
        """,Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    public GameRateLimiter(StringRedisTemplate redis,Clock clock){this.redis=redis;this.clock=clock;}
    public void account(long uid){allow("rate:player:"+uid,40,0.02,5000);}
    public void authentication(String address){allow("rate:auth:"+address,60,0.001,120000);}
    private void allow(String key,int capacity,double refill,int ttl){
        Long result=redis.execute(BUCKET,List.of(key),Long.toString(clock.millis()),Integer.toString(capacity),Double.toString(refill),Integer.toString(ttl));
        if(result==null||result!=1)throw new GameFailure(-4,"请求过于频繁，请稍后重试");
    }
}
