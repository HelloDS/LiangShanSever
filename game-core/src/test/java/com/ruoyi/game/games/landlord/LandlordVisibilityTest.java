package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordVisibilityTest {
    private static final List<Long> PLAYERS=List.of(100001L,100002L,100003L);
    private Map<Long,List<LandlordCard>> hands(LandlordDeck.Deal deal){return Map.of(PLAYERS.get(0),deal.hands().get(0),PLAYERS.get(1),deal.hands().get(1),PLAYERS.get(2),deal.hands().get(2));}
    @Test void biddingViewContainsOnlyOwnHandAndNoBottom(){
        var deal=LandlordDeck.deal(new Random(31));var source=hands(deal);var codec=new GameJsonCodec();
        for(long viewer:PLAYERS){
            var projection=LandlordVisibility.cardsInfo(PLAYERS,source,deal.bottom(),0,LandlordVisibility.Phase.BIDDING,viewer);
            var json=codec.read(codec.write(projection));assertFalse(json.has("dizhuCards"));
            for(long uid:PLAYERS)if(uid==viewer){assertTrue(json.get(Long.toString(uid)).isArray());assertEquals(17,json.get(Long.toString(uid)).size());}
            else{assertTrue(json.get(Long.toString(uid)).isIntegralNumber());assertEquals(17,json.get(Long.toString(uid)).asInt());}
            assertThrows(UnsupportedOperationException.class,()->projection.clear());
        }
        assertEquals(17,source.get(100001L).size());
    }
    @Test void playingRevealsBottomButKeepsOtherHandsAsCounts(){
        var deal=LandlordDeck.deal(new Random(31));Map<Long,List<LandlordCard>> source=new HashMap<>(hands(deal));
        List<LandlordCard> landlord=new ArrayList<>(source.get(100001L));landlord.addAll(deal.bottom());source.put(100001L,List.copyOf(landlord));
        var view=LandlordVisibility.cardsInfo(PLAYERS,source,deal.bottom(),100001,LandlordVisibility.Phase.PLAYING,100002);
        assertEquals(20,view.get("100001"));assertEquals(17,((List<?>)view.get("100002")).size());assertEquals(17,view.get("100003"));
        assertEquals(3,((List<?>)view.get("dizhuCards")).size());
    }
    @Test void completedViewRequiresWinnerAndStillRejectsSpectator(){
        var deal=LandlordDeck.deal(new Random(31));Map<Long,List<LandlordCard>> source=new HashMap<>(hands(deal));source.put(100001L,List.of());
        var view=LandlordVisibility.cardsInfo(PLAYERS,source,deal.bottom(),100001,LandlordVisibility.Phase.FINISHED,100002);
        assertTrue(view.get("100003") instanceof List<?>);
        assertThrows(SecurityException.class,()->LandlordVisibility.cardsInfo(PLAYERS,source,deal.bottom(),100001,LandlordVisibility.Phase.FINISHED,999999));
        assertThrows(IllegalArgumentException.class,()->LandlordVisibility.cardsInfo(PLAYERS,hands(deal),deal.bottom(),100001,LandlordVisibility.Phase.FINISHED,100002));
    }
    @Test void duplicateOwnershipAndPrematureCardsFailClosed(){
        var deal=LandlordDeck.deal(new Random(31));Map<Long,List<LandlordCard>> duplicate=new HashMap<>(hands(deal));duplicate.put(100002L,duplicate.get(100001L));
        assertThrows(IllegalArgumentException.class,()->LandlordVisibility.cardsInfo(PLAYERS,duplicate,deal.bottom(),0,LandlordVisibility.Phase.BIDDING,100001));
        assertThrows(IllegalArgumentException.class,()->LandlordVisibility.cardsInfo(PLAYERS,hands(deal),deal.bottom(),0,LandlordVisibility.Phase.WAITING,100001));
        var waiting=LandlordVisibility.cardsInfo(List.of(100001L),Map.of(100001L,List.of()),List.of(),0,LandlordVisibility.Phase.WAITING,100001);
        assertEquals(List.of(),waiting.get("100001"));
    }
}
