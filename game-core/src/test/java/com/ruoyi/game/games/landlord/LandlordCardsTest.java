package com.ruoyi.game.games.landlord;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.game.games.landlord.LandlordPatterns.Type.*;

class LandlordCardsTest {
    private static List<LandlordCard> cards(int... ranks){
        Map<Integer,Integer> suits=new HashMap<>();List<LandlordCard> result=new ArrayList<>();
        for(int rank:ranks)result.add(new LandlordCard(rank>15?0:suits.merge(rank,1,Integer::sum),rank));
        return List.copyOf(result);
    }
    private static LandlordPatterns.Pattern pattern(LandlordPatterns.Type type,int... ranks){return LandlordPatterns.classifyAs(cards(ranks),type.wire()).orElseThrow();}

    @Test void materialEncodingRejectsBackFacesInvalidSuitsAndInvalidJokers(){
        assertEquals(List.of(0,16),new LandlordCard(0,16).wire());assertEquals(List.of(4,15),new LandlordCard(4,15).wire());
        for(int[] invalid:List.of(new int[]{0,0},new int[]{0,3},new int[]{-1,3},new int[]{5,14},new int[]{4,16},new int[]{1,2},new int[]{0,18}))
            assertThrows(IllegalArgumentException.class,()->new LandlordCard(invalid[0],invalid[1]));
        assertEquals(54,LandlordDeck.cards().size());assertEquals(54,new HashSet<>(LandlordDeck.cards()).size());
    }
    @Test void dealsAreCompleteWithoutReplacementAndDeeplyImmutable(){
        for(int seed=0;seed<100;seed++){
            var deal=LandlordDeck.deal(new Random(seed));Set<LandlordCard> combined=new HashSet<>(deal.bottom());
            assertEquals(3,deal.bottom().size());
            for(var hand:deal.hands()){assertEquals(17,hand.size());combined.addAll(hand);}
            assertEquals(new HashSet<>(LandlordDeck.cards()),combined);
        }
        var deal=LandlordDeck.deal(new Random(42));assertEquals(deal,LandlordDeck.deal(new Random(42)));
        assertThrows(UnsupportedOperationException.class,()->deal.hands().getFirst().clear());assertThrows(UnsupportedOperationException.class,()->deal.bottom().clear());
        assertEquals(54,LandlordDeck.deal().hands().stream().mapToInt(List::size).sum()+3);
    }
    @Test void allThirteenWireTypesAreRecomputedFromPhysicalCards(){
        assertEquals(SINGLE,pattern(SINGLE,17).type());assertEquals(PAIR,pattern(PAIR,15,15).type());assertEquals(TRIPLE,pattern(TRIPLE,5,5,5).type());
        assertEquals(3,pattern(TRIPLE_SINGLE,3,3,3,15).mainRank());assertEquals(3,pattern(TRIPLE_PAIR,3,3,3,15,15).mainRank());
        assertEquals(3,pattern(STRAIGHT,3,4,5,6,7).mainRank());assertEquals(3,pattern(PAIR_STRAIGHT,3,3,4,4,5,5).mainRank());
        assertEquals(3,pattern(AIRPLANE,3,3,3,4,4,4).mainRank());assertEquals(3,pattern(AIRPLANE_SINGLES,3,3,3,4,4,4,8,9).mainRank());
        assertEquals(3,pattern(AIRPLANE_PAIRS,3,3,3,4,4,4,8,8,9,9).mainRank());assertEquals(3,pattern(FOUR_TWO,3,3,3,3,8,8).mainRank());
        assertEquals(BOMB,pattern(BOMB,15,15,15,15).type());assertEquals(ROCKET,pattern(ROCKET,16,17).type());
        for(var type:LandlordPatterns.Type.values())assertEquals(type,LandlordPatterns.Type.of(type.wire()));
    }
    @Test void forgedTypeDuplicatePhysicalCardAndOutOfHandCardAreRejected(){
        assertTrue(LandlordPatterns.classifyAs(cards(3,3,3,4),BOMB.wire()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->LandlordPatterns.classifyAs(cards(3),999));
        var duplicate=new LandlordCard(1,3);assertThrows(IllegalArgumentException.class,()->LandlordPatterns.classify(List.of(duplicate,duplicate)));
        assertThrows(IllegalArgumentException.class,()->LandlordPatterns.removeOwned(cards(3,4),List.of(new LandlordCard(2,3))));
        assertThrows(IllegalArgumentException.class,()->LandlordPatterns.classify(List.of()));
    }
    @Test void comparisonsUseMainGroupAndRequireSameSequenceLength(){
        assertTrue(LandlordPatterns.beats(pattern(TRIPLE_SINGLE,4,4,4,3),pattern(TRIPLE_SINGLE,3,3,3,17)));
        assertFalse(LandlordPatterns.beats(pattern(STRAIGHT,4,5,6,7,8,9),pattern(STRAIGHT,3,4,5,6,7)));
        assertTrue(LandlordPatterns.beats(pattern(STRAIGHT,4,5,6,7,8),pattern(STRAIGHT,3,4,5,6,7)));
        assertTrue(LandlordPatterns.beats(pattern(BOMB,3,3,3,3),pattern(SINGLE,17)));
        assertTrue(LandlordPatterns.beats(pattern(ROCKET,16,17),pattern(BOMB,15,15,15,15)));
        assertFalse(LandlordPatterns.beats(pattern(BOMB,15,15,15,15),pattern(ROCKET,16,17)));
        assertFalse(LandlordPatterns.beats(pattern(ROCKET,16,17),pattern(ROCKET,16,17)));
    }
    @Test void twoAndJokersCannotExtendStraights(){
        assertTrue(LandlordPatterns.classifyAs(cards(11,12,13,14,15),STRAIGHT.wire()).isEmpty());
        assertTrue(LandlordPatterns.classifyAs(cards(13,13,14,14,15,15),PAIR_STRAIGHT.wire()).isEmpty());
        assertTrue(LandlordPatterns.classifyAs(cards(14,14,14,15,15,15),AIRPLANE.wire()).isEmpty());
    }
    @Test void sourceAirplaneLimitsAreExplicitInsteadOfSilentlyInventedRules(){
        assertTrue(LandlordPatterns.classifyAs(cards(3,3,3,4,4,4,5,5,5),AIRPLANE.wire()).isEmpty());
        assertTrue(LandlordPatterns.classifyAs(cards(3,3,3,3,4,4,4,4),AIRPLANE_SINGLES.wire()).isEmpty());
        assertTrue(LandlordPatterns.classifyAs(cards(3,3,3,3,4,4,4,5),AIRPLANE_SINGLES.wire()).isPresent());
        assertTrue(LandlordPatterns.classifyAs(cards(3,3,3,4,4,4,15,15,6,6),AIRPLANE_PAIRS.wire()).isEmpty());
        assertEquals(5,pattern(AIRPLANE_PAIRS,5,5,5,6,6,6,3,3,3,3).mainRank());
    }
    @Test void removingCardsAndClassificationCannotMutateCommittedHand(){
        var hand=cards(3,3,4,5,6,7,8);var original=List.copyOf(hand);
        var remaining=LandlordPatterns.removeOwned(hand,List.of(hand.getFirst(),hand.get(2)));
        assertEquals(5,remaining.size());assertEquals(original,hand);assertThrows(UnsupportedOperationException.class,()->remaining.clear());
        LandlordPatterns.classifyAs(cards(3,4,5,6,7),STRAIGHT.wire());assertEquals(original,hand);
    }
}
