package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.Map;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原大厅主动查询实物入口：可查询关闭状态，但不能领取或绕过认证。 */
class LobbyAvailabilityTest {
    private static final String TOKEN = "a".repeat(43);

    private static final class Fixture implements AutoCloseable {
        final GameJsonCodec codec = new GameJsonCodec();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ConnectionRegistry registry = new ConnectionRegistry(Clock.systemUTC());
        final GuestIdentity identity = new GuestIdentity(1, "availability-fixture", 100001, 0, true);
        final ConnectionRegistry.Binding binding = registry.bind(identity, channel, TOKEN);
        final GameSessionService sessions = new GameSessionService(null, codec, null) {
            @Override public GuestIdentity verify(String token) {
                assertEquals(TOKEN, token);
                return identity;
            }
        };
        final GameRateLimiter limiter = new GameRateLimiter(null, Clock.systemUTC()) {
            @Override public void account(long uid) { assertEquals(identity.uid(), uid); }
        };
        final GameDispatcher dispatcher = new GameDispatcher(null, sessions, null, registry,
                null, null, limiter, null, null, new ClubWireRouter(null));

        GameDispatcher.Result request(String command, ConnectionRegistry.Binding current) {
            var packet = codec.request(codec.write(Map.of("path", "logic", "reqData",
                    Map.of("api", "freephone", "c", command, "sessionToken", TOKEN))));
            return dispatcher.dispatch(packet, current, channel);
        }
        @Override public void close() { registry.remove(binding); channel.finishAndReleaseAll(); }
    }

    @Test void lobbyCanQueryClosedGiftEntryWithoutMethodError() {
        try (var f = new Fixture()) {
            var result = f.request("getStatus", f.binding);
            assertEquals(Map.of("state", 0, "status", 0), result.ack());
            assertSame(f.binding, result.binding());
            assertTrue(result.notifications().isEmpty());
        }
    }

    @Test void availabilityQueryStillRequiresAuthenticatedConnection() {
        try (var f = new Fixture()) {
            assertEquals(-5, assertThrows(GameFailure.class,
                    () -> f.request("getStatus", null)).state());
        }
    }

    @Test void statusQueryDoesNotEnableGiftExecution() {
        try (var f = new Fixture()) {
            assertEquals(-2, assertThrows(GameFailure.class,
                    () -> f.request("receiveCard", f.binding)).state());
        }
    }
}
