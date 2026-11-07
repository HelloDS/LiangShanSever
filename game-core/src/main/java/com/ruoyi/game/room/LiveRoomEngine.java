package com.ruoyi.game.room;

import static com.ruoyi.game.room.LiveRoomState.*;

import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.economy.WalletPosting;
import com.ruoyi.game.games.landlord.*;
import com.ruoyi.game.protocol.GameJsonCodec;

import java.util.*;

/** 公共 6003 房间流程。随机牌组和时刻随命令冻结，分片内仅执行纯计算。 */
public final class LiveRoomEngine
        implements RoomMailboxes.Engine<String, LiveRoomEngine.Command, LiveRoomEngine.Event> {
    public static final String VERSION = "landlord-session-v1";
    public static final String RULE = "club-room-20261107-v1";
    private final GameJsonCodec codec;
    private final LandlordPlayEngine playEngine = new LandlordPlayEngine();
    private final LandlordScoring scoring =
            new LandlordScoring(new LandlordScoring.Policy("local-6003-v1", 1, 64));

    public record Command(
            GuestIdentity actor,
            String kind,
            LandlordAction action,
            Map<String, Object> role,
            Map<Long, Long> partners,
            LandlordDeck.Deal deal,
            long now,
            int vote,
            boolean authorized) {
        public Command {
            role = Map.copyOf(role);
            partners = Map.copyOf(partners);
        }

        public long uid() {
            return actor == null ? 0 : actor.uid();
        }
    }

    public record Event(
            String kind,
            String tag,
            long sender,
            RoomJournalCommand.SeatChange seat,
            List<WalletPosting> postings) {
        public Event {
            postings = List.copyOf(postings);
        }
    }

    public LiveRoomEngine(GameJsonCodec codec) {
        this.codec = codec;
    }

    @Override
    public RoomTransition<String, Event> apply(RoomSnapshot<String> before, Command command) {
        var state =
                codec.value(before.state(), LiveRoomState.class)
                        .withRuntimeIdentity(before.roomId(), before.version());
        var round = state.round();
        if ("CLOSED".equals(round.phase())) throw rejected("ROOM_CLOSED");
        LiveRoomState next;
        String tag = "";
        RoomJournalCommand.SeatChange seat = null;
        long uid = command.uid();
        switch (command.kind()) {
            case "JOIN" -> {
                if (!"WAITING".equals(round.phase()) || state.seats().players().size() == 3)
                    throw rejected("ROOM_FULL");
                if (state.seats().roles().containsKey(uid)) throw rejected("ALREADY_SEATED");
                var players = new TreeMap<>(state.seats().players());
                int index = 0;
                while (players.containsKey(index)) index++;
                players.put(index, uid);
                var roles = new LinkedHashMap<>(state.seats().roles());
                roles.put(uid, command.role());
                var totals = new LinkedHashMap<>(state.totals());
                totals.put(uid, 0L);
                next =
                        new LiveRoomState(
                                state.details(),
                                new Seats(players, roles),
                                round,
                                totals,
                                state.partners(),
                                null,
                                state.revision());
                seat =
                        new RoomJournalCommand.SeatChange(
                                RoomJournalCommand.SeatAction.JOIN, uid, index);
                tag = "joinRoom";
            }
            case "LEAVE" -> {
                requirePlayer(state, uid);
                if (!"WAITING".equals(round.phase()) || round.number() > 0)
                    throw rejected("GAME_ALREADY_STARTED");
                int index =
                        state.seats().players().entrySet().stream()
                                .filter(e -> e.getValue() == command.uid())
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElseThrow();
                var players = new TreeMap<>(state.seats().players());
                players.remove(index);
                var roles = new LinkedHashMap<>(state.seats().roles());
                roles.remove(uid);
                var ready = new HashSet<>(round.ready());
                ready.remove(uid);
                var totals = new LinkedHashMap<>(state.totals());
                totals.remove(uid);
                var waiting =
                        new Round(
                                0,
                                "WAITING",
                                ready,
                                null,
                                Map.of(),
                                0,
                                0,
                                null,
                                Map.of(),
                                false,
                                round.dueAt());
                next =
                        new LiveRoomState(
                                state.details(),
                                new Seats(players, roles),
                                waiting,
                                totals,
                                state.partners(),
                                null,
                                state.revision());
                seat =
                        new RoomJournalCommand.SeatChange(
                                RoomJournalCommand.SeatAction.LEAVE, uid, index);
                tag = "leaveRoom";
            }
            case "CLOSE", "FORCE_CLOSE" -> {
                if (!command.authorized()) throw rejected("FORBIDDEN");
                if ("CLOSE".equals(command.kind())
                        && ("BIDDING".equals(round.phase()) || "PLAYING".equals(round.phase())))
                    throw rejected("VOTE_REQUIRED");
                next = close(state);
                tag = "FORCE_CLOSE".equals(command.kind()) ? "forceDisbandRoom" : "disbandRoom";
            }
            case "VOTE" -> {
                requirePlayer(state, uid);
                if (command.vote() != 0 && command.vote() != 1) throw rejected("INVALID_VOTE");
                var vote = state.vote();
                if (vote == null) {
                    if (command.vote() != 1) throw rejected("NO_VOTE");
                    vote = new Vote(uid, Map.of(), command.now() + 60_000);
                }
                var answers = new LinkedHashMap<>(vote.answers());
                if (answers.putIfAbsent(uid, command.vote()) != null)
                    throw rejected("ALREADY_VOTED");
                next = state.withVote(new Vote(vote.initiator(), answers, vote.until()));
                if (command.vote() == 0) next = state.withVote(null);
                else if (answers.size() == state.seats().players().size()) next = close(next);
                tag = "voteEndGame";
            }
            case "TICK" -> {
                if (state.vote() != null && state.vote().until() <= command.now()) {
                    next = state.withVote(null);
                    tag = "voteEndGame";
                } else {
                    if (round.dueAt() > command.now()) throw rejected("STALE_TIMER");
                    if ("WAITING".equals(round.phase()) || "ROUND_FINISHED".equals(round.phase())) {
                        next = close(state);
                        tag = "roomTimeOver";
                    } else if ("BIDDING".equals(round.phase())) {
                        next = bid(state, round.currentUid(), 0, command.now());
                        tag = "score";
                        uid = round.currentUid();
                    } else if ("PLAYING".equals(round.phase())) {
                        uid = round.play().currentUid();
                        var action = automatic(round.play(), uid);
                        next = play(state, uid, action, command.now());
                        tag = action instanceof LandlordAction.Pass ? "buchu" : "chupai";
                    } else throw rejected("NO_TIMEOUT");
                }
            }
            case "ACTION" -> {
                requirePlayer(state, uid);
                if (command.action() instanceof LandlordAction.Ready) {
                    next = ready(state, command);
                    tag =
                            next.round().deal() != null && next.round().number() > round.number()
                                    ? "startGame"
                                    : "ready";
                } else if (command.action() instanceof LandlordAction.Bid bid) {
                    next = bid(state, uid, bid.score(), command.now());
                    tag = "score";
                } else {
                    next = play(state, uid, command.action(), command.now());
                    tag =
                            command.action() instanceof LandlordAction.Pass
                                    ? "buchu"
                                    : command.action() instanceof LandlordAction.ToggleAuto
                                            ? "auto"
                                            : "chupai";
                }
            }
            default -> throw rejected("UNKNOWN_COMMAND");
        }
        var postings =
                !round.charged() && next.round().charged()
                        ? fees(before.roomId(), next)
                        : List.<WalletPosting>of();
        return new RoomTransition<>(
                next.round().number(),
                next.round().phase(),
                codec.write(next.withRevision(before.version() + 1)),
                new Event(command.kind(), tag, uid, seat, postings));
    }

    private LiveRoomState ready(LiveRoomState state, Command command) {
        var round = state.round();
        if (!Set.of("WAITING", "ROUND_FINISHED").contains(round.phase()))
            throw rejected("WRONG_PHASE");
        if (round.ready().contains(command.uid())) throw rejected("ALREADY_READY");
        var ready = new HashSet<>(round.ready());
        ready.add(command.uid());
        if (ready.size() != 3)
            return state.withRound(
                    new Round(
                            round.number(),
                            round.phase(),
                            ready,
                            round.deal(),
                            round.bids(),
                            0,
                            round.winningBid(),
                            round.play(),
                            round.deltas(),
                            round.charged(),
                            round.dueAt()));
        var players = state.seats().ordered();
        var next =
                new Round(
                        round.number() + 1,
                        "BIDDING",
                        ready,
                        Objects.requireNonNull(command.deal()),
                        Map.of(),
                        players.get((round.number()) % 3),
                        0,
                        null,
                        Map.of(),
                        true,
                        command.now() + 30_000);
        var affiliations = state.partners().isEmpty() ? command.partners() : state.partners();
        return new LiveRoomState(
                state.details(),
                state.seats(),
                next,
                state.totals(),
                affiliations,
                null,
                state.revision());
    }

    private LiveRoomState bid(LiveRoomState state, long uid, int score, long now) {
        var round = state.round();
        if (!"BIDDING".equals(round.phase()) || round.currentUid() != uid)
            throw rejected("NOT_YOUR_TURN");
        int highest = round.bids().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (score != 0 && score <= highest) throw rejected("BID_TOO_LOW");
        var bids = new LinkedHashMap<>(round.bids());
        bids.put(uid, score);
        var players = state.seats().ordered();
        if (score == 3 || bids.size() == 3) {
            long landlord = players.getFirst();
            int winning = 0;
            for (var entry : bids.entrySet())
                if (entry.getValue() > winning) {
                    landlord = entry.getKey();
                    winning = entry.getValue();
                }
            // 三家均不叫时，当前局首位叫分者承担默认 1 分，避免无限重发牌。
            if (winning == 0) {
                landlord = bids.keySet().iterator().next();
                winning = 1;
                bids.put(landlord, 1);
            }
            var play = LandlordPlayEngine.start(players, round.deal(), landlord);
            return state.withRound(
                    new Round(
                            round.number(),
                            "PLAYING",
                            round.ready(),
                            round.deal(),
                            bids,
                            landlord,
                            winning,
                            play,
                            Map.of(),
                            true,
                            now + 30_000));
        }
        long next = players.get((players.indexOf(uid) + 1) % 3);
        return state.withRound(
                new Round(
                        round.number(),
                        "BIDDING",
                        round.ready(),
                        round.deal(),
                        bids,
                        next,
                        0,
                        null,
                        Map.of(),
                        true,
                        now + 30_000));
    }

    private LiveRoomState play(LiveRoomState state, long uid, LandlordAction action, long now) {
        var round = state.round();
        if (!"PLAYING".equals(round.phase())) throw rejected("WRONG_PHASE");
        var play = playEngine.change(round.play(), new LandlordPlayCommand(uid, action)).state();
        if (!play.finished()) {
            long due = now + (play.auto().contains(play.currentUid()) ? 1_000 : 30_000);
            if (action instanceof LandlordAction.ToggleAuto)
                due =
                        play.auto().contains(play.currentUid())
                                ? Math.min(round.dueAt(), now + 1_000)
                                : round.dueAt();
            return state.withRound(
                    new Round(
                            round.number(),
                            "PLAYING",
                            round.ready(),
                            round.deal(),
                            round.bids(),
                            play.currentUid(),
                            round.winningBid(),
                            play,
                            Map.of(),
                            true,
                            due));
        }
        var result = scoring.score(play, round.winningBid());
        var totals = new LinkedHashMap<>(state.totals());
        result.deltas().forEach((player, delta) -> totals.merge(player, delta, Math::addExact));
        String phase = round.number() == state.details().maxRounds() ? "CLOSED" : "ROUND_FINISHED";
        var finished =
                new Round(
                        round.number(),
                        phase,
                        Set.of(),
                        round.deal(),
                        round.bids(),
                        0,
                        round.winningBid(),
                        play,
                        result.deltas(),
                        true,
                        now + 30 * 60_000);
        return new LiveRoomState(
                state.details(),
                state.seats(),
                finished,
                totals,
                state.partners(),
                null,
                state.revision());
    }

    private static LandlordAction automatic(LandlordPlayState play, long uid) {
        if (play.target() != null) return new LandlordAction.Pass();
        var card =
                play.hands().get(uid).stream()
                        .min(
                                Comparator.comparingInt(LandlordCard::rank)
                                        .thenComparingInt(LandlordCard::suit))
                        .orElseThrow();
        var cards = List.of(card);
        return new LandlordAction.Play(cards, LandlordPatterns.classifyAs(cards, 1).orElseThrow());
    }

    private static LiveRoomState close(LiveRoomState state) {
        var r = state.round();
        return state.withRound(
                new Round(
                        r.number(),
                        "CLOSED",
                        r.ready(),
                        r.deal(),
                        r.bids(),
                        0,
                        r.winningBid(),
                        r.play(),
                        r.deltas(),
                        r.charged(),
                        0));
    }

    private static void requirePlayer(LiveRoomState state, long uid) {
        if (!state.seats().roles().containsKey(uid)) throw rejected("NOT_A_PARTICIPANT");
    }

    private static RoomRejected rejected(String code) {
        return new RoomRejected(code);
    }

    private static List<WalletPosting> fees(String room, LiveRoomState state) {
        var d = state.details();
        long unit = d.maxRounds() / 6;
        var entries = new ArrayList<WalletPosting.Entry>();
        if (d.club() != null)
            entries.add(
                    new WalletPosting.Entry(
                            new WalletPosting.Account(
                                    WalletPosting.OwnerType.CLUB,
                                    d.club(),
                                    WalletPosting.Currency.CARD),
                            -3 * unit,
                            0));
        else if (d.aa())
            for (long uid : state.seats().ordered())
                entries.add(
                        new WalletPosting.Entry(
                                new WalletPosting.Account(
                                        WalletPosting.OwnerType.PERSONAL,
                                        uid,
                                        WalletPosting.Currency.CARD),
                                -unit,
                                0));
        else
            entries.add(
                    new WalletPosting.Entry(
                            new WalletPosting.Account(
                                    WalletPosting.OwnerType.PERSONAL,
                                    d.owner(),
                                    WalletPosting.Currency.CARD),
                            -3 * unit,
                            0));
        entries.add(
                new WalletPosting.Entry(
                        new WalletPosting.Account(
                                WalletPosting.OwnerType.SYSTEM, 0, WalletPosting.Currency.CARD),
                        3 * unit,
                        0));
        return List.of(
                new WalletPosting(
                        WalletPosting.Kind.CONSUMPTION,
                        "room:" + room + ":fee",
                        RULE,
                        room,
                        entries));
    }
}
