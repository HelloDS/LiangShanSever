package com.ruoyi.game.room;

import static com.ruoyi.game.room.LiveRoomState.*;

import static org.junit.jupiter.api.Assertions.*;

import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.games.landlord.*;
import com.ruoyi.game.protocol.GameJsonCodec;

import org.junit.jupiter.api.Test;

import java.util.*;

class LiveRoomEngineTest {
    final GameJsonCodec codec = new GameJsonCodec();
    final LiveRoomEngine engine = new LiveRoomEngine(codec);
    RoomSnapshot<String> snapshot;
    long now = 1_000_000;
    int fees;

    void apply(long uid, LandlordAction action) {
        var command =
                new LiveRoomEngine.Command(
                        uid == 0 ? null : new GuestIdentity(uid, "test", uid, 0, true),
                        uid == 0 ? "TICK" : "ACTION",
                        action,
                        Map.of(),
                        Map.of(101L, 101L, 102L, 101L, 103L, 101L),
                        action instanceof LandlordAction.Ready ? LandlordDeck.deal() : null,
                        now,
                        0,
                        false,
                        0);
        var change = engine.apply(snapshot, command);
        fees += change.event().postings().size();
        snapshot =
                new RoomSnapshot<>(
                        snapshot.roomId(),
                        1,
                        snapshot.version() + 1,
                        change.round(),
                        change.phase(),
                        change.state());
        now += 31_000;
    }

    LiveRoomState state() {
        return codec.value(snapshot.state(), LiveRoomState.class);
    }

    void initial() {
        var details =
                new Details(
                        UUID.randomUUID().toString(),
                        "100001",
                        101L,
                        5L,
                        6,
                        false,
                        Map.of("playNumber", 6, "aa", false));
        var seats =
                new Seats(
                        Map.of(0, 101L, 1, 102L, 2, 103L),
                        Map.of(
                                101L,
                                Map.of("uid", 101L),
                                102L,
                                Map.of("uid", 102L),
                                103L,
                                Map.of("uid", 103L)));
        var round =
                new Round(
                        0,
                        "WAITING",
                        Set.of(),
                        null,
                        Map.of(),
                        0,
                        0,
                        null,
                        Map.of(),
                        false,
                        now + 60_000);
        snapshot =
                new RoomSnapshot<>(
                        details.roomKey(),
                        1,
                        0,
                        0,
                        "WAITING",
                        codec.write(
                                new LiveRoomState(
                                        details,
                                        seats,
                                        round,
                                        Map.of(101L, 0L, 102L, 0L, 103L, 0L),
                                        Map.of(),
                                        null,
                                        0L)));
    }

    @Test
    void sixRealRoundsHaveConservedCardsPrivateViewsZeroSumAndOneFee() {
        initial();
        for (int round = 1; round <= 6; round++) {
            apply(102, new LandlordAction.Ready());
            apply(103, new LandlordAction.Ready());
            apply(101, new LandlordAction.Ready());
            assertEquals("BIDDING", state().round().phase());
            assertEquals(
                    true,
                    ((Map<?, ?>) LiveRoomProjection.room(state()).get("data")).get("isStart"));
            assertEquals(17, ((List<?>) LiveRoomProjection.cards(state(), 101).get("101")).size());
            assertEquals(17, LiveRoomProjection.cards(state(), 101).get("102"));
            assertFalse(LiveRoomProjection.cards(state(), 101).containsKey("dizhuCards"));
            apply(state().round().currentUid(), new LandlordAction.Bid(3));
            int actions = 0;
            while ("PLAYING".equals(state().round().phase())) {
                apply(0, null);
                assertTrue(++actions < 200);
            }
            assertTrue(state().round().play().finished());
            assertEquals(0, state().totals().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(round, state().round().number());
        }
        assertEquals("CLOSED", state().round().phase());
        assertEquals(1, fees);
        assertEquals(7, LiveRoomProjection.game(state(), 101).get("currentCount"));
    }

    @Test
    void wrongTurnAndRepeatedReadyAreRejectedWithoutChangingCommittedState() {
        initial();
        apply(101, new LandlordAction.Ready());
        String committed = snapshot.state();
        assertThrows(RoomRejected.class, () -> apply(101, new LandlordAction.Ready()));
        assertEquals(committed, snapshot.state());
        apply(102, new LandlordAction.Ready());
        apply(103, new LandlordAction.Ready());
        assertThrows(RoomRejected.class, () -> apply(102, new LandlordAction.Bid(3)));
        assertThrows(SecurityException.class, () -> LiveRoomProjection.game(state(), 999));
    }

    @Test
    void olderSnapshotWithoutTransportFieldsGetsItsPermanentIdentityAndRevision() {
        initial();
        var legacy = codec.object(snapshot.state());
        legacy.remove("revision");
        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) legacy.get("details");
        details.remove("roomKey");
        snapshot = new RoomSnapshot<>(snapshot.roomId(), 1, 0, 0, "WAITING", codec.write(legacy));
        apply(101, new LandlordAction.Ready());
        assertEquals(snapshot.roomId(), state().details().roomKey());
        assertEquals(1L, state().revision().longValue());
    }
}
