package com.ruoyi.game.transport;

import com.ruoyi.game.auth.*;
import com.ruoyi.game.games.landlord.LandlordWireActions;
import com.ruoyi.game.protocol.*;
import com.ruoyi.game.room.LiveRoomMovies;
import com.ruoyi.game.room.LiveRoomService;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.*;

/** 房间原协议适配，牌桌命令直接返回邮箱阶段。 */
@Component
public class RoomWireRouter {
    private static final Set<String> ROUTES =
            Set.of(
                    "movie/getMovie",
                    "room/createRoom",
                    "room/joinRoom",
                    "room/leaveRoom",
                    "room/disbandRoom",
                    "room/forceDisbandRoom",
                    "room/gameMessage",
                    "room/voteEndGame",
                    "room/checkVoteEndGame",
                    "room/getDefaultRoomConfig",
                    "room/setDefaultRoomConfig",
                    "room/setAutoCreate",
                    "club/getRoomList",
                    "club/getRoomInfos",
                    "game/returnGame",
                    "game/loginGame",
                    "game/checkGameCreate",
                    "game/appPause",
                    "game/appResume");
    private final LiveRoomService rooms;
    private final LiveRoomMovies movies;
    private final GameJsonCodec codec;
    private final ConnectionRegistry registry;

    public RoomWireRouter(
            LiveRoomService rooms,
            GameJsonCodec codec,
            ConnectionRegistry registry,
            LiveRoomMovies movies) {
        this.rooms = rooms;
        this.movies = movies;
        this.codec = codec;
        this.registry = registry;
    }

    public boolean supports(WireRequest request) {
        return ROUTES.contains(request.api() + "/" + request.command());
    }

    public CompletionStage<LiveRoomService.Reply> dispatch(
            WireRequest request, GuestIdentity actor) {
        JsonNode data = request.data();
        LiveRoomService.Reply response;
        switch (request.api() + "/" + request.command()) {
            case "movie/getMovie" ->
                    response =
                            new LiveRoomService.Reply(
                                    movies.movie(
                                            actor.uid(), GameJsonCodec.text(data, "movie_id", 36)),
                                    List.of());
            case "room/createRoom" -> {
                response =
                        rooms.create(
                                actor,
                                GameJsonCodec.text(data, "game_id", 4),
                                object(data, "createData"),
                                GameJsonCodec.text(data, "operationId", 36));
                if (Boolean.TRUE.equals(object(data, "createData").get("morenroom"))) {
                    var creation = response;
                    var room = (Map<?, ?>) response.ack().get("room");
                    return rooms.join(actor, Objects.toString(room.get("id")))
                            .thenApply(
                                    joined -> {
                                        var notices = new ArrayList<>(creation.notifications());
                                        notices.addAll(joined.notifications());
                                        return new LiveRoomService.Reply(joined.ack(), notices);
                                    });
                }
            }
            case "room/joinRoom" -> {
                return rooms.join(actor, code(data, "room_id"));
            }
            case "room/leaveRoom" -> {
                return rooms.leave(actor);
            }
            case "room/disbandRoom" -> {
                return rooms.close(
                        actor, data.hasNonNull("room_id") ? code(data, "room_id") : null);
            }
            case "room/forceDisbandRoom" -> {
                return rooms.forceClose(actor, code(data, "room_id"));
            }
            case "room/gameMessage" -> {
                return rooms.action(actor, LandlordWireActions.parse(data));
            }
            case "room/voteEndGame" -> {
                return rooms.vote(actor, integer(data, "result"));
            }
            case "room/checkVoteEndGame" -> response = rooms.voteStatus(actor.uid());
            case "room/getDefaultRoomConfig" ->
                    response = rooms.defaultConfig(actor.uid(), id(data, "club_id"));
            case "room/setDefaultRoomConfig" ->
                    response =
                            rooms.configure(
                                    actor.uid(),
                                    id(data, "club_id"),
                                    GameJsonCodec.text(data, "game_id", 4),
                                    object(data, "createData"));
            case "room/setAutoCreate" ->
                    response =
                            rooms.automatic(
                                    actor.uid(), id(data, "club_id"), flag(data, "auto_create"));
            case "club/getRoomList" ->
                    response = rooms.circleList(actor.uid(), id(data, "club_id"));
            case "club/getRoomInfos" -> {
                JsonNode ids = data.path("room_ids");
                if (!ids.isArray() || ids.size() > 6) throw bad();
                var codes = new ArrayList<String>();
                for (var value : ids) codes.add(code(value));
                response = rooms.infos(actor.uid(), codes);
            }
            case "game/returnGame" ->
                    response = new LiveRoomService.Reply(rooms.returning(actor.uid()), List.of());
            case "game/loginGame", "game/checkGameCreate" ->
                    response = rooms.initialize(actor.uid());
            case "game/appResume" -> response = rooms.voteStatus(actor.uid());
            case "game/appPause" ->
                    response = new LiveRoomService.Reply(Map.of("state", 0), List.of());
            default -> throw bad();
        }
        return CompletableFuture.completedFuture(response);
    }

    @EventListener
    public void timed(LiveRoomService.TimedNotice notice) {
        for (var item : notice.notifications())
            registry.push(item.recipients(), codec.write(item.packet()));
    }

    private Map<String, Object> object(JsonNode data, String key) {
        var value = data.path(key);
        if (!value.isObject()) throw bad();
        return codec.object(codec.write(value));
    }

    private static long id(JsonNode data, String key) {
        String text = data.path(key).asString();
        if (!text.matches("[1-9][0-9]{0,8}")) throw bad();
        return Long.parseLong(text);
    }

    private static String code(JsonNode data, String key) {
        return code(data.path(key));
    }

    private static String code(JsonNode value) {
        String text = value.isString() || value.isIntegralNumber() ? value.asString() : "";
        if (!text.matches("[1-9][0-9]{5}")) throw bad();
        return text;
    }

    private static int integer(JsonNode data, String key) {
        var value = data.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw bad();
        return value.asInt();
    }

    private static boolean flag(JsonNode data, String key) {
        var value = data.path(key);
        if (value.isBoolean()) return value.asBoolean();
        int flag = integer(data, key);
        if (flag != 0 && flag != 1) throw bad();
        return flag == 1;
    }

    private static GameFailure bad() {
        return new GameFailure(-4, "房间请求参数无效");
    }
}
