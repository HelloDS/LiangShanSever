package com.ruoyi.game.games.landlord;

import java.util.Objects;

/** uid 由已认证的房间入口填入，不从客户端动作字段读取。 */
public record LandlordPlayCommand(long uid,LandlordAction action) {
    public LandlordPlayCommand{LandlordPlayState.validUid(uid);Objects.requireNonNull(action);}
}
