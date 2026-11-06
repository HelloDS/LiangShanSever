package com.ruoyi.game.games.landlord;

import java.util.*;

/** 私有确定性事件。不是直接广播给所有玩家的完整状态。 */
public sealed interface LandlordPlayEvent {
    record Played(long uid,List<LandlordCard> cards,int type) implements LandlordPlayEvent {
        public Played {
            LandlordPlayState.validUid(uid);cards=List.copyOf(cards);
            if(LandlordPatterns.classifyAs(cards,type).isEmpty())throw new IllegalArgumentException("事件出牌类型无效");
        }
    }
    record Passed(long uid) implements LandlordPlayEvent{public Passed{LandlordPlayState.validUid(uid);}}
    record AutoChanged(long uid,boolean enabled) implements LandlordPlayEvent{public AutoChanged{LandlordPlayState.validUid(uid);}}
}
