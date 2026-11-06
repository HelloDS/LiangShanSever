package com.ruoyi.game.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.game.protocol.GameJsonCodec;

@Service
public class GameSessionService {
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final GameJsonCodec codec;
    private final GuestAuthService auth;

    public GameSessionService(StringRedisTemplate redis, GameJsonCodec codec, GuestAuthService auth) {
        this.redis = redis; this.codec = codec; this.auth = auth;
    }
    public String create(GuestIdentity identity) {
        String token = SecureTokens.create();
        redis.opsForValue().set(key(token), codec.write(identity), TTL);
        return token;
    }
    public GuestIdentity verify(String token) {
        String key = key(token);
        String value = redis.opsForValue().get(key);
        if (value == null) throw new GameFailure(-1, "会话已失效，请重新登录");
        var data = codec.read(value);
        GuestIdentity identity = new GuestIdentity(data.get("accountId").asLong(), data.get("credentialId").asString(),
                data.get("uid").asLong(), data.get("sessionVersion").asLong(), data.get("test").asBoolean());
        auth.verifySession(identity);
        redis.expire(key, TTL);
        return identity;
    }
    public void revoke(String token) { redis.delete(key(token)); }
    private String key(String token) { return "game:session:" + SecureTokens.hash(token); }
}
