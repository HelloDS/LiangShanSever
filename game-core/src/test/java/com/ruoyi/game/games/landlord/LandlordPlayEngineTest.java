package com.ruoyi.game.games.landlord;

import java.util.*;
import java.util.function.Predicate;
import com.ruoyi.game.room.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordPlayEngineTest {
    private static final List<Long> PLAYERS=List.of(100001L,100002L,100003L);
    private final LandlordPlayEngine engine=new LandlordPlayEngine();
    private LandlordPlayState start(int seed){return LandlordPlayEngine.start(PLAYERS,LandlordDeck.deal(new Random(seed)),PLAYERS.getFirst());}
    private LandlordAction.Play play(List<LandlordCard> cards){return new LandlordAction.Play(cards,LandlordPatterns.classify(cards).orElseThrow());}
    private LandlordPlayEngine.Change action(LandlordPlayState state,long uid,LandlordAction action){return engine.change(state,new LandlordPlayCommand(uid,action));}
    private LandlordPlayState find(Predicate<LandlordPlayState> condition){for(int seed=0;seed<10000;seed++){var state=start(seed);if(condition.test(state))return state;}throw new AssertionError("没有找到有限测试发牌");}
    private List<LandlordCard> rank(LandlordPlayState state,long uid,int rank){return state.hands().get(uid).stream().filter(card->card.rank()==rank).toList();}
    private List<LandlordCard> bomb(LandlordPlayState state){for(int rank=3;rank<=15;rank++){var cards=rank(state,100001,rank);if(cards.size()==4)return cards;}return List.of();}

    @Test void initialStateContainsWholeDeckAndCannotBeMutated(){
        var state=start(41);assertEquals(20,state.hands().get(100001L).size());assertEquals(17,state.hands().get(100002L).size());
        assertEquals(100001,state.currentUid());assertNull(state.target());assertEquals(0,state.discarded().size());
        assertThrows(UnsupportedOperationException.class,()->state.hands().get(100001L).clear());assertThrows(UnsupportedOperationException.class,()->state.plays().clear());
        assertThrows(RoomRejected.class,()->engine.outcome(state));
    }
    @Test void twoPassesClearTargetAndReturnLeadWithoutChangingOtherHands(){
        var before=start(11);var first=action(before,100001,play(List.of(before.hands().get(100001L).getFirst()))).state();
        assertEquals(100002,first.currentUid());assertEquals(1,first.plays().get(100001L));
        var one=action(first,100002,new LandlordAction.Pass()).state();assertEquals(1,one.passes());assertEquals(100003,one.currentUid());
        var two=action(one,100003,new LandlordAction.Pass()).state();assertNull(two.target());assertEquals(0,two.passes());assertEquals(100001,two.currentUid());
        assertEquals(before.hands().get(100002L),two.hands().get(100002L));assertEquals(20,before.hands().get(100001L).size());assertEquals(19,two.hands().get(100001L).size());
        assertEquals("LEADER_CANNOT_PASS",assertThrows(RoomRejected.class,()->action(two,100001,new LandlordAction.Pass())).code());
    }
    @Test void actorTurnOwnershipAndPhaseAreValidatedBeforeAnyStateChange(){
        var before=start(29);var owned=play(List.of(before.hands().get(100001L).getFirst()));
        assertEquals("NOT_A_PARTICIPANT",assertThrows(RoomRejected.class,()->action(before,999999,owned)).code());
        assertEquals("NOT_YOUR_TURN",assertThrows(RoomRejected.class,()->action(before,100002,owned)).code());
        assertEquals("CARDS_NOT_OWNED",assertThrows(RoomRejected.class,()->action(before,100001,play(List.of(before.hands().get(100002L).getFirst())))).code());
        assertEquals("LEADER_CANNOT_PASS",assertThrows(RoomRejected.class,()->action(before,100001,new LandlordAction.Pass())).code());
        assertEquals("WRONG_PHASE",assertThrows(RoomRejected.class,()->action(before,100001,new LandlordAction.Bid(3))).code());
        var snapshot=new RoomSnapshot<>(UUID.randomUUID().toString(),1,0,1,"WAITING",before);
        assertEquals("WRONG_PHASE",assertThrows(RoomRejected.class,()->engine.apply(snapshot,new LandlordPlayCommand(100001,owned))).code());
        assertEquals(start(29),before);
    }
    @Test void nonMatchingTypeCannotBeatAnExistingPair(){
        var before=find(state->state.hands().get(100001L).stream().anyMatch(card->rank(state,100001,card.rank()).size()>=2));
        var pair=before.hands().get(100001L).stream().map(card->rank(before,100001,card.rank())).filter(cards->cards.size()>=2).findFirst().orElseThrow().subList(0,2);
        var after=action(before,100001,play(pair)).state();var single=play(List.of(after.hands().get(100002L).getFirst()));
        assertEquals("PLAY_DOES_NOT_BEAT_TARGET",assertThrows(RoomRejected.class,()->action(after,100002,single)).code());
        assertEquals(18,after.hands().get(100001L).size());assertEquals(17,after.hands().get(100002L).size());
    }
    @Test void bombAndRocketAreRecordedAsFactsWithoutGeneratingScores(){
        var before=find(state->!bomb(state).isEmpty()&&rank(state,100001,16).size()==1&&rank(state,100001,17).size()==1);
        var after=action(before,100001,play(bomb(before))).state();
        after=action(after,100002,new LandlordAction.Pass()).state();after=action(after,100003,new LandlordAction.Pass()).state();
        after=action(after,100001,play(List.of(rank(after,100001,16).getFirst(),rank(after,100001,17).getFirst()))).state();
        assertEquals(1,after.bombs());assertEquals(1,after.rockets());assertEquals(6,after.discarded().size());assertFalse(after.finished());
    }
    @Test void autoFlagMayChangeOutOfTurnButDoesNotPlayCardsOrChangeTurn(){
        var before=start(67);var first=action(before,100002,new LandlordAction.ToggleAuto());
        assertEquals(Set.of(100002L),first.state().auto());assertEquals(before.hands(),first.state().hands());assertEquals(before.currentUid(),first.state().currentUid());
        assertEquals(new LandlordPlayEvent.AutoChanged(100002,true),first.event());
        assertEquals(first.state(),engine.replay(before,first.event()));
        assertEquals(before,action(first.state(),100002,new LandlordAction.ToggleAuto()).state());
        assertThrows(IllegalArgumentException.class,()->engine.replay(first.state(),first.event()));
    }
    @Test void fourHundredNaturallyPlayedRoundsConserveCardsAndReplayEveryEvent(){
        int landlordWins=0,farmerWins=0;
        for(int seed=0;seed<400;seed++){
            var initial=start(seed);var state=initial;var replay=initial;int moves=0;
            var snapshot=new RoomSnapshot<>(UUID.randomUUID().toString(),1,0,1,"PLAYING",initial);
            while(!state.finished()){
                assertTrue(++moves<=200,"有限副牌不应产生无界动作");long uid=state.currentUid();LandlordAction next;
                var target=state.target();
                var choice=state.hands().get(uid).stream().filter(card->target==null||card.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
                next=choice.<LandlordAction>map(card->play(List.of(card))).orElseGet(LandlordAction.Pass::new);
                var transition=engine.apply(snapshot,new LandlordPlayCommand(uid,next));
                replay=engine.replay(replay,transition.event());state=transition.state();assertEquals(state,replay);
                snapshot=new RoomSnapshot<>(snapshot.roomId(),1,snapshot.version()+1,transition.round(),transition.phase(),state);
            }
            assertEquals("ROUND_FINISHED",snapshot.phase());assertEquals(1,snapshot.round());
            var outcome=engine.outcome(state);assertEquals(state.winner(),outcome.winner());
            if(outcome.landlordWon())landlordWins++;else farmerWins++;
            var completed=state;
            assertEquals("ROUND_FINISHED",assertThrows(RoomRejected.class,()->action(completed,completed.winner(),new LandlordAction.Pass())).code());
            assertEquals(20,initial.hands().get(100001L).size());assertEquals(0,initial.discarded().size());
        }
        assertTrue(landlordWins>0);assertTrue(farmerWins>0);
    }
    @Test void corruptOwnershipAndCursorCannotBeInstalledAsState(){
        var before=start(71);Map<Long,List<LandlordCard>> duplicate=new HashMap<>(before.hands());duplicate.put(100002L,before.hands().get(100003L));
        assertThrows(IllegalArgumentException.class,()->new LandlordPlayState(before.players(),duplicate,before.bottom(),before.landlord(),before.currentUid(),null,0,before.plays(),List.of(),0,Set.of(),0,0));
        assertThrows(IllegalArgumentException.class,()->new LandlordPlayState(before.players(),before.hands(),before.bottom(),before.landlord(),0,null,0,before.plays(),List.of(),0,Set.of(),0,0));
    }
    @Test void clearedTrickStillChecksWhoseHandActuallyLostCards(){
        var state=start(131);state=action(state,100001,play(List.of(state.hands().get(100001L).getFirst()))).state();
        state=action(state,100002,new LandlordAction.Pass()).state();var cleared=action(state,100003,new LandlordAction.Pass()).state();
        assertNull(cleared.target());
        for(var forged:List.of(Map.of(100001L,0,100002L,0,100003L,0),Map.of(100001L,0,100002L,1,100003L,0)))
            assertThrows(IllegalArgumentException.class,()->new LandlordPlayState(cleared.players(),cleared.hands(),cleared.bottom(),cleared.landlord(),cleared.currentUid(),null,0,forged,cleared.discarded(),0,Set.of(),0,0));
    }
}
