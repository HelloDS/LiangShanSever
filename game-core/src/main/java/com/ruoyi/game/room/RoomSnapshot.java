package com.ruoyi.game.room;

import java.util.Objects;

/** 仅供服务端内部使用。state 必须是深度不可变值，不能直接作为玩家快照。 */
public record RoomSnapshot<S>(String roomId,long ownerEpoch,long version,int round,String phase,S state) {
    public RoomSnapshot {
        if(roomId==null||!roomId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||ownerEpoch<=0||version<0||round<0
                ||phase==null||!phase.matches("[A-Z_]{1,32}"))throw new IllegalArgumentException("房间快照标识无效");
        Objects.requireNonNull(state);
    }
}
