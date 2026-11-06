package com.ruoyi.game.games.landlord;

/** 6003 素材/消费者编码：花色 1..4、点数 3..15；大小王为 [0,16]/[0,17]。 */
public record LandlordCard(int suit,int rank) implements Comparable<LandlordCard> {
    public LandlordCard{
        if(!((suit>=1&&suit<=4&&rank>=3&&rank<=15)||(suit==0&&(rank==16||rank==17))))throw new IllegalArgumentException("牌编码无效");
    }
    @Override public int compareTo(LandlordCard other){int result=Integer.compare(rank,other.rank);return result==0?Integer.compare(suit,other.suit):result;}
    public java.util.List<Integer> wire(){return java.util.List.of(suit,rank);}
}
