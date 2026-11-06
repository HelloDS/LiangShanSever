package com.ruoyi.game.games.landlord;

import java.util.*;

/**
 * 6003 客户端事实的独立重算分类器。客户端飞机限制保留为兼容事实，未将其冒称已确认的服务端完整规则。
 * 不信任 opCardType，也不修改输入牌列；经济、轮次、结算与规则发布由上层另行验证。
 */
public final class LandlordPatterns {
    public enum Type {
        SINGLE(1),PAIR(2),TRIPLE(3),TRIPLE_SINGLE(4),TRIPLE_PAIR(5),STRAIGHT(6),PAIR_STRAIGHT(7),
        AIRPLANE(8),AIRPLANE_SINGLES(9),AIRPLANE_PAIRS(10),FOUR_TWO(11),BOMB(12),ROCKET(13);
        private final int wire;
        Type(int wire){this.wire=wire;}
        public int wire(){return wire;}
        public static Type of(int wire){for(Type type:values())if(type.wire==wire)return type;throw new IllegalArgumentException("未知牌型");}
    }
    public record Pattern(Type type,int size,int mainRank){public Pattern{if(type==null||size<1||size>20||mainRank<3||mainRank>17)throw new IllegalArgumentException("牌型元数据无效");}}
    private LandlordPatterns(){ }
    public static Optional<Pattern> classify(List<LandlordCard> cards){
        var counts=counts(cards);
        for(Type type:Type.values()){var pattern=classify(type,cards.size(),counts);if(pattern.isPresent())return pattern;}
        return Optional.empty();
    }
    public static Optional<Pattern> classifyAs(List<LandlordCard> cards,int claimed){var counts=counts(cards);return classify(Type.of(claimed),cards.size(),counts);}
    private static SortedMap<Integer,Integer> counts(List<LandlordCard> cards){
        if(cards==null||cards.isEmpty()||cards.size()>20||cards.stream().anyMatch(Objects::isNull)||new HashSet<>(cards).size()!=cards.size())throw new IllegalArgumentException("出牌必须为 1 至 20 张不重复的有效牌");
        SortedMap<Integer,Integer> counts=new TreeMap<>();for(var card:cards)counts.merge(card.rank(),1,Integer::sum);return counts;
    }
    private static Optional<Pattern> classify(Type type,int size,SortedMap<Integer,Integer> counts){
        int high=counts.lastKey(),low=counts.firstKey(),main=high;boolean valid;
        switch(type){
            case SINGLE->valid=size==1;
            case PAIR->valid=size==2&&counts.size()==1&&high<=15;
            case TRIPLE->valid=size==3&&counts.size()==1;
            case TRIPLE_SINGLE->{valid=size==4&&exactGroups(counts,3,1);main=rankWith(counts,3);}
            case TRIPLE_PAIR->{valid=size==5&&exactGroups(counts,3,2);main=rankWith(counts,3);}
            case STRAIGHT->{valid=size>=5&&high<15&&sequence(counts,1);main=low;}
            case PAIR_STRAIGHT->{valid=size>=6&&size%2==0&&high<15&&sequence(counts,2);main=low;}
            // 原客户端 getFeiji_0 明确带偶数总张数条件；三/五组裸飞机待业务确认，不暗中放宽。
            case AIRPLANE->{valid=size>=6&&size%2==0&&high<15&&sequence(counts,3);main=low;}
            case AIRPLANE_SINGLES->{main=singlePlaneMain(counts,size);valid=main>0;}
            case AIRPLANE_PAIRS->{main=pairedPlaneMain(counts,size);valid=main>0;}
            case FOUR_TWO->{main=rankWith(counts,4);valid=size==6&&main>0;}
            case BOMB->valid=size==4&&counts.size()==1;
            case ROCKET->valid=size==2&&counts.size()==2&&counts.containsKey(16)&&counts.containsKey(17);
            default->throw new IllegalStateException("牌型分支不完整");
        }
        return valid?Optional.of(new Pattern(type,size,main)):Optional.empty();
    }
    private static int singlePlaneMain(SortedMap<Integer,Integer> counts,int size){
        if(size<8||size%4!=0)return 0;int required=size/4,quads=0;List<Integer> triples=new ArrayList<>();
        for(var entry:counts.entrySet()){
            if(triples.size()==required)break;
            if(entry.getKey()>=15)return 0;
            if(entry.getValue()>=3)triples.add(entry.getKey());
            if(entry.getValue()==4)quads++;
        }
        return triples.size()==required&&quads!=required&&adjacent(triples)?triples.getFirst():0;
    }
    private static int pairedPlaneMain(SortedMap<Integer,Integer> counts,int size){
        if(size<10||size%5!=0||counts.lastKey()>=15)return 0;
        int required=size/5,pairs=0;List<Integer> triples=new ArrayList<>();
        for(var entry:counts.entrySet()){
            if(entry.getValue()==3)triples.add(entry.getKey());
            if(entry.getValue()==2)pairs++;
            if(entry.getValue()==4)pairs+=2;
        }
        return triples.size()==required&&pairs==required&&adjacent(triples)?triples.getFirst():0;
    }
    private static boolean exactGroups(Map<Integer,Integer> counts,int first,int second){return counts.size()==2&&counts.containsValue(first)&&counts.containsValue(second);}
    private static int rankWith(Map<Integer,Integer> counts,int count){return counts.entrySet().stream().filter(entry->entry.getValue()==count).mapToInt(Map.Entry::getKey).max().orElse(0);}
    private static boolean sequence(SortedMap<Integer,Integer> counts,int repeat){return counts.values().stream().allMatch(value->value==repeat)&&adjacent(new ArrayList<>(counts.keySet()));}
    private static boolean adjacent(List<Integer> ranks){for(int index=1;index<ranks.size();index++)if(ranks.get(index)!=ranks.get(index-1)+1)return false;return !ranks.isEmpty();}
    public static boolean beats(Pattern current,Pattern previous){
        if(previous.type()==Type.ROCKET)return false;
        if(current.type()==Type.ROCKET)return true;
        if(current.type()!=previous.type())return current.type()==Type.BOMB;
        return current.size()==previous.size()&&current.mainRank()>previous.mainRank();
    }
    /** 自有牌校验与移除，返回新的不可变手牌；客户端指定一个合法牌型也不能伪造持有权。 */
    public static List<LandlordCard> removeOwned(List<LandlordCard> hand,List<LandlordCard> played){
        counts(played);
        if(hand==null||hand.size()>20||hand.stream().anyMatch(Objects::isNull)||new HashSet<>(hand).size()!=hand.size()||!new HashSet<>(hand).containsAll(played))throw new IllegalArgumentException("出牌不属于当前手牌");
        Set<LandlordCard> removed=new HashSet<>(played);return hand.stream().filter(card->!removed.contains(card)).toList();
    }
}
