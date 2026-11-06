package com.ruoyi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.ruoyi.game.admin.GameAdminService;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.persistence.mapper.*;
import com.ruoyi.game.protocol.GameJsonCodec;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"game.ws.port=0"})
class GameFoundationIntegrationTest {
    private static final String DB_PASSWORD=SecureTokens.create();
    private static final String REDIS_PASSWORD=SecureTokens.create();
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse("mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902"))
            .withDatabaseName("foundation_test").withUsername("foundation_test").withPassword(DB_PASSWORD)
            .withUrlParam("allowPublicKeyRetrieval","true").withUrlParam("serverTimezone","UTC");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis@sha256:f245f4c83b84a9e9a0588da1c9456e579455310f0945d5db52cd2ba27f2000cc"))
            .withExposedPorts(6379).withCommand("redis-server","--requirepass",REDIS_PASSWORD);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.druid.master.url",MYSQL::getJdbcUrl);
        properties.add("spring.datasource.druid.master.username",MYSQL::getUsername);
        properties.add("spring.datasource.druid.master.password",MYSQL::getPassword);
        properties.add("spring.data.redis.host",REDIS::getHost);
        properties.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password",()->REDIS_PASSWORD);
        properties.add("token.secret",SecureTokens::create);
    }
    @Autowired GuestAuthService auth;
    @Autowired GameSessionService sessions;
    @Autowired GameAdminService admin;
    @Autowired GameJsonCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @MockitoSpyBean LobbyMapper lobby;
    @MockitoSpyBean IdentityMapper identities;

    @Test void identityAndLoginDeduplicationSurviveLossOfRedis() throws Exception {
        String token=auth.createGuest();GuestIdentity identity=auth.verify(token);
        auth.recordLogin(identity,"REG");auth.recordLogin(identity,"RESETFD");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_login_daily WHERE uid=?",Integer.class,identity.uid()));
        assertEquals(2,jdbc.queryForObject("SELECT login_count FROM player_login_daily WHERE uid=?",Integer.class,identity.uid()));
        String session=sessions.create(identity);assertEquals(identity.uid(),sessions.verify(session).uid());
        assertEquals(0,REDIS.execInContainer("redis-cli","-a",REDIS_PASSWORD,"FLUSHDB").getExitCode());
        assertEquals(identity.uid(),auth.verify(token).uid());
        assertThrows(GameFailure.class,()->sessions.verify(session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_role WHERE account_id=?",Integer.class,identity.accountId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM guest_credential WHERE token_hash=?",Integer.class,token));
    }

    @Test void lateInitializationFailureRollsBackAccountCredentialRoleAndWallet() {
        List<String> tables=List.of("user_account","guest_credential","player_role","wallet_account");
        Map<String,Integer> counts=new HashMap<>();tables.forEach(table->counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)));
        doThrow(new IllegalStateException("injected failure")).when(lobby).createKoi(anyLong(),any());
        assertThrows(IllegalStateException.class,auth::createGuest);
        tables.forEach(table->assertEquals(counts.get(table),jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table));
    }

    @Test void parallelAdminRetriesHaveOneAuditAndOneSessionRevocation() throws Exception {
        GuestIdentity identity=auth.verify(auth.createGuest());String key=UUID.randomUUID().toString();
        try(ExecutorService workers=Executors.newFixedThreadPool(8)){
            List<Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<16;i++)results.add(workers.submit(()->admin.command(1,identity.uid(),"kick","并发幂等验收",key)));
            Set<Object> ids=new HashSet<>();for(var result:results)ids.add(result.get(10,TimeUnit.SECONDS).get("operationId"));
            assertEquals(1,ids.size());
        }
        assertEquals(1,jdbc.queryForObject("SELECT session_version FROM user_account WHERE id=?",Integer.class,identity.accountId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=? AND action='kick'",Integer.class,Long.toString(identity.uid())));
    }

    @Test void authenticationStorageFailureUsesHttp503AndPreservesCredential() throws Exception {
        String token=auth.createGuest();String hash=SecureTokens.hash(token);
        doThrow(new DataAccessResourceFailureException("storage unavailable")).when(identities).credential(hash);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/auth/check"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(codec.write(Map.of("token",token)))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(503,response.statusCode());assertFalse(response.body().contains(token));
        assertFalse(codec.read(response.body()).has("code"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM guest_credential WHERE token_hash=?",String.class,hash));
    }

    @Test void finalRuntimeContainsOnlyDruidAsConnectionPool(){
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.zaxxer.hikari.HikariDataSource"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE",Integer.class)>=5);
    }
}
