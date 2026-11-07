package com.ruoyi.game.games.landlord;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordScoringTest {
    private static final List<Long> PLAYERS=List.of(100001L,100002L,100003L);
    private final LandlordPlayEngine engine=new LandlordPlayEngine();
    private LandlordPlayState start(int seed){return LandlordPlayEngine.start(PLAYERS,LandlordDeck.deal(new Random(seed)),PLAYERS.getFirst());}
    private LandlordPlayState play(LandlordPlayState state,List<LandlordCard> cards){return engine.change(state,new LandlordPlayCommand(state.currentUid(),new LandlordAction.Play(cards,LandlordPatterns.classify(cards).orElseThrow()))).state();}
    private LandlordPlayState pass(LandlordPlayState state){return engine.change(state,new LandlordPlayCommand(state.currentUid(),new LandlordAction.Pass())).state();}
    private LandlordPlayState landlordFinishes(LandlordPlayState state){
        int moves=0;while(!state.finished()){assertTrue(++moves<=20);assertEquals(state.landlord(),state.currentUid());state=play(state,List.of(state.hands().get(state.landlord()).getFirst()));if(!state.finished()){state=pass(state);state=pass(state);}}return state;
    }
    @Test void naturallyFinishedLandlordSpringScoresRealNonzeroDeltas(){
        var finished=landlordFinishes(start(31));var scoring=new LandlordScoring(new LandlordScoring.Policy("local-v1",1,64));var result=scoring.score(finished,3);
        assertTrue(result.spring());assertEquals(2,result.multiplier());assertEquals(6,result.unitScore());assertEquals(Map.of(100001L,12L,100002L,-6L,100003L,-6L),result.deltas());assertFalse(result.capped());
        assertEquals(0,result.deltas().values().stream().mapToLong(Long::longValue).sum());assertThrows(UnsupportedOperationException.class,()->result.deltas().clear());
    }
    @Test void naturallyFinishedFarmerReverseSpringUsesBothFarmersOnTheWinningSide(){
        var state=start(44);var first=state.hands().get(state.landlord()).stream().min(Comparator.comparingInt(LandlordCard::rank)).orElseThrow();state=play(state,List.of(first));long farmer=state.currentUid();
        int moves=0;while(!state.finished()){
            assertTrue(++moves<=17);assertEquals(farmer,state.currentUid());var target=state.target();var card=state.hands().get(farmer).stream().filter(value->target==null||value.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank)).orElseThrow();
            state=play(state,List.of(card));if(!state.finished()){state=pass(state);state=pass(state);}
        }
        var result=new LandlordScoring(new LandlordScoring.Policy("local-v1",1,64)).score(state,1);
        assertTrue(result.spring());assertEquals(1,state.plays().get(state.landlord()));assertEquals(Map.of(100001L,-4L,100002L,2L,100003L,2L),result.deltas());
    }
    @Test void bombRocketAndSpringFactsAllMultiplyBeforeTheSingleSideCap(){
        LandlordPlayState state=null;List<LandlordCard> bomb=null;
        for(int seed=0;seed<10000&&state==null;seed++){
            var candidate=start(seed);var hand=candidate.hands().get(candidate.landlord());boolean rocket=hand.contains(new LandlordCard(0,16))&&hand.contains(new LandlordCard(0,17));
            if(rocket)for(int rank=3;rank<=15;rank++){int value=rank;var cards=hand.stream().filter(card->card.rank()==value).toList();if(cards.size()==4){state=candidate;bomb=cards;break;}}
        }
        assertNotNull(state);state=play(state,bomb);state=pass(state);state=pass(state);state=play(state,List.of(new LandlordCard(0,16),new LandlordCard(0,17)));state=pass(state);state=pass(state);state=landlordFinishes(state);
        var result=new LandlordScoring(new LandlordScoring.Policy("local-v1",1,10)).score(state,2);
        assertEquals(1,state.bombs());assertEquals(1,state.rockets());assertEquals(8,result.multiplier());assertEquals(10,result.unitScore());assertTrue(result.capped());assertEquals(20L,result.deltas().get(state.landlord()).longValue());
    }
    @Test void unfinishedRoundsAndMissingBidsCannotBeReportedAsSettled(){
        var initial=start(9);var scoring=new LandlordScoring(new LandlordScoring.Policy("local-v1",1,64));assertThrows(IllegalArgumentException.class,()->scoring.score(initial,1));var finished=landlordFinishes(initial);
        for(int invalid:new int[]{-1,0,4})assertThrows(IllegalArgumentException.class,()->scoring.score(finished,invalid));
    }
    @Test void ordinaryNaturalRoundDoesNotReceiveAnInventedSpringMultiplier(){
        boolean observed=false;
        for(int seed=0;seed<400&&!observed;seed++){
            var state=start(seed);int moves=0;
            while(!state.finished()){
                assertTrue(++moves<=200);
                var target=state.target();long uid=state.currentUid();var next=state.hands().get(uid).stream().filter(card->target==null||card.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
                state=next.isPresent()?play(state,List.of(next.get())):pass(state);
            }
            var result=new LandlordScoring(new LandlordScoring.Policy("local-v1",1,64)).score(state,2);
            if(!result.spring()){observed=true;assertEquals(1,result.multiplier());assertEquals(2,result.unitScore());assertEquals(0,result.deltas().values().stream().mapToLong(Long::longValue).sum());}
        }
        assertTrue(observed,"应自然生成至少一个非春天终局");
    }
    @Test void configurationChangesProduceDistinctStableRuleVersions(){
        var original=new LandlordScoring.Policy("local-v1",1,64);assertEquals(original.ruleVersion(),new LandlordScoring.Policy("local-v1",1,64).ruleVersion());assertNotEquals(original.ruleVersion(),new LandlordScoring.Policy("local-v1",2,64).ruleVersion());assertNotEquals(original.ruleVersion(),new LandlordScoring.Policy("local-v1",1,128).ruleVersion());
        assertThrows(IllegalArgumentException.class,()->new LandlordScoring.Policy("local-v1",0,64));assertThrows(IllegalArgumentException.class,()->new LandlordScoring.Policy("local-v1",1,0));
    }
}
