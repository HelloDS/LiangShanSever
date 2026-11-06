package com.ruoyi.game.games.landlord;

import java.security.SecureRandom;
import java.util.*;
import java.util.random.RandomGenerator;

public final class LandlordDeck {
    private static final SecureRandom ENTROPY=new SecureRandom();
    private static final List<LandlordCard> CARDS;
    static{
        List<LandlordCard> cards=new ArrayList<>(54);
        for(int suit=1;suit<=4;suit++)for(int rank=3;rank<=15;rank++)cards.add(new LandlordCard(suit,rank));
        cards.add(new LandlordCard(0,16));cards.add(new LandlordCard(0,17));CARDS=List.copyOf(cards);
    }
    private LandlordDeck(){ }
    public static List<LandlordCard> cards(){return CARDS;}
    public record Deal(List<List<LandlordCard>> hands,List<LandlordCard> bottom){
        public Deal{
            hands=hands.stream().map(List::copyOf).toList();bottom=List.copyOf(bottom);
            if(hands.size()!=3||hands.stream().anyMatch(hand->hand.size()!=17)||bottom.size()!=3)throw new IllegalArgumentException("发牌张数无效");
            Set<LandlordCard> seen=new HashSet<>(bottom);for(var hand:hands)seen.addAll(hand);
            if(seen.size()!=54||!seen.equals(new HashSet<>(CARDS)))throw new IllegalArgumentException("发牌必须完整且不重复");
        }
    }
    /** 生产入口没有指定种子或指定手牌参数；完整发牌只进入私有持久事件。 */
    public static Deal deal(){return deal(ENTROPY);}
    static Deal deal(RandomGenerator random){
        List<LandlordCard> shuffled=new ArrayList<>(CARDS);
        for(int index=shuffled.size()-1;index>0;index--)Collections.swap(shuffled,index,random.nextInt(index+1));
        return new Deal(List.of(shuffled.subList(0,17),shuffled.subList(17,34),shuffled.subList(34,51)),shuffled.subList(51,54));
    }
}
