package com.ruoyi.game.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.ruoyi.game.protocol.GameJsonCodec;

@Service
public class GameSessionService {
    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> CREATE=new DefaultRedisScript<>("""
        local old=redis.call('GET',KEYS[2])
        if old then redis.call('DEL',ARGV[4]..old) end
        redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3])
        redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[3])
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==0 then return 0 end
        local owner=redis.call('GET',KEYS[2])
        if owner and owner~=ARGV[1] then return 0 end
        redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[2])
        redis.call('EXPIRE',KEYS[1],ARGV[2])
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final GameJsonCodec codec;
    private final GuestAuthService auth;

    public GameSessionService(StringRedisTemplate redis, GameJsonCodec codec, GuestAuthService auth) {
        this.redis = redis; this.codec = codec; this.auth = auth;
    }
    public String create(GuestIdentity identity) {
        String token = SecureTokens.create();
        String hash=SecureTokens.hash(token);
        redis.execute(CREATE,List.of(key(token),ownerKey(identity.uid())),hash,codec.write(identity),Long.toString(TTL.toSeconds()),"game:session:");
        return token;
    }
    public GuestIdentity verify(String token) {
        String key = key(token);
        String value = redis.opsForValue().get(key);
        if (value == null) throw new GameFailure(-5, "会话已失效，请重新登录");
        var data = codec.read(value);
        GuestIdentity identity = new GuestIdentity(data.get("accountId").asLong(), data.get("credentialId").asString(),
                data.get("uid").asLong(), data.get("sessionVersion").asLong(), data.get("test").asBoolean());
        auth.verifySession(identity);
        Long renewed=redis.execute(RENEW,List.of(key,ownerKey(identity.uid())),SecureTokens.hash(token),Long.toString(TTL.toSeconds()));
        if(renewed==null||renewed!=1)throw new GameFailure(-5,"会话已被新的登录替换");
        return identity;
    }
    public void revoke(String token) { redis.delete(key(token)); }
    private String key(String token) { return "game:session:" + SecureTokens.hash(token); }
    private String ownerKey(long uid){return "game:session-owner:"+uid;}
}
