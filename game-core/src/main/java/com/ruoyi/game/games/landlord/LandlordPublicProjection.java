package com.ruoyi.game.games.landlord;

import java.util.*;

/** 原消费者形状的局部只读投影，不构造缺少确认计分的完整 game/GameOver 报文。 */
public final class LandlordPublicProjection {
    private LandlordPublicProjection(){ }
    private static void member(LandlordPlayState state,long viewer){if(!state.players().contains(viewer))throw new SecurityException("只有本局参与者可读取牌局投影");}
    /** players 顺序是持久座位顺序。原面板读取 players[3]，不能下发零基三元素数组。 */
    public static Map<String,Long> seats(LandlordPlayState state,long viewer){
        member(state,viewer);Map<String,Long> seats=new LinkedHashMap<>();
        for(int index=0;index<state.players().size();index++)seats.put(Integer.toString(index+1),state.players().get(index));
        return Collections.unmodifiableMap(seats);
    }
    /** 无活动目标必须为 null；空对象会让旧面板误入压制/提示分支。 */
    public static Map<String,List<List<Integer>>> outCards(LandlordPlayState state,long viewer){
        member(state,viewer);var target=state.target();
        return target==null?null:Map.of(Long.toString(target.uid()),wire(target.cards()));
    }
    /** GameOver 展示读取 game.cards，只有真实出完后才允许提供全体剩余手牌。 */
    public static Map<String,List<List<Integer>>> finishedCards(LandlordPlayState state,long viewer){
        member(state,viewer);if(!state.finished())throw new IllegalArgumentException("牌局未真实结束，不能公开其他人的手牌");
        Map<String,List<List<Integer>>> cards=new LinkedHashMap<>();
        for(long uid:state.players())cards.put(Long.toString(uid),wire(state.hands().get(uid)));
        return Collections.unmodifiableMap(cards);
    }
    private static List<List<Integer>> wire(List<LandlordCard> cards){return cards.stream().map(LandlordCard::wire).toList();}
}
