package com.ruoyi.game.games.landlord;

import java.util.*;

/** 私有的已发牌出牌阶段，不含价格、积分或结算成功标记。仅内部恢复与纯计算使用。 */
public record LandlordPlayState(List<Long> players,Map<Long,List<LandlordCard>> hands,List<LandlordCard> bottom,
        long landlord,long currentUid,Target target,int passes,Map<Long,Integer> plays,List<LandlordCard> discarded,
        long winner,Set<Long> auto,int bombs,int rockets) {
    public record Target(long uid,List<LandlordCard> cards,LandlordPatterns.Pattern pattern) {
        public Target {
            validUid(uid);cards=List.copyOf(cards);Objects.requireNonNull(pattern);
            if(LandlordPatterns.classifyAs(cards,pattern.type().wire()).filter(pattern::equals).isEmpty())
                throw new IllegalArgumentException("目标牌型与实体牌不一致");
        }
    }
    public LandlordPlayState {
        players=List.copyOf(players);
        if(players.size()!=3||new HashSet<>(players).size()!=3)throw new IllegalArgumentException("出牌阶段必须有三位不同参与者");
        players.forEach(LandlordPlayState::validUid);
        if(!players.contains(landlord))throw new IllegalArgumentException("地主不在本局");
        Map<Long,List<LandlordCard>> privateHands=new LinkedHashMap<>();
        hands.forEach((uid,cards)->privateHands.put(uid,List.copyOf(cards)));hands=Collections.unmodifiableMap(privateHands);
        plays=Map.copyOf(plays);bottom=List.copyOf(bottom);discarded=List.copyOf(discarded);auto=Set.copyOf(auto);
        Set<Long> members=new HashSet<>(players);
        if(!hands.keySet().equals(members)||!plays.keySet().equals(members)||!members.containsAll(auto))
            throw new IllegalArgumentException("出牌状态的参与者不一致");
        if(bottom.size()!=3||new HashSet<>(bottom).size()!=3||passes<0||passes>1||target==null&&passes!=0)
            throw new IllegalArgumentException("底牌或轮次状态无效");
        Set<LandlordCard> seen=new HashSet<>();
        for(long uid:players){
            var hand=hands.get(uid);
            if(hand.size()>(uid==landlord?20:17)||uid!=landlord&&hand.stream().anyMatch(bottom::contains))
                throw new IllegalArgumentException("手牌归属或数量无效");
            int removed=(uid==landlord?20:17)-hand.size(),count=plays.get(uid);
            if(count<0||count>removed||removed>20L*count)throw new IllegalArgumentException("个人出牌次数与剩余手牌不一致");
            for(var card:hand)if(!seen.add(card))throw new IllegalArgumentException("实体牌重复归属");
        }
        for(var card:discarded)if(!seen.add(card))throw new IllegalArgumentException("实体牌重复出牌");
        if(!seen.equals(new HashSet<>(LandlordDeck.cards())))throw new IllegalArgumentException("手牌与已出牌必须守恒为完整 54 张");
        int playCount=0;
        for(var count:plays.values()){
            if(count<0||count>54)throw new IllegalArgumentException("出牌次数无效");
            playCount=Math.addExact(playCount,count);
        }
        if(playCount>discarded.size()||bombs<0||bombs>13||rockets<0||rockets>1||bombs*4+rockets*2>discarded.size()||bombs+rockets>playCount)
            throw new IllegalArgumentException("出牌事实计数无效");
        if(target!=null&&(!members.contains(target.uid())||plays.get(target.uid())<1||!new HashSet<>(discarded).containsAll(target.cards())))
            throw new IllegalArgumentException("当前目标牌没有持久出牌来源");
        long empty=hands.values().stream().filter(List::isEmpty).count();
        if(winner==0){
            if(empty!=0||!members.contains(currentUid))throw new IllegalArgumentException("进行阶段不能提前出现空手或缺少轮次");
            if(target!=null){
                long expected=players.get((players.indexOf(target.uid())+1+passes)%3);
                if(currentUid!=expected)throw new IllegalArgumentException("当前玩家与过牌次数不一致");
            }
        }else if(!members.contains(winner)||currentUid!=0||empty!=1||!hands.get(winner).isEmpty()
                ||target==null||target.uid()!=winner||passes!=0)throw new IllegalArgumentException("结束阶段必须来自真实出完手牌");
    }
    static void validUid(long uid){if(uid<1||uid>999999999)throw new IllegalArgumentException("参与者编号无效");}
    public boolean finished(){return winner!=0;}
    public long next(long uid){int seat=players.indexOf(uid);if(seat<0)throw new IllegalArgumentException("参与者不在本局");return players.get((seat+1)%3);}
}
