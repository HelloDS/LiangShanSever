package com.ruoyi.game.economy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.PlayerOperationMapper;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 玩家确认一次的业务意图，结果与资金事务同时提交，跨连接可重放。 */
@Service
public class PlayerOperations {
    private final PlayerOperationMapper mapper;
    private final GameJsonCodec codec;
    private final Clock clock;

    public PlayerOperations(PlayerOperationMapper mapper,GameJsonCodec codec,Clock clock) {
        this.mapper=mapper;
        this.codec=codec;
        this.clock=clock;
    }

    @Transactional(timeout=5)
    public Map<String,Object> execute(long uid,String id,Map<String,Object> intent,Supplier<Map<String,Object>> action) {
        if(id==null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new GameFailure(-4,"缺少操作编号");
        }
        String hash=hash(codec.canonicalObject(codec.write(intent)));
        mapper.begin(uid,id,hash,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        var row=mapper.lock(uid,id);
        if(!hash.equals(row.get("requestHash")))throw new GameFailure(-4,"操作编号已用于其他请求");
        if(row.get("result") instanceof String json) {
            return codec.object(json);
        }
        Map<String,Object> result=action.get();
        if(mapper.complete(uid,id,codec.write(result))!=1)throw new IllegalStateException("玩家操作结果未保存");
        return result;
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
