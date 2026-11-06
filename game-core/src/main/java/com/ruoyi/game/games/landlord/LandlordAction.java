package com.ruoyi.game.games.landlord;

import java.util.*;

/** 角色身份由会话/房间入口提供，不从动作 JSON 的 uid/sender 字段读取。 */
public sealed interface LandlordAction {
    record Ready() implements LandlordAction{ }
    record Bid(int score) implements LandlordAction{
        public Bid{if(score<0||score>3)throw new IllegalArgumentException("叫分无效");}
    }
    record DoubleChoice(boolean doubled) implements LandlordAction{ }
    record Play(List<LandlordCard> cards,LandlordPatterns.Pattern pattern) implements LandlordAction{
        public Play{
            cards=List.copyOf(cards);Objects.requireNonNull(pattern);
            if(!LandlordPatterns.classifyAs(cards,pattern.type().wire()).filter(pattern::equals).isPresent())throw new IllegalArgumentException("出牌与重算牌型不一致");
        }
    }
    record Pass() implements LandlordAction{ }
    record ToggleAuto() implements LandlordAction{ }
}
