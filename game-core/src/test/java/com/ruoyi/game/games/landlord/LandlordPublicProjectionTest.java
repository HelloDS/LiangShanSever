package com.ruoyi.game.games.landlord;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordPublicProjectionTest {
    private final LandlordPlayEngine engine=new LandlordPlayEngine();
    private LandlordPlayState start(){return LandlordPlayEngine.start(List.of(100001L,100002L,100003L),LandlordDeck.deal(new Random(231)),100001);}
    private LandlordAction.Play single(LandlordCard card){return new LandlordAction.Play(List.of(card),LandlordPatterns.classify(List.of(card)).orElseThrow());}
    @Test void originalPanelReceivesOneBasedObjectSeatsAndNullLeadingTarget(){
        var state=start();var seats=LandlordPublicProjection.seats(state,100001);
        assertEquals(List.of("1","2","3"),new ArrayList<>(seats.keySet()));assertEquals(100003,seats.get("3"));assertNull(LandlordPublicProjection.outCards(state,100001));
        assertThrows(UnsupportedOperationException.class,()->seats.clear());assertThrows(SecurityException.class,()->LandlordPublicProjection.seats(state,999999));
    }
    @Test void publishedTargetIsTheActualPlayedCardsAndClearsAfterTwoPasses(){
        var state=start();var card=state.hands().get(100001L).getFirst();state=engine.change(state,new LandlordPlayCommand(100001,single(card))).state();
        var target=LandlordPublicProjection.outCards(state,100002);assertEquals(Map.of("100001",List.of(card.wire())),target);assertThrows(UnsupportedOperationException.class,()->target.get("100001").clear());
        state=engine.change(state,new LandlordPlayCommand(100002,new LandlordAction.Pass())).state();state=engine.change(state,new LandlordPlayCommand(100003,new LandlordAction.Pass())).state();
        assertNull(LandlordPublicProjection.outCards(state,100002));
    }
    @Test void remainingHandsAreAvailableOnlyToMembersAfterNaturalCompletion(){
        var initial=start();assertThrows(IllegalArgumentException.class,()->LandlordPublicProjection.finishedCards(initial,100001));var state=initial;
        for(int step=0;!state.finished();step++){
            assertTrue(step<200);long uid=state.currentUid();var target=state.target();
            var card=state.hands().get(uid).stream().filter(value->target==null||value.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
            LandlordAction action=card.<LandlordAction>map(this::single).orElseGet(LandlordAction.Pass::new);state=engine.change(state,new LandlordPlayCommand(uid,action)).state();
        }
        var completed=state;var cards=LandlordPublicProjection.finishedCards(completed,100002);assertEquals(3,cards.size());assertEquals(List.of(),cards.get(Long.toString(completed.winner())));
        assertThrows(SecurityException.class,()->LandlordPublicProjection.finishedCards(completed,999999));assertThrows(UnsupportedOperationException.class,()->cards.clear());
        assertEquals(20,initial.hands().get(100001L).size());
    }
}
