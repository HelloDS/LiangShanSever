package com.ruoyi.game.games.landlord;

import java.time.Instant;
import java.util.*;
import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.room.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandlordPlayCodecTest {
    private final LandlordPlayCodec codec=new LandlordPlayCodec(new GameJsonCodec());
    private final LandlordPlayEngine engine=new LandlordPlayEngine();
    private LandlordPlayState initial(){return LandlordPlayEngine.start(List.of(100001L,100002L,100003L),LandlordDeck.deal(new Random(91)),100001);}
    private LandlordAction.Play single(LandlordCard card){return new LandlordAction.Play(List.of(card),LandlordPatterns.classify(List.of(card)).orElseThrow());}
    @Test void privateStateRoundTripsWithoutMutableCollectionsOrUnversionedSupport(){
        var state=initial();assertEquals(state,codec.state(codec.json(state)));assertEquals(codec.json(state),codec.json(codec.state(codec.json(state))));
        assertTrue(codec.supports("6003",1,LandlordPlayEngine.ENGINE_VERSION));assertFalse(codec.supports("6009",1,LandlordPlayEngine.ENGINE_VERSION));assertFalse(codec.supports("6003",2,LandlordPlayEngine.ENGINE_VERSION));
        assertThrows(UnsupportedOperationException.class,()->codec.state(codec.json(state)).hands().clear());
    }
    @Test void exactEventFieldsRejectUnknownKeysWrongTypesAndUnknownVersions(){
        for(var event:List.<LandlordPlayEvent>of(new LandlordPlayEvent.Played(100001,List.of(new LandlordCard(1,3)),1),new LandlordPlayEvent.Passed(100002),new LandlordPlayEvent.AutoChanged(100003,true)))
            assertEquals(event,codec.event(codec.kind(event),codec.eventJson(event)));
        for(String json:List.of("{\"uid\":100001,\"enabled\":1}","{\"uid\":100001.0,\"enabled\":true}","{\"uid\":100001,\"enabled\":true,\"extra\":1}","{\"uid\":\"100001\",\"enabled\":true}"))
            assertThrows(IllegalArgumentException.class,()->codec.event("AUTO_CHANGED",json));
        assertThrows(IllegalArgumentException.class,()->codec.event("DEAL_RECORDED","{}"));
        assertThrows(IllegalArgumentException.class,()->codec.event("CARD_PLAYED","{\"uid\":100001,\"cards\":[[0,0]],\"type\":1}"));
    }
    @Test void stateDecoderRejectsExtraFieldsAndCorruptOwnership(){
        String json=codec.json(initial());
        assertThrows(IllegalArgumentException.class,()->codec.state(json.substring(0,json.length()-1)+",\"price\":1}"));
        assertThrows(IllegalArgumentException.class,()->codec.state(json.replace("\"currentUid\":100001","\"currentUid\":0")));
        assertThrows(IllegalArgumentException.class,()->codec.state(json.replace("\"landlord\":100001","\"landlord\":999999")));
        assertThrows(IllegalArgumentException.class,()->codec.state("{}"));
    }
    @Test void persistedEventsCanRebuildACompleteNaturallyPlayedRound(){
        var state=initial();var replay=codec.state(codec.json(state));
        for(int move=0;!state.finished();move++){
            assertTrue(move<200);long uid=state.currentUid();var target=state.target();
            var candidate=state.hands().get(uid).stream().filter(card->target==null||card.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
            LandlordAction action=candidate.<LandlordAction>map(this::single).orElseGet(LandlordAction.Pass::new);
            var change=engine.change(state,new LandlordPlayCommand(uid,action));
            replay=engine.replay(replay,codec.event(codec.kind(change.event()),codec.eventJson(change.event())));state=change.state();
            assertEquals(codec.json(state),codec.json(replay));
        }
        assertTrue(replay.finished());assertEquals(state.winner(),engine.outcome(replay).winner());
    }
    @Test void plansBindActorAndRejectForgedCandidateBeforeStartingDatabaseWork(){
        var state=initial();var previous=new RoomSnapshot<>(UUID.randomUUID().toString(),3,10,1,"PLAYING",state);
        var command=new LandlordPlayCommand(100001,single(state.hands().get(100001L).getFirst()));var transition=engine.apply(previous,command);
        var candidate=new RoomSnapshot<>(previous.roomId(),3,11,1,"PLAYING",transition.state());var occurred=Instant.parse("2026-11-06T19:00:00Z");
        var write=new RoomPersistence.Write<>(previous,candidate,command,transition.event(),occurred);
        var plans=new LandlordPlayPlans(codec,"fixture-v1");var actor=new GuestIdentity(1,"fixture",100001,0,true);var plan=plans.command(write,actor);
        assertEquals(occurred,plan.occurredAt());assertFalse(plan.checkpoint());assertTrue(plan.postings().isEmpty());assertEquals("CARD_PLAYED",plan.eventKind());
        assertThrows(IllegalArgumentException.class,()->plans.command(write,new GuestIdentity(2,"fixture",100002,0,true)));
        var forged=new RoomSnapshot<>(previous.roomId(),3,11,1,"PLAYING",state);
        assertThrows(IllegalArgumentException.class,()->plans.command(new RoomPersistence.Write<>(previous,forged,command,transition.event(),occurred),actor));
    }
    @Test void replayRejectsOutOfTurnOrUnownedSavedEvents(){
        var state=initial();
        assertThrows(RoomRejected.class,()->engine.replay(state,new LandlordPlayEvent.Passed(100002)));
        assertThrows(RoomRejected.class,()->engine.replay(state,new LandlordPlayEvent.Played(100001,List.of(state.hands().get(100002L).getFirst()),1)));
    }
}
