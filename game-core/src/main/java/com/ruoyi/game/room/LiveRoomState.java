package com.ruoyi.game.room;

import java.util.*;
import com.ruoyi.game.games.landlord.*;

/** 私有且有版本的整桌状态。只有投影器可以向玩家输出其中的字段。 */
public record LiveRoomState(Details details,Seats seats,Round round,Map<Long,Long> totals,
        Map<Long,Long> partners,Vote vote) {
    public record Details(String code,long owner,Long club,int maxRounds,boolean aa,Map<String,Object> options) {
        public Details { options=Map.copyOf(options); }
    }
    public record Seats(Map<Integer,Long> players,Map<Long,Map<String,Object>> roles) {
        public Seats {
            players=Collections.unmodifiableMap(new TreeMap<>(players));
            var copies=new LinkedHashMap<Long,Map<String,Object>>();
            roles.forEach((uid,role)->copies.put(uid,Map.copyOf(role)));
            roles=Collections.unmodifiableMap(copies);
            if(players.size()>3 || !new HashSet<>(players.values()).equals(roles.keySet()))throw new IllegalArgumentException("房间座位与角色不一致");
        }
        public List<Long> ordered(){return List.copyOf(players.values());}
    }
    public record Round(int number,String phase,Set<Long> ready,LandlordDeck.Deal deal,
            Map<Long,Integer> bids,long currentUid,int winningBid,LandlordPlayState play,
            Map<Long,Long> deltas,boolean charged,long dueAt) {
        public Round { ready=Set.copyOf(ready);bids=Map.copyOf(bids);deltas=Map.copyOf(deltas); }
    }
    public record Vote(long initiator,Map<Long,Integer> answers,long until) {
        public Vote { answers=Map.copyOf(answers); }
    }
    public LiveRoomState {
        Objects.requireNonNull(details);Objects.requireNonNull(seats);Objects.requireNonNull(round);
        totals=Map.copyOf(totals);partners=Map.copyOf(partners);
    }
    public LiveRoomState withRound(Round next){return new LiveRoomState(details,seats,next,totals,partners,vote);}
    public LiveRoomState withVote(Vote next){return new LiveRoomState(details,seats,round,totals,partners,next);}
}
