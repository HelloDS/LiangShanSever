package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.room.*;

/** 6003 出牌阶段候选；无网络注册、扣费、积分、超时策略或完整结算。 */
public final class LandlordPlayEngine implements RoomMailboxes.Engine<LandlordPlayState,LandlordPlayCommand,LandlordPlayEvent> {
    public static final String ENGINE_VERSION="landlord-play-v1";
    public record Change(LandlordPlayState state,LandlordPlayEvent event){ }
    public record Outcome(long winner,boolean landlordWon,Map<Long,Integer> playCounts,int bombs,int rockets){
        public Outcome{playCounts=Map.copyOf(playCounts);}
    }
    /** 仅使用已生成、将被保存的发牌结果；恢复不能调用本方法重新洗牌。 */
    public static LandlordPlayState start(List<Long> players,LandlordDeck.Deal deal,long landlord){
        if(players==null||players.size()!=3||!players.contains(landlord))throw new IllegalArgumentException("本局参与者或地主无效");
        Map<Long,List<LandlordCard>> hands=new LinkedHashMap<>();Map<Long,Integer> plays=new LinkedHashMap<>();
        for(int seat=0;seat<3;seat++){
            long uid=players.get(seat);List<LandlordCard> hand=new ArrayList<>(deal.hands().get(seat));
            if(uid==landlord)hand.addAll(deal.bottom());hands.put(uid,List.copyOf(hand));plays.put(uid,0);
        }
        return new LandlordPlayState(players,hands,deal.bottom(),landlord,landlord,null,0,plays,List.of(),0,Set.of(),0,0);
    }
    @Override public RoomTransition<LandlordPlayState,LandlordPlayEvent> apply(RoomSnapshot<LandlordPlayState> committed,LandlordPlayCommand command){
        if(committed.round()<1||!"PLAYING".equals(committed.phase()))throw new RoomRejected("WRONG_PHASE");
        var change=change(committed.state(),command);
        return new RoomTransition<>(committed.round(),change.state().finished()?"ROUND_FINISHED":"PLAYING",change.state(),change.event());
    }
    public Change change(LandlordPlayState before,LandlordPlayCommand command){
        long uid=command.uid();
        if(!before.players().contains(uid))throw new RoomRejected("NOT_A_PARTICIPANT");
        if(before.finished())throw new RoomRejected("ROUND_FINISHED");
        if(command.action() instanceof LandlordAction.ToggleAuto){
            Set<Long> auto=new HashSet<>(before.auto());boolean enabled=auto.add(uid);if(!enabled)auto.remove(uid);
            return new Change(copy(before,before.hands(),before.currentUid(),before.target(),before.passes(),before.plays(),before.discarded(),0,auto,before.bombs(),before.rockets()),new LandlordPlayEvent.AutoChanged(uid,enabled));
        }
        if(!(command.action() instanceof LandlordAction.Play)&&!(command.action() instanceof LandlordAction.Pass))throw new RoomRejected("WRONG_PHASE");
        if(before.currentUid()!=uid)throw new RoomRejected("NOT_YOUR_TURN");
        if(command.action() instanceof LandlordAction.Play play){
            if(before.target()!=null&&!LandlordPatterns.beats(play.pattern(),before.target().pattern()))throw new RoomRejected("PLAY_DOES_NOT_BEAT_TARGET");
            List<LandlordCard> remaining;
            try{remaining=LandlordPatterns.removeOwned(before.hands().get(uid),play.cards());}
            catch(IllegalArgumentException failure){throw new RoomRejected("CARDS_NOT_OWNED");}
            Map<Long,List<LandlordCard>> hands=new LinkedHashMap<>(before.hands());hands.put(uid,remaining);
            Map<Long,Integer> plays=new LinkedHashMap<>(before.plays());plays.put(uid,Math.addExact(plays.get(uid),1));
            List<LandlordCard> discarded=new ArrayList<>(before.discarded());discarded.addAll(play.cards());
            long winner=remaining.isEmpty()?uid:0,next=winner==0?before.next(uid):0;
            int bombs=before.bombs()+(play.pattern().type()==LandlordPatterns.Type.BOMB?1:0),rockets=before.rockets()+(play.pattern().type()==LandlordPatterns.Type.ROCKET?1:0);
            var state=copy(before,hands,next,new LandlordPlayState.Target(uid,play.cards(),play.pattern()),0,plays,discarded,winner,before.auto(),bombs,rockets);
            return new Change(state,new LandlordPlayEvent.Played(uid,play.cards(),play.pattern().type().wire()));
        }
        if(before.target()==null)throw new RoomRejected("LEADER_CANNOT_PASS");
        int passes=before.passes()+1;
        return new Change(copy(before,before.hands(),before.next(uid),passes==2?null:before.target(),passes==2?0:passes,before.plays(),before.discarded(),0,before.auto(),before.bombs(),before.rockets()),new LandlordPlayEvent.Passed(uid));
    }
    /** 已持久事件的纯重放；不会洗牌、请求随机数、记账或发布 outbox。 */
    public LandlordPlayState replay(LandlordPlayState state,LandlordPlayEvent event){
        LandlordPlayCommand command;
        if(event instanceof LandlordPlayEvent.Played played){
            var pattern=LandlordPatterns.classifyAs(played.cards(),played.type()).orElseThrow();
            command=new LandlordPlayCommand(played.uid(),new LandlordAction.Play(played.cards(),pattern));
        }else if(event instanceof LandlordPlayEvent.Passed passed)command=new LandlordPlayCommand(passed.uid(),new LandlordAction.Pass());
        else if(event instanceof LandlordPlayEvent.AutoChanged auto){
            if(state.auto().contains(auto.uid())==auto.enabled())throw new IllegalArgumentException("托管事件没有状态变化");
            command=new LandlordPlayCommand(auto.uid(),new LandlordAction.ToggleAuto());
        }else throw new IllegalArgumentException("未知出牌事件");
        var change=change(state,command);
        if(!change.event().equals(event))throw new IllegalArgumentException("事件不能确定性重放");
        return change.state();
    }
    public Outcome outcome(LandlordPlayState state){
        if(!state.finished())throw new RoomRejected("ROUND_NOT_FINISHED");
        return new Outcome(state.winner(),state.winner()==state.landlord(),state.plays(),state.bombs(),state.rockets());
    }
    private LandlordPlayState copy(LandlordPlayState before,Map<Long,List<LandlordCard>> hands,long current,LandlordPlayState.Target target,int passes,
            Map<Long,Integer> plays,List<LandlordCard> discarded,long winner,Set<Long> auto,int bombs,int rockets){
        return new LandlordPlayState(before.players(),hands,before.bottom(),before.landlord(),current,target,passes,plays,discarded,winner,auto,bombs,rockets);
    }
}
