package com.ruoyi.game.games.landlord;

import java.util.*;

/** 已提交状态的 cardsInfo 白名单投影；调用方不能把内部全量状态直接序列化给玩家。 */
public final class LandlordVisibility {
    public enum Phase { WAITING,BIDDING,PLAYING,FINISHED }
    private LandlordVisibility(){ }
    public static Map<String,Object> cardsInfo(List<Long> players,Map<Long,List<LandlordCard>> hands,List<LandlordCard> bottom,long landlord,Phase phase,long viewer){
        if(players==null||players.isEmpty()||players.size()>3||players.stream().anyMatch(uid->uid==null||uid<1||uid>999999999)
                ||new HashSet<>(players).size()!=players.size())throw new IllegalArgumentException("参与者列表无效");
        if(!players.contains(viewer))throw new SecurityException("只有本局参与者可读取牌局视图");
        Objects.requireNonNull(phase);
        if(hands==null||!hands.keySet().equals(new HashSet<>(players))||bottom==null)throw new IllegalArgumentException("私有牌状态不完整");
        Set<LandlordCard> seen=new HashSet<>();
        for(long uid:players){
            var hand=hands.get(uid);
            if(hand==null||hand.size()>20||hand.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("私有手牌无效");
            for(var card:hand)if(!seen.add(card))throw new IllegalArgumentException("实体牌不能重复归属");
        }
        if(phase==Phase.WAITING){
            if(landlord!=0||!bottom.isEmpty()||!seen.isEmpty())throw new IllegalArgumentException("等待阶段不应包含发牌结果");
        }else{
            if(players.size()!=3||bottom.size()!=3||bottom.stream().anyMatch(Objects::isNull)||new HashSet<>(bottom).size()!=3)throw new IllegalArgumentException("三人牌局或底牌状态无效");
            if(phase==Phase.BIDDING){
                if(landlord!=0||hands.values().stream().anyMatch(hand->hand.size()!=17)||bottom.stream().anyMatch(seen::contains))throw new IllegalArgumentException("叫分阶段牌组无效");
            }else{
                if(!players.contains(landlord))throw new IllegalArgumentException("地主身份无效");
                for(long uid:players)if(uid!=landlord&&(hands.get(uid).size()>17||hands.get(uid).stream().anyMatch(bottom::contains)))throw new IllegalArgumentException("农民手牌不应包含底牌或超过 17 张");
            }
            if(phase==Phase.FINISHED&&hands.values().stream().noneMatch(List::isEmpty))throw new IllegalArgumentException("未出完手牌不能使用结束视图");
        }
        Map<String,Object> result=new LinkedHashMap<>();
        for(long uid:players){var hand=hands.get(uid);result.put(Long.toString(uid),phase==Phase.FINISHED||uid==viewer?wire(hand):hand.size());}
        // 标准客户端只在 currentState=2 读取底牌，叫分期保持牌背，不传真实底牌。
        if(phase==Phase.PLAYING||phase==Phase.FINISHED)result.put("dizhuCards",wire(bottom));
        return Collections.unmodifiableMap(result);
    }
    private static List<List<Integer>> wire(List<LandlordCard> cards){return cards.stream().map(LandlordCard::wire).toList();}
}
