package com.ruoyi.game.room;

import com.ruoyi.game.games.landlord.*;

import java.util.*;

/** 私有且有版本的整桌状态。只有投影器可以向玩家输出其中的字段。 */
public record LiveRoomState(
        Details details,
        Seats seats,
        Round round,
        Map<Long, Long> totals,
        Map<Long, Long> partners,
        Vote vote,
        Long revision) {
    public record Details(
            String roomKey,
            String code,
            long owner,
            Long club,
            int maxRounds,
            boolean aa,
            Map<String, Object> options) {
        public Details {
            options = Map.copyOf(options);
        }
    }

    public record Seats(Map<Integer, Long> players, Map<Long, Map<String, Object>> roles) {
        public Seats {
            players = Collections.unmodifiableMap(new TreeMap<>(players));
            var copies = new LinkedHashMap<Long, Map<String, Object>>();
            roles.forEach((uid, role) -> copies.put(uid, Map.copyOf(role)));
            roles = Collections.unmodifiableMap(copies);
            if (players.size() > 3 || !new HashSet<>(players.values()).equals(roles.keySet()))
                throw new IllegalArgumentException("房间座位与角色不一致");
        }

        public List<Long> ordered() {
            return List.copyOf(players.values());
        }
    }

    public record Round(
            int number,
            String phase,
            Set<Long> ready,
            LandlordDeck.Deal deal,
            Map<Long, Integer> bids,
            long currentUid,
            int winningBid,
            LandlordPlayState play,
            Map<Long, Long> deltas,
            boolean charged,
            long dueAt) {
        public Round {
            ready = Set.copyOf(ready);
            bids = Map.copyOf(bids);
            deltas = Map.copyOf(deltas);
        }
    }

    public record Vote(long initiator, Map<Long, Integer> answers, long until) {
        public Vote {
            answers = Map.copyOf(answers);
        }
    }

    public LiveRoomState {
        Objects.requireNonNull(details);
        Objects.requireNonNull(seats);
        Objects.requireNonNull(round);
        totals = Map.copyOf(totals);
        partners = Map.copyOf(partners);
        if (revision == null) revision = 0L;
        if (revision < 0) throw new IllegalArgumentException("房间版本无效");
    }

    public LiveRoomState withRound(Round next) {
        return new LiveRoomState(details, seats, next, totals, partners, vote, revision);
    }

    public LiveRoomState withVote(Vote next) {
        return new LiveRoomState(details, seats, round, totals, partners, next, revision);
    }

    public LiveRoomState withRevision(long next) {
        return new LiveRoomState(details, seats, round, totals, partners, vote, next);
    }

    /** 旧持久快照没有传输标识，使用已经校验的运行时元数据补齐。 */
    public LiveRoomState withRuntimeIdentity(String key, long version) {
        if (details.roomKey() != null && !details.roomKey().equals(key)
                || revision != 0 && revision != version) {
            throw new IllegalArgumentException("房间状态与运行时版本不一致");
        }
        var identified =
                new Details(
                        key,
                        details.code(),
                        details.owner(),
                        details.club(),
                        details.maxRounds(),
                        details.aa(),
                        details.options());
        return new LiveRoomState(identified, seats, round, totals, partners, vote, version);
    }
}
