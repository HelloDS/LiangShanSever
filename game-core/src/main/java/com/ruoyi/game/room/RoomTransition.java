package com.ruoyi.game.room;

import java.util.Objects;

/** 引擎只生成候选状态及确定性事件，不进行 I/O。 */
public record RoomTransition<S,E>(int round,String phase,S state,E event) {
    public RoomTransition {Objects.requireNonNull(state);Objects.requireNonNull(event);}
}
