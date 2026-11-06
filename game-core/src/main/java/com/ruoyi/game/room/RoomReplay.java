package com.ruoyi.game.room;

import java.util.function.BiFunction;

/** 仅重放已提交状态事件。解码器/重放函数必须纯计算，不能再次洗牌、扣费或发布事件。 */
public final class RoomReplay {
    private RoomReplay(){ }
    public interface Codec<S,E>{
        boolean supports(String gameId,int schemaVersion,String engineVersion);
        S state(String json);
        E event(String kind,String json);
        String json(S state);
    }
    public static <S,E> RoomSnapshot<S> recover(RoomJournal.Recovery recovery,Codec<S,E> codec,BiFunction<S,E,S> apply,
            java.util.function.Function<String,String> stateHash){
        if(!codec.supports(recovery.gameId(),recovery.schemaVersion(),recovery.engineVersion()))throw new RoomRejected("UNSUPPORTED_ENGINE");
        S state=codec.state(recovery.snapshotJson());
        if(!stateHash.apply(codec.json(state)).equals(recovery.snapshotHash()))throw new RoomRejected("SNAPSHOT_CORRUPT");
        long version=recovery.snapshotSeq();
        for(var event:recovery.events()){
            if(((Number)event.get("eventSeq")).longValue()!=++version)throw new RoomRejected("EVENT_GAP");
            state=apply.apply(state,codec.event((String)event.get("eventKind"),(String)event.get("payloadJson")));
            if(!stateHash.apply(codec.json(state)).equals(event.get("afterHash")))throw new RoomRejected("STATE_HASH_MISMATCH");
        }
        if(version!=recovery.version()||!stateHash.apply(codec.json(state)).equals(recovery.stateHash()))throw new RoomRejected("STATE_HASH_MISMATCH");
        return new RoomSnapshot<>(recovery.roomId(),recovery.ownerEpoch(),version,recovery.round(),recovery.phase(),state);
    }
}
