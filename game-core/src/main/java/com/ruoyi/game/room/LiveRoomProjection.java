package com.ruoyi.game.room;

import java.util.*;
import com.ruoyi.game.games.landlord.*;

/** 原 Creator 消费者的白名单视图，圈大厅永远只拿到座位与房间元数据。 */
public final class LiveRoomProjection {
    private LiveRoomProjection(){ }
    public static Map<String,Object> room(LiveRoomState state) {
        var details=state.details();
        var seats=new TreeMap<String,Long>();
        state.seats().players().forEach((seat,uid)->seats.put(Integer.toString(seat+1),uid));
        var result=new LinkedHashMap<String,Object>();
        result.put("id",details.code());result.put("owner",details.owner());
        result.put("players",seats);result.put("max_player",3);result.put("room_type",0);
        result.put("club_id",details.club());result.put("currentRound",state.round().number());
        result.put("totalRound",details.maxRounds());result.put("data",Map.of("game_id","6003","cd",details.options()));
        return result;
    }
    public static Map<String,Object> game(LiveRoomState state,long viewer) {
        if(!state.seats().roles().containsKey(viewer))throw new SecurityException("仅参与者可读取牌局");
        var round=state.round();var play=round.play();
        boolean finished=play!=null && play.finished();
        var result=new LinkedHashMap<String,Object>();
        result.put("id",state.details().code());
        result.put("ready",flags(round.ready()));
        result.put("currentState",switch(round.phase()){case "BIDDING"->1;case "PLAYING"->2;default->0;});
        result.put("currentCount",round.number()==0?1:finished?round.number()+1:round.number());
        result.put("maxCount",state.details().maxRounds());result.put("currentUid",round.currentUid()==0?null:round.currentUid());
        result.put("perUid",play==null || play.target()==null?null:play.target().uid());
        result.put("dizhuUid",play==null?null:play.landlord());result.put("score",round.bids());
        result.put("money",state.totals());result.put("currentMoney",round.deltas());
        result.put("difen",Math.max(1,round.winningBid()));
        result.put("beishu",play==null?1:1L<<(play.bombs()+play.rockets()));
        result.put("maxFan",64);result.put("isSpring",finished && new LandlordScoring(new LandlordScoring.Policy("local-6003-v1",1,64)).score(play,round.winningBid()).spring()?1:0);
        result.put("autoList",play==null?List.of():play.auto().stream().sorted().toList());
        result.put("outCards",play==null?null:LandlordPublicProjection.outCards(play,viewer));
        result.put("cardsType",play==null || play.target()==null?0:play.target().pattern().type().wire());
        result.put("opCardType",result.get("cardsType"));
        result.put("cardsInfo",cards(state,viewer));
        if(finished)result.put("cards",LandlordPublicProjection.finishedCards(play,viewer));
        return result;
    }
    public static Map<String,Object> cards(LiveRoomState state,long viewer) {
        var players=state.seats().ordered();var round=state.round();
        if(round.play()!=null) {
            var play=round.play();
            return LandlordVisibility.cardsInfo(players,play.hands(),play.bottom(),play.landlord(),play.finished()?LandlordVisibility.Phase.FINISHED:LandlordVisibility.Phase.PLAYING,viewer);
        }
        var hands=new LinkedHashMap<Long,List<LandlordCard>>();
        for(int index=0;index<players.size();index++)hands.put(players.get(index),round.deal()==null?List.of():round.deal().hands().get(index));
        return LandlordVisibility.cardsInfo(players,hands,round.deal()==null?List.of():round.deal().bottom(),0,round.deal()==null?LandlordVisibility.Phase.WAITING:LandlordVisibility.Phase.BIDDING,viewer);
    }
    private static Map<String,Integer> flags(Set<Long> values){var result=new LinkedHashMap<String,Integer>();for(long uid:values)result.put(Long.toString(uid),1);return result;}
}
