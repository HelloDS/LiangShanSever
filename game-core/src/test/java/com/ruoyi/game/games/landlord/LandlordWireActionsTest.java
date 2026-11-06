package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordWireActionsTest {
    private final GameJsonCodec codec=new GameJsonCodec();
    private LandlordAction parse(String json){return LandlordWireActions.parse(codec.read(json));}
    @Test void originalFlatTagsHaveExplicitTypedActions(){
        assertInstanceOf(LandlordAction.Ready.class,parse("{\"api\":\"room\",\"c\":\"gameMessage\",\"tag\":\"ready\"}"));
        assertEquals(new LandlordAction.Bid(3),parse("{\"tag\":\"score\",\"score\":3}"));
        assertEquals(new LandlordAction.DoubleChoice(true),parse("{\"tag\":\"jiabei\",\"jiabei\":1}"));
        assertInstanceOf(LandlordAction.Pass.class,parse("{\"tag\":\"buchu\"}"));assertInstanceOf(LandlordAction.ToggleAuto.class,parse("{\"tag\":\"auto\"}"));
    }
    @Test void playUsesTupleEncodingAndServerRecomputedPattern(){
        var play=assertInstanceOf(LandlordAction.Play.class,parse("{\"tag\":\"chupai\",\"outCards\":[[0,16],[0,17]],\"opCardType\":13}"));
        assertEquals(LandlordPatterns.Type.ROCKET,play.pattern().type());assertEquals(List.of(0,16),play.cards().getFirst().wire());
        assertThrows(UnsupportedOperationException.class,()->play.cards().clear());
        assertThrows(IllegalArgumentException.class,()->parse("{\"tag\":\"chupai\",\"outCards\":[[1,3]],\"opCardType\":12}"));
    }
    @Test void stringsBooleansOutOfRangeAndMalformedTuplesAreRejected(){
        for(String json:List.of("{\"tag\":\"score\",\"score\":\"1\"}","{\"tag\":\"score\",\"score\":true}","{\"tag\":\"score\",\"score\":4}","{\"tag\":\"jiabei\",\"jiabei\":2}",
                "{\"tag\":\"chupai\",\"outCards\":[[1,3,4]],\"opCardType\":1}","{\"tag\":\"chupai\",\"outCards\":[[1,3.0]],\"opCardType\":1}",
                "{\"tag\":\"chupai\",\"outCards\":[[0,0]],\"opCardType\":1}","{\"tag\":\"chupai\",\"outCards\":[[1,3],[1,3]],\"opCardType\":2}"))
            assertThrows(IllegalArgumentException.class,()->parse(json));
    }
    @Test void otherLandlordVariantsCannotInjectTheirActionsOrWildCards(){
        for(String tag:List.of("seeCards","drawCards","daopai","applyAuto"))assertThrows(IllegalArgumentException.class,()->parse("{\"tag\":\""+tag+"\"}"));
        assertThrows(IllegalArgumentException.class,()->parse("{\"tag\":\"chupai\",\"outCards\":[[-1,3]],\"opCardType\":1}"));
        assertEquals(new LandlordAction.Ready(),parse("{\"tag\":\"ready\",\"uid\":999999,\"sender\":999999}"));
    }
    @Test void internalPlayCannotCarryAForgedPatternEither(){
        assertThrows(IllegalArgumentException.class,()->new LandlordAction.Play(List.of(new LandlordCard(1,3)),new LandlordPatterns.Pattern(LandlordPatterns.Type.BOMB,4,3)));
    }
}
