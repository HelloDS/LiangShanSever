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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
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
import com.ruoyi.game.lobby.ReportService;
import com.ruoyi.game.economy.WalletPosting;
import com.ruoyi.game.economy.WalletPostingService;
import com.ruoyi.game.economy.WalletPosting.*;
import com.ruoyi.game.economy.WalletPosting.Currency;
import com.ruoyi.game.games.landlord.*;
import com.ruoyi.game.room.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"game.ws.port=0","game.outbox.worker-enabled=false","game.reconciliation.worker-enabled=false","game.online-history.enabled=false"})
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
        properties.add("game.pii-key",SecureTokens::create);
    }
    @Autowired com.ruoyi.game.outbox.OutboxStore outbox;
    @Autowired com.ruoyi.game.outbox.OutboxAdminService outboxAdmin;
    @MockitoSpyBean OutboxMapper outboxMapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.ruoyi.game.room.RoomAdminService roomAdmin;
    @Autowired com.ruoyi.game.room.RoomJournal journal;
    @Autowired com.ruoyi.game.room.RoomEventConsumer roomEvents;
    @MockitoSpyBean com.ruoyi.web.game.RoomTransactionBoundary roomTransactions;
    @MockitoSpyBean RoomJournalMapper roomJournalMapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("masterDataSource") javax.sql.DataSource generalPool;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("roomDataSource") javax.sql.DataSource roomPool;
    private static final java.util.concurrent.atomic.AtomicInteger ROOM_CODES=new java.util.concurrent.atomic.AtomicInteger(700000);
    @Autowired GuestAuthService auth;
    @MockitoSpyBean GameSessionService sessions;
    @Autowired GameLoginService login;
    @Autowired GameAdminService admin;
    @Autowired GameJsonCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ReportService reports;
    @MockitoSpyBean LobbyMapper lobby;
    @MockitoSpyBean IdentityMapper identities;
    @Autowired com.ruoyi.game.club.ClubService clubs;
    @Autowired com.ruoyi.game.club.ClubAdminService clubAdmin;
    @Autowired com.ruoyi.game.economy.WalletPostingService wallets;
    @MockitoSpyBean WalletMapper walletMapper;
    @Autowired com.ruoyi.game.economy.WalletAdminService walletAdmin;
    @Autowired com.ruoyi.game.economy.WalletReconciliationJobs reconciliationJobs;
    @Autowired com.ruoyi.game.economy.WalletReconciliationAdmin reconciliationAdmin;
    @MockitoSpyBean WalletReconciliationMapper reconciliationMapper;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sqlSessions;
    @Autowired com.ruoyi.game.admin.AuditService businessAudit;
    @MockitoSpyBean GameAdminMapper adminMapper;
    @Autowired com.ruoyi.game.admin.OnlineHistoryService onlineHistory;
    @MockitoSpyBean OnlineHistoryMapper onlineHistoryMapper;

    @Autowired com.ruoyi.game.transport.ConnectionRegistry connections;
    @Autowired com.ruoyi.game.transport.OnlinePresence presence;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test void onlineCountsRequireLiveConnectionValidIdentityAndRebuiltIndex(){
        GuestIdentity identity=auth.verify(auth.createGuest());
        var channel=new io.netty.channel.embedded.EmbeddedChannel();
        var binding=connections.bind(identity,channel,sessions.create(identity));
        try{
            presence.refresh();assertTrue(presence.sample(true).uids().contains(identity.uid()));
            jdbc.update("UPDATE user_account SET session_version=session_version+1 WHERE id=?",identity.accountId());
            assertFalse(presence.sample(true).uids().contains(identity.uid()));
            jdbc.update("UPDATE user_account SET session_version=session_version-1 WHERE id=?",identity.accountId());
            redis.opsForZSet().remove("game:online:index",Long.toString(identity.uid()));
            assertEquals("REBUILDING",presence.sample(true).dataStatus());assertNull(presence.sample(true).uids());
            presence.refresh();assertTrue(presence.sample(true).uids().contains(identity.uid()));
            jdbc.update("UPDATE guest_credential SET status='REVOKED' WHERE id=?",identity.credentialId());
            assertFalse(presence.sample(true).uids().contains(identity.uid()));
            jdbc.update("UPDATE guest_credential SET status='ACTIVE' WHERE id=?",identity.credentialId());
            assertTrue(connections.remove(binding));
            assertFalse(presence.sample(true).uids().contains(identity.uid()));
        }finally{connections.remove(binding);presence.remove(binding);channel.finishAndReleaseAll();}
    }

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
    @Test void newGameSessionReplacesOldTokenForTheSamePlayer(){
        GuestIdentity identity=auth.verify(auth.createGuest());
        String old=sessions.create(identity),fresh=sessions.create(identity);
        assertThrows(GameFailure.class,()->sessions.verify(old));
        assertEquals(identity.uid(),sessions.verify(fresh).uid());
    }
    @Test void redisSessionFailureDoesNotCountAsSuccessfulLogin(){
        GuestIdentity identity=auth.verify(auth.createGuest());
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected failure")).when(sessions).create(identity);
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,()->login.login(identity,"REG",null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM player_login_daily WHERE uid=?",Integer.class,identity.uid()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM player_login_event WHERE uid=?",Integer.class,identity.uid()));
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
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE biz_key=?",Integer.class,"player-revoked:"+identity.uid()+":1"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=? AND action='kick'",Integer.class,Long.toString(identity.uid())));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit a JOIN admin_operation o ON o.operation_id=a.operation_id AND o.actor_id=a.operator_id AND o.action=a.action AND o.target=a.target WHERE a.target=? AND a.action='kick' AND o.status='COMPLETED'",Integer.class,Long.toString(identity.uid())));
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

    @Test void anonymousReportDiscardsContactAndHidesReporterInDefaultList(){
        GuestIdentity identity=auth.verify(auth.createGuest());
        reports.submit(identity.uid(),new ReportService.Submission("名称违规",-1,"13800000000","test_contact","匿名测试说明"));
        var row=jdbc.queryForMap("SELECT phone_cipher,wechat_cipher,id FROM user_report WHERE reporter_uid=?",identity.uid());
        assertNull(row.get("phone_cipher"));assertNull(row.get("wechat_cipher"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)reports.list(1,"PENDING",1,200).get("rows");
        var visible=rows.stream().filter(value->row.get("id").equals(value.get("id"))).findFirst().orElseThrow();
        assertNull(visible.get("reporterUid"));assertEquals("匿名",visible.get("contactMask"));
    }

    @Test void reportContactIsEncryptedAndFullReadIsSeparatelyAudited(){
        GuestIdentity identity=auth.verify(auth.createGuest());
        reports.submit(identity.uid(),new ReportService.Submission("游戏作弊",1,"13800000000","test_contact","联系方式加密测试"));
        var row=jdbc.queryForMap("SELECT id,phone_cipher FROM user_report WHERE reporter_uid=?",identity.uid());
        assertTrue(((String)row.get("phone_cipher")).startsWith("v1."));
        assertFalse(((String)row.get("phone_cipher")).contains("13800000000"));
        assertEquals("13800000000",reports.contacts(1,(String)row.get("id"),"本地权限验收").get("telephone"));
        String audit=jdbc.queryForObject("SELECT after_json FROM admin_audit WHERE target=? AND action='report:contact'",String.class,row.get("id"));
        assertFalse(audit.contains("13800000000"));assertFalse(audit.contains("test_contact"));
    }

    @Nested class OnlineMinuteHistory {
        private final java.time.LocalDate day=java.time.LocalDate.of(2026,11,6);
        private final String node=UUID.randomUUID().toString();
        @BeforeEach void clearHistory(){jdbc.update("DELETE FROM online_minute_history");}
        @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(){return (List<Map<String,Object>>)onlineHistory.day(1,day).get("rows");}
        @Test void realMinutePersistsExactFirstObservationAcrossRepeatedRecord(){
            var observed=java.time.Instant.parse("2026-11-06T01:02:03.123456789Z");onlineHistory.record(observed,"AVAILABLE",0,node);
            onlineHistory.record(observed.plusSeconds(5),"AVAILABLE",7,UUID.randomUUID().toString());
            var rows=rows();assertEquals(1,rows.size());assertEquals(0,rows.getFirst().get("onlineCount"));assertEquals("2026-11-06T01:02:00Z",rows.getFirst().get("sampleMinute"));assertEquals("2026-11-06T01:02:03.123456Z",rows.getFirst().get("observedAt"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM online_minute_history",Integer.class));
        }
        @Test void unknownStateRemainsExplicitNullAndCannotBecomeZero(){
            var observed=java.time.Instant.parse("2026-11-06T01:03:00Z");onlineHistory.record(observed,"UNAVAILABLE",null,node);onlineHistory.record(observed.plusSeconds(2),"AVAILABLE",0,node);
            var row=rows().getFirst();assertEquals("UNAVAILABLE",row.get("dataStatus"));assertTrue(row.containsKey("onlineCount"));assertNull(row.get("onlineCount"));
            assertThrows(IllegalArgumentException.class,()->onlineHistory.record(observed,"REBUILDING",0,node));assertThrows(IllegalArgumentException.class,()->onlineHistory.record(observed,"AVAILABLE",null,node));
            var rejected=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO online_minute_history VALUES('2026-11-06 02:00:00','2026-11-06 02:00:00','UNAVAILABLE',0,?)",node));
            assertInstanceOf(java.sql.SQLException.class,rejected.getMostSpecificCause());assertEquals(3819,((java.sql.SQLException)rejected.getMostSpecificCause()).getErrorCode());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM online_minute_history",Integer.class));
        }
        @Test void utcStorageUsesBeijingDayAndDoesNotFillMissingMinutes(){
            for(String at:List.of("2026-11-05T15:59:00Z","2026-11-05T16:00:00Z","2026-11-06T15:59:00Z","2026-11-06T16:00:00Z"))onlineHistory.record(java.time.Instant.parse(at),"AVAILABLE",2,node);
            var points=rows();assertEquals(2,points.size());assertEquals("2026-11-05T16:00:00Z",points.getFirst().get("sampleMinute"));assertEquals("2026-11-06T15:59:00Z",points.getLast().get("sampleMinute"));
            assertEquals(false,onlineHistory.day(1,day).get("samplingEnabled"));
        }
        @Test void currentPlatformScopeAndStorageAvailabilityAreCheckedForEveryRead(){
            long actor=900041;assertThrows(SecurityException.class,()->onlineHistory.day(actor,day));jdbc.update("INSERT INTO admin_data_scope VALUES(?,'PLATFORM','*')",actor);
            assertDoesNotThrow(()->onlineHistory.day(actor,day));jdbc.update("DELETE FROM admin_data_scope WHERE principal_id=?",actor);assertThrows(SecurityException.class,()->onlineHistory.day(actor,day));
            doThrow(new DataAccessResourceFailureException("history unavailable")).when(onlineHistoryMapper).day(any(),any());assertThrows(com.ruoyi.game.admin.GameDataUnavailable.class,()->onlineHistory.day(1,day));
            assertThrows(IllegalArgumentException.class,()->onlineHistory.day(1,java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1)));
        }
    }

    @Nested class BusinessAudit {
        private final java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        @SuppressWarnings("unchecked") private Map<String,Object> record(long id){return (Map<String,Object>)businessAudit.detail(1,id).get("record");}
        private long id(String target){return jdbc.queryForObject("SELECT id FROM admin_audit WHERE target=?",Long.class,target);}
        @Test void commandLinksExactAuditAndReadQueriesDoNotRepeatItsEffect(){
            var identity=auth.verify(auth.createGuest());String key=UUID.randomUUID().toString();
            var first=admin.command(1,identity.uid(),"kick","审计关联验收",key);String operation=(String)first.get("operationId");
            var result=businessAudit.list(1,1L,"kick",Long.toString(identity.uid()),operation,today,today,1,50);
            assertEquals(1L,result.get("total"));assertEquals(1,((List<?>)result.get("rows")).size());
            var row=record(id(Long.toString(identity.uid())));assertEquals(operation,row.get("operationId"));assertEquals("COMPLETED",row.get("operationStatus"));
            assertEquals(Map.of("status","ACTIVE","sessionVersion","1"),row.get("after"));
            assertTrue(row.get("createdAt").toString().endsWith("Z"));assertFalse(codec.write(row).contains(key));assertFalse(row.containsKey("beforeJson"));assertFalse(row.containsKey("requestHash"));
            assertEquals(first.get("operationId"),admin.command(1,identity.uid(),"kick","审计关联验收",key).get("operationId"));
            assertEquals(1,jdbc.queryForObject("SELECT session_version FROM user_account WHERE id=?",Integer.class,identity.accountId()));
        }
        @Test void opaqueRequestKeysRemainCaseSensitiveAndExactRetriesStillReuseTheirAudit(){
            var identity=auth.verify(auth.createGuest());String suffix=UUID.randomUUID().toString(),upper="A"+suffix,lower="a"+suffix;
            var first=admin.command(1,identity.uid(),"kick","不透明请求键大小写",upper);
            var second=admin.command(1,identity.uid(),"kick","不透明请求键大小写",lower);
            assertNotEquals(first.get("operationId"),second.get("operationId"));
            assertEquals(first.get("operationId"),admin.command(1,identity.uid(),"kick","不透明请求键大小写",upper).get("operationId"));
            assertEquals(2,jdbc.queryForObject("SELECT session_version FROM user_account WHERE id=?",Integer.class,identity.accountId()));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?",Integer.class,Long.toString(identity.uid())));
        }
        @Test void unconfirmedAuditInsertRollsBackEffectCommandAndOutbox(){
            var identity=auth.verify(auth.createGuest());String key=UUID.randomUUID().toString();
            doReturn(0).when(adminMapper).auditCommand(anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
            assertThrows(IllegalStateException.class,()->admin.command(1,identity.uid(),"ban","审计写入失败回滚",key));
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,identity.accountId()));
            assertEquals(0,jdbc.queryForObject("SELECT session_version FROM user_account WHERE id=?",Integer.class,identity.accountId()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation WHERE request_key=?",Integer.class,key));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE biz_key=?",Integer.class,"player-revoked:"+identity.uid()+":1"));
        }
        @Test void unconfirmedCompletionRollsBackAlreadyInsertedAudit(){
            var identity=auth.verify(auth.createGuest());String key=UUID.randomUUID().toString();
            doReturn(0).when(adminMapper).complete(anyString(),anyString(),any());
            assertThrows(IllegalStateException.class,()->admin.command(1,identity.uid(),"ban","完成状态失败回滚",key));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?",Integer.class,Long.toString(identity.uid())));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation WHERE request_key=?",Integer.class,key));
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,identity.accountId()));
        }
        @Test void unlinkedSummariesDoNotExposeNestedSecretsOrGuessOperation(){
            String target=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO admin_audit(operator_id,action,target,reason,before_json,after_json,created_at) VALUES(1,'kick',?,'旧记录精确摘要',?,?,UTC_TIMESTAMP(6))",target,
                    "{\"sessionVersion\":9007199254740993,\"sessionToken\":\"private-audit-sentinel\",\"status\":{\"token\":\"nested-audit-sentinel\"}}","{\"status\":\"ACTIVE\",\"password\":\"secret-audit-sentinel\"}");
            var row=record(id(target));assertNull(row.get("operationId"));assertNull(row.get("operationStatus"));
            assertEquals(Map.of("sessionVersion","9007199254740993"),row.get("before"));assertEquals(Map.of("status","ACTIVE"),row.get("after"));
            String visible=codec.write(row);assertFalse(visible.contains("sentinel"));assertFalse(visible.contains("beforeJson"));
            jdbc.update("UPDATE admin_audit SET action='future:private' WHERE target=?",target);row=record(id(target));assertEquals(false,row.get("summaryAvailable"));assertEquals(Map.of(),row.get("before"));
        }
        @Test void platformScopeIsRequiredOnEveryQueryAndStorageFailureIsUnknown(){
            long actor=900031;assertThrows(SecurityException.class,()->businessAudit.list(actor,null,null,null,null,today,today,1,50));
            jdbc.update("INSERT INTO admin_data_scope VALUES(?,'CLUB','100001')",actor);
            assertThrows(SecurityException.class,()->businessAudit.detail(actor,1));
            jdbc.update("INSERT INTO admin_data_scope VALUES(?,'PLATFORM','*')",actor);
            assertDoesNotThrow(()->businessAudit.list(actor,null,null,null,null,today,today,1,50));
            jdbc.update("DELETE FROM admin_data_scope WHERE principal_id=? AND scope_type='PLATFORM'",actor);
            assertThrows(SecurityException.class,()->businessAudit.list(actor,null,null,null,null,today,today,1,50));
            doThrow(new DataAccessResourceFailureException("unavailable")).when(adminMapper).platformScope(1);
            assertThrows(com.ruoyi.game.admin.GameDataUnavailable.class,()->businessAudit.list(1,null,null,null,null,today,today,1,50));
        }
        @Test void beijingDateBoundsAndStableOrderingDoNotLeakNeighbouringDay(){
            String target=UUID.randomUUID().toString();var day=java.time.LocalDate.of(2026,11,6);
            for(String stamp:List.of("2026-11-05 15:59:59.999999","2026-11-05 16:00:00.000000","2026-11-06 15:59:59.999999","2026-11-06 16:00:00.000000"))
                jdbc.update("INSERT INTO admin_audit(operator_id,action,target,reason,created_at) VALUES(1,'report:contact',?,'北京日期边界',?)",target,stamp);
            var result=businessAudit.list(1,null,null,target,null,day,day,1,1);assertEquals(2L,result.get("total"));
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("rows");assertEquals(1,rows.size());assertEquals("2026-11-06T15:59:59.999999Z",rows.getFirst().get("createdAt"));
            assertThrows(IllegalArgumentException.class,()->businessAudit.list(1,null,null,null,null,day,day.plusDays(31),1,50));
            assertThrows(IllegalArgumentException.class,()->businessAudit.list(1,null,"bad%'",null,null,day,day,1,50));
            assertThrows(IllegalArgumentException.class,()->businessAudit.list(1,null,null,null,"not-a-uuid",day,day,1,50));
            assertThrows(IllegalArgumentException.class,()->businessAudit.list(1,null,null,null,null,day,day,1,201));
        }
    }

    @Nested class DurableOutbox {
        private String append(long uid){
            return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(status->outbox.playerRevoked(uid,1));
        }
        private long uid(){return auth.verify(auth.createGuest()).uid();}
        private com.ruoyi.game.outbox.OutboxEvent claim(String id){
            for(int i=0;i<20;i++)for(var event:outbox.claim(16)){if(event.id().equals(id))return event;else outbox.complete(event);}
            throw new AssertionError("目标事件未领取");
        }
        @Test void outboxInsertFailureRollsBackPlayerCommandAndAudit(){
            GuestIdentity identity=auth.verify(auth.createGuest());
            doThrow(new IllegalStateException("injected outbox failure")).when(outboxMapper)
                    .append(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any());
            assertThrows(IllegalStateException.class,()->admin.command(1,identity.uid(),"ban","事务末尾故障验收",UUID.randomUUID().toString()));
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,identity.accountId()));
            assertEquals(0,jdbc.queryForObject("SELECT session_version FROM user_account WHERE id=?",Integer.class,identity.accountId()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?",Integer.class,Long.toString(identity.uid())));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE biz_key=?",Integer.class,"player-revoked:"+identity.uid()+":1"));
        }
        @Test void appendRequiresBusinessTransactionAndStableKeyIsUnique(){
            long uid=uid();
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->outbox.playerRevoked(uid,1));
            String first=append(uid);assertEquals(first,append(uid));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE biz_key=?",Integer.class,"player-revoked:"+uid+":1"));
        }
        private void drainReady(){
            for(int batch=0;batch<100;batch++){var tasks=outbox.claim(16);if(tasks.isEmpty())return;tasks.forEach(event->assertTrue(outbox.complete(event)));}
            fail("测试待领取任务未排空");
        }
        @Test void batchClaimUsesOneFencedLeaseAndReturnsEveryLockedEvent(){
            drainReady();Set<String> expected=new HashSet<>();for(int i=0;i<6;i++)expected.add(append(uid()));
            var tasks=outbox.claim(6);assertEquals(expected,new HashSet<>(tasks.stream().map(com.ruoyi.game.outbox.OutboxEvent::id).toList()));
            assertEquals(1,tasks.stream().map(com.ruoyi.game.outbox.OutboxEvent::leaseToken).distinct().count());
            for(var event:tasks){assertEquals(1,event.attempts());assertTrue(outbox.complete(event));assertFalse(outbox.complete(event));}
        }
        @Test void batchReadFailureRollsBackEveryLeaseAndAttempt(){
            drainReady();List<String> ids=new ArrayList<>();for(int i=0;i<6;i++)ids.add(append(uid()));
            doThrow(new IllegalStateException("injected claimed batch read failure")).when(outboxMapper).claimedBatch(anyList(),anyString());
            assertThrows(IllegalStateException.class,()->outbox.claim(6));
            for(String id:ids){assertEquals("READY",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE id=?",Integer.class,id));assertNull(jdbc.queryForObject("SELECT lease_token FROM outbox_event WHERE id=?",String.class,id));}
        }

        @Test void backlogQueryUsesCoveringStatusRangesDespiteLargeCompletedHistory(){
            drainReady();for(int i=0;i<6;i++)append(uid());var claimed=outbox.claim(2);
            jdbc.update("UPDATE outbox_event SET status='DEAD',lease_token=NULL,lease_until=NULL WHERE id=?",claimed.getFirst().id());
            var before=outbox.backlog();
            assertEquals(20000,jdbc.update("INSERT INTO outbox_event(id,biz_key,event_type,schema_version,rule_version,payload,request_hash,status,retry_at,occurred_at,completed_at) SELECT UUID(),CONCAT('backlog-fixture:',UUID()),'TEST_LOAD',1,'fixture-v1','{}',REPEAT('a',64),'DONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM sys_menu a CROSS JOIN sys_menu b CROSS JOIN sys_menu c LIMIT 20000"));
            jdbc.execute("ANALYZE TABLE outbox_event");var after=outbox.backlog();
            assertEquals(before.ready(),after.ready());assertEquals(before.claimed(),after.claimed());assertEquals(before.dead(),after.dead());
            String statement=sqlSessions.getConfiguration().getMappedStatement(OutboxMapper.class.getName()+".backlog").getBoundSql(null).getSql();
            var plan=jdbc.queryForList("EXPLAIN "+statement);assertEquals(3,plan.size());
            for(var branch:plan){assertEquals("idx_outbox_backlog",branch.get("key"));assertTrue(Set.of("ref","range").contains(branch.get("type")));assertTrue(((Number)branch.get("rows")).longValue()<1000,"不应为少量积压扫描大量 DONE 历史");}
        }

        @Test void concurrentClaimsAreExclusiveAndStaleLeaseCannotCompleteReclaimedTask() throws Exception {
            Set<String> expected=new HashSet<>();for(int i=0;i<20;i++)expected.add(append(uid()));
            Set<String> seen=ConcurrentHashMap.newKeySet();
            try(ExecutorService workers=Executors.newFixedThreadPool(4)){
                List<Future<?>> results=new ArrayList<>();
                for(int i=0;i<4;i++)results.add(workers.submit(()->{
                    for(int n=0;n<8;n++)for(var event:outbox.claim(16)){
                        assertTrue(seen.add(event.id()),"同一租期不能重复领取");assertTrue(outbox.complete(event));
                    }
                }));
                for(var result:results)result.get(10,TimeUnit.SECONDS);
            }
            assertTrue(seen.containsAll(expected));
            String id=append(uid());var old=claim(id);
            jdbc.update("UPDATE outbox_event SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
            var fresh=claim(id);assertNotEquals(old.leaseToken(),fresh.leaseToken());assertEquals(2,fresh.attempts());
            assertFalse(outbox.complete(old));assertFalse(outbox.failed(old,"OLD_WORKER"));assertTrue(outbox.complete(fresh));
        }
        @Test void completingALockedEventTimesOutWithoutOverwritingTheLease() throws Exception {
            String id=append(uid());var event=claim(id);
            try(var connection=generalPool.getConnection();ExecutorService worker=Executors.newSingleThreadExecutor()){
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("SELECT id FROM outbox_event WHERE id=? FOR UPDATE")){statement.setString(1,id);statement.executeQuery().close();}
                long began=System.nanoTime();var completion=worker.submit(()->outbox.complete(event));
                try{assertThrows(ExecutionException.class,()->completion.get(8,TimeUnit.SECONDS));assertTrue(System.nanoTime()-began<8_000_000_000L);}
                finally{connection.rollback();}
            }
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id));assertTrue(outbox.complete(event));
        }

        @Test void boundedRetryBecomesDeadAndManualRetryPreservesEventAndAudit(){
            String id=append(uid());
            for(int i=1;i<=5;i++){
                var event=claim(id);assertEquals(i,event.attempts());assertTrue(outbox.failed(event,"TEST_FAILURE"));
                jdbc.update("UPDATE outbox_event SET retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
            }
            assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id));
            assertThrows(SecurityException.class,()->outboxAdmin.retry(999,id,"跨范围重试验收",UUID.randomUUID().toString()));
            String key=UUID.randomUUID().toString();
            var first=outboxAdmin.retry(1,id,"人工排障重试验收",key);var again=outboxAdmin.retry(1,id,"人工排障重试验收",key);
            assertEquals(first.get("operationId"),again.get("operationId"));
            assertEquals(1,jdbc.queryForObject("SELECT manual_retries FROM outbox_event WHERE id=?",Integer.class,id));
            assertEquals(5,jdbc.queryForObject("SELECT total_attempts FROM outbox_event WHERE id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='outbox:retry' AND target=?",Integer.class,id));
            var event=claim(id);assertEquals(1,event.attempts());assertTrue(outbox.complete(event));
            assertEquals(6,jdbc.queryForObject("SELECT total_attempts FROM outbox_event WHERE id=?",Integer.class,id));
        }
        @Test void finalExpiredLeaseEntersDeadAndAdminQueryIsScopedAndDateBounded(){
            String id=append(uid());var event=claim(id);
            jdbc.update("UPDATE outbox_event SET attempt_count=5,total_attempts=5,lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
            outbox.claim(16);
            assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id));assertFalse(outbox.complete(event));
            var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertThrows(SecurityException.class,()->outboxAdmin.list(999,null,today,today,1,50));
            assertThrows(IllegalArgumentException.class,()->outboxAdmin.list(1,null,today.minusDays(31),today,1,50));
            assertTrue(((Number)outboxAdmin.list(1,"DEAD",today,today,1,50).get("total")).longValue()>=1);
            assertTrue(outbox.backlog().dead()>=1);
        }
    }

    @Nested class RoomStorage {
        private record Fixture(GuestIdentity owner,String room,com.ruoyi.game.room.RoomSnapshot<String> state){ }
        private Fixture room(){return room(auth.verify(auth.createGuest()),Integer.toString(ROOM_CODES.incrementAndGet()));}
        private Fixture room(GuestIdentity owner,String code){
            String id=UUID.randomUUID().toString(),json="{\"value\":0}",hash=journal.stateHash(json);
            jdbc.update("INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,payer_uid,options_json,rule_version,is_test,created_at) VALUES(?,?,'6003',?,?,?,'{}','fixture-v1',TRUE,UTC_TIMESTAMP(6))",id,code,owner.uid(),owner.uid(),owner.uid());
            jdbc.update("INSERT INTO active_room_code(room_code,room_id) VALUES(?,?)",code,id);
            jdbc.update("INSERT INTO room_runtime(room_id,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at) VALUES(?,?,?,?,1,'counter-v1',UTC_TIMESTAMP(6))",id,hash,json,hash);
            var recovery=journal.claim(id,UUID.randomUUID().toString(),1,"counter-v1");
            return new Fixture(owner,id,new com.ruoyi.game.room.RoomSnapshot<>(id,recovery.ownerEpoch(),0,0,"WAITING",json));
        }
        private com.ruoyi.game.room.RoomJournalCommand change(Fixture fixture,com.ruoyi.game.room.RoomSnapshot<String> previous,int value,boolean checkpoint,List<WalletPosting> postings,com.ruoyi.game.room.RoomJournalCommand.SeatChange seat,String phase){
            int before=codec.read(previous.state()).get("value").asInt();
            var candidate=new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),previous.ownerEpoch(),previous.version()+1,previous.round(),phase,"{\"value\":"+value+"}");
            return new com.ruoyi.game.room.RoomJournalCommand(previous,candidate,"CLOSED".equals(phase)?"ROOM_CLOSE":"TEST_INCREMENT","{\"delta\":"+(value-before)+"}","fixture-v1",1,"counter-v1",fixture.owner(),postings,seat,checkpoint,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        private com.ruoyi.game.room.RoomJournalCommand change(Fixture fixture,com.ruoyi.game.room.RoomSnapshot<String> previous,int value,boolean checkpoint){return change(fixture,previous,value,checkpoint,List.of(),null,"WAITING");}
        private long number(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
        private long balance(Fixture fixture){return number("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",fixture.owner().uid());}
        private void fund(Fixture fixture,long amount){
            wallets.post(new WalletPosting(Kind.REWARD,"fixture:"+UUID.randomUUID(),"fixture-v1",fixture.room(),List.of(
                    new Entry(new Account(OwnerType.PERSONAL,fixture.owner().uid(),Currency.CARD),amount,0),new Entry(new Account(OwnerType.SYSTEM,0,Currency.CARD),-amount,0))));
        }
        private WalletPosting charge(Fixture fixture,long amount){return new WalletPosting(Kind.CONSUMPTION,"room:"+fixture.room()+":fixture:charge","fixture-v1",fixture.room(),List.of(
                new Entry(new Account(OwnerType.PERSONAL,fixture.owner().uid(),Currency.CARD),-amount,0),new Entry(new Account(OwnerType.SYSTEM,0,Currency.CARD),amount,0)));}
        private long events(Fixture fixture){return number("SELECT COUNT(*) FROM room_event WHERE room_id=?",fixture.room());}
        private long outboxes(Fixture fixture){return number("SELECT COUNT(*) FROM outbox_event WHERE biz_key LIKE ?","room-event:"+fixture.room()+":%");}
        private com.ruoyi.game.outbox.OutboxEvent outbox(Fixture fixture,long version){return outboxMapper.event(jdbc.queryForObject("SELECT id FROM outbox_event WHERE biz_key=?",String.class,"room-event:"+fixture.room()+":"+version));}
        private com.ruoyi.game.room.RoomReplay.Codec<Integer,Integer> replayCodec(){return new com.ruoyi.game.room.RoomReplay.Codec<>(){
            public boolean supports(String game,int schema,String engine){return game.equals("6003")&&schema==1&&engine.equals("counter-v1");}
            public Integer state(String json){return codec.read(json).get("value").asInt();}
            public Integer event(String kind,String json){return codec.read(json).get("delta").asInt();}
            public String json(Integer value){return "{\"value\":"+value+"}";}
        };}

        @Test void persistedSnapshotAndEventKeepExactNumbersAcrossDatabaseRoundTrip(){
            var fixture=room();String json="{\"decimal\":0.123456789012345678901,\"integer\":9007199254740993}";
            var candidate=new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),fixture.state().ownerEpoch(),1,0,"WAITING",json);
            var command=new com.ruoyi.game.room.RoomJournalCommand(fixture.state(),candidate,"TEST_PRECISION",json,"fixture-v1",1,"counter-v1",fixture.owner(),List.of(),null,true,java.time.Instant.now());
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(command));
            assertEquals(codec.canonicalObject(json),jdbc.queryForObject("SELECT payload_json FROM room_event WHERE room_id=?",String.class,fixture.room()));
            var recovery=journal.claim(fixture.room(),UUID.randomUUID().toString(),1,"counter-v1");
            assertEquals(codec.canonicalObject(json),codec.canonicalObject(recovery.snapshotJson()));
            assertEquals(journal.stateHash(json),recovery.stateHash());
        }

        @Test void committedRetryIsOneEventAndCheckpointAdvancesOnlyWhenRequested(){
            var fixture=room();var first=change(fixture,fixture.state(),1,false);
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(first));
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(first));
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.resolve(first));
            assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
            assertEquals(0,number("SELECT snapshot_seq FROM room_runtime WHERE room_id=?",fixture.room()));
            var second=change(fixture,first.candidate(),2,true);assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(second));
            assertEquals(2,number("SELECT snapshot_seq FROM room_runtime WHERE room_id=?",fixture.room()));assertEquals(2,events(fixture));
            var recovery=journal.claim(fixture.room(),UUID.randomUUID().toString(),1,"counter-v1");
            assertTrue(recovery.events().isEmpty());
            assertEquals(2,com.ruoyi.game.room.RoomReplay.recover(recovery,replayCodec(),Integer::sum,journal::stateHash).state());
        }
        @Test void lateOutboxFailureRollsBackSeatWalletEventVersionAndCheckpoint(){
            var fixture=room();fund(fixture,100);
            var seat=new com.ruoyi.game.room.RoomJournalCommand.SeatChange(com.ruoyi.game.room.RoomJournalCommand.SeatAction.JOIN,fixture.owner().uid(),0);
            var command=change(fixture,fixture.state(),1,true,List.of(charge(fixture,10)),seat,"WAITING");
            doThrow(new IllegalStateException("injected final outbox failure")).when(outboxMapper).append(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any());
            assertThrows(IllegalStateException.class,()->journal.commit(command));
            assertEquals(100,balance(fixture));assertEquals(0,events(fixture));assertEquals(0,outboxes(fixture));
            assertEquals(0,number("SELECT version FROM room_runtime WHERE room_id=?",fixture.room()));
            assertEquals(0,number("SELECT snapshot_seq FROM room_runtime WHERE room_id=?",fixture.room()));
            assertEquals(0,number("SELECT COUNT(*) FROM player_room_binding WHERE uid=?",fixture.owner().uid()));
            assertEquals(0,number("SELECT COUNT(*) FROM room_player WHERE room_id=?",fixture.room()));
            assertEquals(0,number("SELECT COUNT(*) FROM wallet_transaction WHERE business_key LIKE ?","room:"+fixture.room()+":%"));
        }
        @Test void parallelRetriesDebitOnceAndUseOnePersistentRoomVersion() throws Exception {
            var fixture=room();fund(fixture,100);var command=change(fixture,fixture.state(),1,false,List.of(charge(fixture,3)),null,"WAITING");
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Future<com.ruoyi.game.room.RoomPersistence.Decision>> results=new ArrayList<>();
                for(int i=0;i<16;i++)results.add(workers.submit(()->journal.commit(command)));
                for(var result:results)assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,result.get(10,TimeUnit.SECONDS));
            }
            assertEquals(97,balance(fixture));assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
            assertEquals(1,number("SELECT COUNT(*) FROM wallet_transaction WHERE business_key LIKE ?","room:"+fixture.room()+":%"));
        }
        @Test void samePlayerCannotJoinTwoRoomsConcurrently() throws Exception {
            GuestIdentity owner=auth.verify(auth.createGuest());var a=room(owner,Integer.toString(ROOM_CODES.incrementAndGet()));var b=room(owner,Integer.toString(ROOM_CODES.incrementAndGet()));
            var seat=new com.ruoyi.game.room.RoomJournalCommand.SeatChange(com.ruoyi.game.room.RoomJournalCommand.SeatAction.JOIN,owner.uid(),0);
            var first=change(a,a.state(),1,false,List.of(),seat,"WAITING");var second=change(b,b.state(),1,false,List.of(),seat,"WAITING");
            List<com.ruoyi.game.room.RoomPersistence.Decision> outcomes=new ArrayList<>();
            try(ExecutorService workers=Executors.newFixedThreadPool(2)){
                var one=workers.submit(()->journal.commit(first));var two=workers.submit(()->journal.commit(second));
                outcomes.add(one.get(10,TimeUnit.SECONDS));outcomes.add(two.get(10,TimeUnit.SECONDS));
            }
            assertEquals(1,outcomes.stream().filter(value->value==com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED).count());
            assertEquals(1,number("SELECT COUNT(*) FROM player_room_binding WHERE uid=?",owner.uid()));
            assertEquals(1,number("SELECT COUNT(*) FROM room_player WHERE uid=?",owner.uid()));assertEquals(1,events(a)+events(b));
        }
        @Test void reusedRoomCodeCannotMixPermanentEventsOrBeReleasedByOldRetry(){
            var old=room(auth.verify(auth.createGuest()),"810001");var close=change(old,old.state(),1,true,List.of(),null,"CLOSED");
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(close));
            var fresh=room(old.owner(),"810001");assertNotEquals(old.room(),fresh.room());
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(close));
            assertEquals(fresh.room(),jdbc.queryForObject("SELECT room_id FROM active_room_code WHERE room_code='810001'",String.class));
            assertEquals(1,events(old));assertEquals(0,events(fresh));
            assertEquals("ROOM_CLOSED",assertThrows(com.ruoyi.game.room.RoomRejected.class,()->journal.claim(old.room(),UUID.randomUUID().toString(),1,"counter-v1")).code());
        }
        @Test void replayUsesSavedEventsWithoutRepeatingMoneyOrPublishingOutbox(){
            var fixture=room();fund(fixture,100);var first=change(fixture,fixture.state(),1,false,List.of(charge(fixture,2)),null,"WAITING");
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(first));
            var second=change(fixture,first.candidate(),2,false);assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(second));
            long ledger=number("SELECT COUNT(*) FROM wallet_ledger"),outbox=outboxes(fixture);
            var recovery=journal.claim(fixture.room(),UUID.randomUUID().toString(),1,"counter-v1");
            for(int i=0;i<2;i++)assertEquals(2,com.ruoyi.game.room.RoomReplay.recover(recovery,replayCodec(),Integer::sum,journal::stateHash).state());
            assertEquals(98,balance(fixture));assertEquals(ledger,number("SELECT COUNT(*) FROM wallet_ledger"));assertEquals(outbox,outboxes(fixture));assertEquals(2,events(fixture));
        }
        @Test void newOwnerEpochFencesOldWriterAndUnknownEngineDoesNotAcquireOwner(){
            var fixture=room();var old=change(fixture,fixture.state(),1,false);
            long epoch=number("SELECT owner_epoch FROM room_runtime WHERE room_id=?",fixture.room());
            assertEquals("UNSUPPORTED_ENGINE",assertThrows(com.ruoyi.game.room.RoomRejected.class,()->journal.claim(fixture.room(),UUID.randomUUID().toString(),2,"unknown")).code());
            assertEquals(epoch,number("SELECT owner_epoch FROM room_runtime WHERE room_id=?",fixture.room()));
            var fresh=journal.claim(fixture.room(),UUID.randomUUID().toString(),1,"counter-v1");assertEquals(epoch+1,fresh.ownerEpoch());
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.OWNER_CHANGED,journal.commit(old));assertEquals(0,events(fixture));
        }
        @Test void missingEventOrCorruptReplayPayloadFailsRecovery(){
            var missing=room();assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(change(missing,missing.state(),1,false)));
            jdbc.update("DELETE FROM room_event WHERE room_id=?",missing.room());
            assertEquals("EVENT_GAP",assertThrows(com.ruoyi.game.room.RoomRejected.class,()->journal.claim(missing.room(),UUID.randomUUID().toString(),1,"counter-v1")).code());
            var corrupt=room();assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(change(corrupt,corrupt.state(),1,false)));
            jdbc.update("UPDATE room_event SET payload_json='{\"delta\":999}' WHERE room_id=?",corrupt.room());
            var recovery=journal.claim(corrupt.room(),UUID.randomUUID().toString(),1,"counter-v1");
            assertEquals("STATE_HASH_MISMATCH",assertThrows(com.ruoyi.game.room.RoomRejected.class,()->com.ruoyi.game.room.RoomReplay.recover(recovery,replayCodec(),Integer::sum,journal::stateHash)).code());
        }
        @Test void recoveryBudgetsRejectBeforeChangingPersistentOwnerEpoch(){
            var tooMany=room();
            jdbc.update("UPDATE room_runtime SET version=2049 WHERE room_id=?",tooMany.room());
            assertThrows(com.ruoyi.game.room.RoomRejected.class,()->journal.claim(tooMany.room(),UUID.randomUUID().toString(),1,"counter-v1"));
            assertEquals(1,number("SELECT owner_epoch FROM room_runtime WHERE room_id=?",tooMany.room()));
            var tooLarge=room();
            for(int sequence=1;sequence<=9;sequence++)jdbc.update("INSERT INTO room_event(room_id,event_seq,owner_epoch,round_no,phase,event_kind,payload_json,request_hash,before_hash,after_hash,schema_version,engine_version,occurred_at,committed_at) VALUES(?,?,1,0,'WAITING','TEST_INCREMENT',JSON_OBJECT('opaque',REPEAT('x',1048576)),REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),1,'counter-v1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tooLarge.room(),sequence);
            jdbc.update("UPDATE room_runtime SET version=9 WHERE room_id=?",tooLarge.room());
            assertThrows(com.ruoyi.game.room.RoomRejected.class,()->journal.claim(tooLarge.room(),UUID.randomUUID().toString(),1,"counter-v1"));
            assertEquals(1,number("SELECT owner_epoch FROM room_runtime WHERE room_id=?",tooLarge.room()));
        }

        @Test void duplicateAndOutOfOrderConsumerDeliveryCountsEachSourceEventOnce() throws Exception {
            var fixture=room();var one=change(fixture,fixture.state(),1,false);journal.commit(one);journal.commit(change(fixture,one.candidate(),2,false));
            var first=outbox(fixture,1);var second=outbox(fixture,2);roomEvents.consume(second);
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Future<?>> results=new ArrayList<>();for(int i=0;i<16;i++)results.add(workers.submit(()->roomEvents.consume(first)));
                for(var result:results)result.get(10,TimeUnit.SECONDS);
            }
            assertEquals(2,number("SELECT event_count FROM room_event_projection WHERE room_id=?",fixture.room()));
            assertEquals(2,number("SELECT last_version FROM room_event_projection WHERE room_id=?",fixture.room()));
            assertEquals(2,number("SELECT COUNT(*) FROM consumer_receipt WHERE event_id IN (?,?)",first.id(),second.id()));
        }
        @Test void consumerFailureRollsBackReceiptAndProjection(){
            var fixture=room();journal.commit(change(fixture,fixture.state(),1,false));var event=outbox(fixture,1);
            doThrow(new IllegalStateException("injected projection failure")).when(roomJournalMapper).project(anyString(),anyLong(),any(),any());
            assertThrows(IllegalStateException.class,()->roomEvents.consume(event));
            assertEquals(0,number("SELECT COUNT(*) FROM consumer_receipt WHERE event_id=?",event.id()));
            assertEquals(0,number("SELECT COUNT(*) FROM room_event_projection WHERE room_id=?",fixture.room()));
        }
        @Test void roomPoolCanCommitSharedWalletAndOutboxWhenGeneralPoolIsExhausted() throws Exception {
            var fixture=room();fund(fixture,100);var command=change(fixture,fixture.state(),1,false,List.of(charge(fixture,1)),null,"WAITING");
            var general=assertInstanceOf(com.alibaba.druid.pool.DruidDataSource.class,generalPool);var dedicated=assertInstanceOf(com.alibaba.druid.pool.DruidDataSource.class,roomPool);
            assertEquals(16,general.getMaxActive());assertEquals(8,dedicated.getMaxActive());
            List<java.sql.Connection> held=new ArrayList<>();
            try{
                for(int i=0;i<general.getMaxActive();i++)held.add(general.getConnection());
                assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(command));
            }finally{for(var connection:held)connection.close();}
            assertEquals(99,balance(fixture));assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
            assertNull(com.ruoyi.framework.datasource.DynamicDataSourceContextHolder.getDataSourceType());
        }
        @Test void committedButLostBoundaryResultResolvesWithoutRepeatingEffects(){
            var fixture=room();fund(fixture,100);var command=change(fixture,fixture.state(),1,false,List.of(charge(fixture,3)),null,"WAITING");
            var lost=new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(call->{Object result=call.callRealMethod();if(lost.getAndSet(false))throw new org.springframework.dao.DataAccessResourceFailureException("injected result loss after commit");return result;}).when(roomTransactions).execute(any());
            assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->journal.commit(command));
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.resolve(command));
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.commit(command));
            assertEquals(97,balance(fixture));assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
        }
        @Test void afterCommitExceptionCannotBeMistakenForKnownPrecommitRejection(){
            var fixture=room();var command=change(fixture,fixture.state(),1,false);var lost=new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(call->{Object result=call.callRealMethod();if(lost.getAndSet(false))throw new org.springframework.dao.DataIntegrityViolationException("injected exception after completed commit");return result;}).when(roomTransactions).execute(any());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->journal.commit(command));
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.COMMITTED,journal.resolve(command));assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
        }
        private com.ruoyi.game.room.JournalRoomPersistence<Integer,Integer,Integer> bridge(Fixture fixture,List<WalletPosting> postings){
            return new com.ruoyi.game.room.JournalRoomPersistence<>(journal,write->{
                var before=new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),write.previous().ownerEpoch(),write.previous().version(),write.previous().round(),write.previous().phase(),"{\"value\":"+write.previous().state()+"}");
                var candidate=new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),write.candidate().ownerEpoch(),write.candidate().version(),write.candidate().round(),write.candidate().phase(),"{\"value\":"+write.candidate().state()+"}");
                return new com.ruoyi.game.room.RoomJournalCommand(before,candidate,"TEST_INCREMENT","{\"delta\":"+write.event()+"}","fixture-v1",1,"counter-v1",fixture.owner(),postings,null,false,write.occurredAt());
            });
        }
        @Test void realMailboxAndRoomTransactionInstallOnlyCommittedState() throws Exception {
            var fixture=room();
            try(var boxes=new com.ruoyi.game.room.RoomMailboxes<Integer,Integer,Integer>(new com.ruoyi.game.room.RoomMailboxes.Config(1,16,8,8,2,8,5),bridge(fixture,List.of()))){
                var handle=boxes.register(new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),fixture.state().ownerEpoch(),0,0,"WAITING",0),(before,value)->new com.ruoyi.game.room.RoomTransition<>(0,"WAITING",before.state()+value,value));
                var first=boxes.submit(handle,1);var second=boxes.submit(handle,1);
                assertEquals(1,first.toCompletableFuture().get(3,TimeUnit.SECONDS).state());assertEquals(2,second.toCompletableFuture().get(3,TimeUnit.SECONDS).state());
                assertEquals(2,events(fixture));assertEquals(2,outboxes(fixture));assertEquals(2,number("SELECT version FROM room_runtime WHERE room_id=?",fixture.room()));
            }
        }
        @Test void lostCommitResultKeepsMailboxOldStateUntilDatabaseConfirmation() throws Exception {
            var fixture=room();fund(fixture,100);
            var lost=new CountDownLatch(1);var allowResolve=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();var applied=new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call->{int attempt=calls.incrementAndGet();if(attempt==2&&!allowResolve.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");Object result=call.callRealMethod();if(attempt==1){lost.countDown();throw new org.springframework.dao.DataAccessResourceFailureException("injected committed result loss");}return result;}).when(roomTransactions).execute(any());
            try(var boxes=new com.ruoyi.game.room.RoomMailboxes<Integer,Integer,Integer>(new com.ruoyi.game.room.RoomMailboxes.Config(1,16,8,8,2,8,5),bridge(fixture,List.of(charge(fixture,3))))){
                var handle=boxes.register(new com.ruoyi.game.room.RoomSnapshot<>(fixture.room(),fixture.state().ownerEpoch(),0,0,"WAITING",0),(before,value)->{applied.incrementAndGet();return new com.ruoyi.game.room.RoomTransition<>(0,"WAITING",before.state()+value,value);});
                var first=boxes.submit(handle,1);assertTrue(lost.await(3,TimeUnit.SECONDS));assertEquals(0,boxes.snapshot(handle).state());assertFalse(first.toCompletableFuture().isDone());
                allowResolve.countDown();assertEquals(1,first.toCompletableFuture().get(3,TimeUnit.SECONDS).state());assertEquals(1,applied.get());
                assertEquals(97,balance(fixture));assertEquals(1,events(fixture));assertEquals(1,outboxes(fixture));
            }finally{allowResolve.countDown();}
        }

        @Test void outerTransactionCannotSilentlyRouteRoomCommitToGeneralPool(){
            var fixture=room();var command=change(fixture,fixture.state(),1,false);
            assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->journal.commit(command)));
            assertEquals(0,events(fixture));assertNull(com.ruoyi.framework.datasource.DynamicDataSourceContextHolder.getDataSourceType());
        }
        @Test void roomMetadataQueryHonorsClubScopeAndNeverReturnsPrivateState(){
            var fixture=room();var foreign=room();long actor=900099;
            jdbc.update("INSERT INTO club(name,owner_uid,max_members,max_managers,is_test,created_at,updated_at) VALUES('房间范围验收圈',?,5,1,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",fixture.owner().uid());
            long club=number("SELECT id FROM club WHERE owner_uid=?",fixture.owner().uid());
            jdbc.update("INSERT INTO club_member(club_id,uid,member_role,joined_at) VALUES(?,?,'OWNER',UTC_TIMESTAMP(6))",club,fixture.owner().uid());
            jdbc.update("UPDATE room_record SET club_id=? WHERE room_id=?",club,fixture.room());
            jdbc.update("INSERT INTO admin_data_scope(principal_id,scope_type,scope_id) VALUES(?,'CLUB',?)",actor,Long.toString(club));
            var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(1,((Number)roomAdmin.list(actor,null,null,null,null,today,today,1,50).get("total")).longValue());
            var detail=roomAdmin.detail(actor,fixture.room());String visible=codec.write(detail);
            assertTrue(visible.contains(fixture.room()));assertFalse(visible.contains("snapshotJson"));assertFalse(visible.contains("stateHash"));assertFalse(visible.contains("payloadJson"));
            assertThrows(SecurityException.class,()->roomAdmin.detail(actor,foreign.room()));
            jdbc.update("DELETE FROM admin_data_scope WHERE principal_id=?",actor);
            assertThrows(SecurityException.class,()->roomAdmin.detail(actor,fixture.room()));
        }
        @Test void roomMetadataFiltersValidateDatesCodesAndState(){
            var fixture=room();var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(1,((Number)roomAdmin.list(1,null,fixture.owner().uid(),null,"WAITING",today,today,1,50).get("total")).longValue());
            assertThrows(IllegalArgumentException.class,()->roomAdmin.list(1,"1 OR 1=1",null,null,null,today,today,1,50));
            assertThrows(IllegalArgumentException.class,()->roomAdmin.list(1,null,null,null,"UNKNOWN",today,today,1,50));
            assertThrows(IllegalArgumentException.class,()->roomAdmin.list(1,null,null,null,null,today.minusDays(31),today,1,50));
        }
        @Test void naturalLandlordPlayRecoversMidRoundAndPersistsLastCardWithoutInventedSettlement() throws Exception {
            List<GuestIdentity> identities=List.of(auth.verify(auth.createGuest()),auth.verify(auth.createGuest()),auth.verify(auth.createGuest()));
            List<Long> players=identities.stream().map(GuestIdentity::uid).toList();Map<Long,GuestIdentity> actors=new HashMap<>();identities.forEach(identity->actors.put(identity.uid(),identity));
            String id=UUID.randomUUID().toString(),code=Integer.toString(ROOM_CODES.incrementAndGet()),rule="mechanics-fixture-v1",empty="{}";
            var playCodec=new LandlordPlayCodec(codec);var engine=new LandlordPlayEngine();
            jdbc.update("INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,options_json,rule_version,status,is_test,created_at) VALUES(?,?,'6003',?,?,'{}',?,'WAITING',TRUE,UTC_TIMESTAMP(6))",id,code,players.getFirst(),players.getFirst(),rule);
            jdbc.update("INSERT INTO active_room_code(room_code,room_id) VALUES(?,?)",code,id);
            jdbc.update("INSERT INTO room_runtime(room_id,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at) VALUES(?,?,?,?,1,?,UTC_TIMESTAMP(6))",id,journal.stateHash(empty),empty,journal.stateHash(empty),LandlordPlayEngine.ENGINE_VERSION);
            for(int seat=0;seat<3;seat++){
                jdbc.update("INSERT INTO room_player(room_id,uid,seat,joined_at) VALUES(?,?,?,UTC_TIMESTAMP(6))",id,players.get(seat),seat);
                jdbc.update("INSERT INTO player_room_binding(uid,room_id,seat,joined_at) VALUES(?,?,?,UTC_TIMESTAMP(6))",players.get(seat),id,seat);
            }
            var ownership=journal.claim(id,UUID.randomUUID().toString(),1,LandlordPlayEngine.ENGINE_VERSION);
            // 测试从已确定地主的发牌检查点开始，不冒充叫分、收费或完整结算验收。
            var initial=LandlordPlayEngine.start(players,LandlordDeck.deal(),players.getFirst());String initialJson=playCodec.json(initial);
            var previous=new RoomSnapshot<>(id,ownership.ownerEpoch(),0,0,"WAITING",empty);
            var started=new RoomSnapshot<>(id,ownership.ownerEpoch(),1,1,"PLAYING",initialJson);
            assertEquals(RoomPersistence.Decision.COMMITTED,journal.commit(new RoomJournalCommand(previous,started,"DEAL_RECORDED",initialJson,rule,1,LandlordPlayEngine.ENGINE_VERSION,identities.getFirst(),List.of(),null,true,java.time.Instant.now())));
            var plans=new LandlordPlayPlans(playCodec,rule);
            var persistence=new JournalRoomPersistence<LandlordPlayState,LandlordPlayCommand,LandlordPlayEvent>(journal,write->plans.command(write,actors.get(write.command().uid())));
            java.util.function.Supplier<RoomMailboxes<LandlordPlayState,LandlordPlayCommand,LandlordPlayEvent>> boxes=()->new RoomMailboxes<>(new RoomMailboxes.Config(2,128,16,16,2,32,10),persistence);
            var box=boxes.get();var handle=box.register(new RoomSnapshot<>(id,ownership.ownerEpoch(),1,1,"PLAYING",initial),engine);
            int moves=0,restarts=0;RoomSnapshot<LandlordPlayState> current=box.snapshot(handle);
            try{
                while(!current.state().finished()){
                    if(moves==10){
                        box.close();var recovery=journal.claim(id,UUID.randomUUID().toString(),1,LandlordPlayEngine.ENGINE_VERSION);
                        var recovered=RoomReplay.recover(recovery,playCodec,engine::replay,journal::stateHash);
                        assertEquals(current.state(),recovered.state());assertEquals(current.version(),recovered.version());assertTrue(recovered.ownerEpoch()>current.ownerEpoch());
                        box=boxes.get();handle=box.register(recovered,engine);current=recovered;restarts++;
                    }
                    assertTrue(++moves<=200);var state=current.state();long uid=state.currentUid();var target=state.target();
                    var card=state.hands().get(uid).stream().filter(value->target==null||value.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
                    LandlordAction action=card.<LandlordAction>map(value->new LandlordAction.Play(List.of(value),LandlordPatterns.classify(List.of(value)).orElseThrow())).orElseGet(LandlordAction.Pass::new);
                    current=box.submit(handle,new LandlordPlayCommand(uid,action)).toCompletableFuture().get(5,TimeUnit.SECONDS);
                }
            }finally{box.close();}
            assertEquals(1,restarts);assertEquals("ROUND_FINISHED",current.phase());assertEquals(moves+1,current.version());
            assertEquals(moves+1,number("SELECT COUNT(*) FROM room_event WHERE room_id=?",id));
            assertEquals(moves+1,number("SELECT COUNT(*) FROM outbox_event WHERE biz_key LIKE ?","room-event:"+id+":%"));
            assertEquals(current.version(),number("SELECT snapshot_seq FROM room_runtime WHERE room_id=?",id));
            var recovered=RoomReplay.recover(journal.claim(id,UUID.randomUUID().toString(),1,LandlordPlayEngine.ENGINE_VERSION),playCodec,engine::replay,journal::stateHash);
            assertEquals(current.state(),recovered.state());assertEquals(current.state().winner(),engine.outcome(recovered.state()).winner());
            assertEquals(0,number("SELECT COUNT(*) FROM wallet_transaction WHERE reference_id=?",id));
            for(long uid:players)assertEquals(0,number("SELECT SUM(balance+frozen) FROM wallet_account WHERE uid=?",uid));
        }

        @Test void revokedIdentityAndForeignWalletIntentCannotChangeRoom(){
            var fixture=room();fund(fixture,100);jdbc.update("UPDATE user_account SET status='BANNED' WHERE id=?",fixture.owner().accountId());
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.NOT_COMMITTED,journal.commit(change(fixture,fixture.state(),1,false)));
            assertEquals(0,events(fixture));assertEquals(100,balance(fixture));
            jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",fixture.owner().accountId());
            var foreign=new WalletPosting(Kind.CONSUMPTION,"room:"+UUID.randomUUID()+":fixture:charge","fixture-v1",fixture.room(),charge(fixture,1).entries());
            assertEquals(com.ruoyi.game.room.RoomPersistence.Decision.NOT_COMMITTED,journal.commit(change(fixture,fixture.state(),1,false,List.of(foreign),null,"WAITING")));
            assertEquals(0,events(fixture));assertEquals(100,balance(fixture));
        }
    }

    @Nested class ClubOrganization {
        private long player(){return auth.verify(auth.createGuest()).uid();}
        /** 仅隔离测试库的已知组织，非生产初始化、非演示成员；加入均走申请事务。 */
        private long existingClub(long owner,int capacity,Integer managers){
            jdbc.update("INSERT INTO club(name,owner_uid,max_members,max_managers,is_test,created_at,updated_at) VALUES(?,?,?,?,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",UUID.randomUUID().toString().substring(0,8),owner,capacity,managers);
            long id=jdbc.queryForObject("SELECT id FROM club WHERE owner_uid=?",Long.class,owner);
            jdbc.update("INSERT INTO club_member(club_id,uid,member_role,joined_at) VALUES(?,?,'OWNER',UTC_TIMESTAMP(6))",id,owner);
            jdbc.update("INSERT INTO club_wallet_account(club_id,currency) VALUES(?,'card'),(?,'diamond')",id,id);
            return id;
        }
        private void join(long owner,long id,long uid){clubs.apply(uid,id,"JOIN");clubs.review(owner,id,uid,"JOIN",1);}

        @Test void concurrentApprovalCreatesOneMembershipAndOneEvent() throws Exception {
            long owner=player(),applicant=player(),id=existingClub(owner,10,2);clubs.apply(applicant,id,"JOIN");
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<16;i++)results.add(workers.submit(()->{try{clubs.review(owner,id,applicant,"JOIN",1);return true;}catch(GameFailure expected){return false;}}));
                int succeeded=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))succeeded++;
                assertEquals(1,succeeded);
            }
            assertEquals(2,jdbc.queryForObject("SELECT member_count FROM club WHERE id=?",Integer.class,id));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM club_member WHERE club_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM club_event WHERE club_id=? AND action='REVIEW_JOIN'",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM club_message WHERE club_id=? AND uid=?",Integer.class,id,applicant));
        }
        @Test void concurrentApplicantsCannotOversubscribeLastSeat() throws Exception {
            long owner=player(),a=player(),b=player(),id=existingClub(owner,2,1);clubs.apply(a,id,"JOIN");clubs.apply(b,id,"JOIN");
            try(ExecutorService workers=Executors.newFixedThreadPool(2)){
                var results=workers.invokeAll(List.of(()->{try{clubs.review(owner,id,a,"JOIN",1);return 1;}catch(GameFailure e){return 0;}},()->{try{clubs.review(owner,id,b,"JOIN",1);return 1;}catch(GameFailure e){return 0;}}));
                int total=0;for(var result:results)total+=(Integer)result.get();assertEquals(1,total);
            }
            assertEquals(2,jdbc.queryForObject("SELECT member_count FROM club WHERE id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM club_application WHERE club_id=? AND status='PENDING'",Integer.class,id));
        }
        @Test void resourceAndRoleChecksRejectCrossClubAndPeerRemoval(){
            long owner=player(),manager=player(),peer=player(),member=player(),otherOwner=player();
            long id=existingClub(owner,10,2),other=existingClub(otherOwner,5,1);
            join(owner,id,manager);join(owner,id,peer);join(owner,id,member);
            clubs.manager(owner,id,manager,true);clubs.manager(owner,id,peer,true);
            assertThrows(GameFailure.class,()->clubs.members(manager,other));
            assertThrows(GameFailure.class,()->clubs.roles(manager,id,List.of(otherOwner)));
            assertThrows(GameFailure.class,()->clubs.kick(member,id,manager));
            assertThrows(GameFailure.class,()->clubs.kick(manager,id,peer));
            assertThrows(GameFailure.class,()->clubs.kick(manager,id,owner));
            assertThrows(GameFailure.class,()->clubs.manager(manager,id,member,true));
            clubs.manager(owner,id,manager,false);
            assertThrows(GameFailure.class,()->clubs.kick(manager,id,member));
            assertEquals(4,jdbc.queryForObject("SELECT member_count FROM club WHERE id=?",Integer.class,id));
        }
        @Test void leaveApprovalMaintainsCountsAndReadableMessages(){
            long owner=player(),member=player(),id=existingClub(owner,5,1);join(owner,id,member);
            clubs.apply(member,id,"LEAVE");
            @SuppressWarnings("unchecked") var pending=(Map<String,Object>)clubs.leaveApplications(owner,id).get("list");
            assertTrue(pending.containsKey(Long.toString(member)));
            clubs.review(owner,id,member,"LEAVE",1);
            assertEquals(1,jdbc.queryForObject("SELECT member_count FROM club WHERE id=?",Integer.class,id));
            assertThrows(GameFailure.class,()->clubs.get(member,id));
            assertThrows(GameFailure.class,()->clubs.review(owner,id,member,"LEAVE",1));
            @SuppressWarnings("unchecked") var messages=(List<String>)clubs.messages(member).get("msgs");
            assertTrue(messages.stream().anyMatch(value->value.contains(",applyLeaveSuc,")));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM club_message WHERE uid=? AND read_at IS NULL",Integer.class,member));
            assertThrows(GameFailure.class,()->clubs.apply(owner,id,"LEAVE"));
        }
        @Test void unknownManagerLimitDoesNotGrantUnlimitedManagers(){
            long owner=player(),member=player(),id=existingClub(owner,5,null);join(owner,id,member);
            assertThrows(GameFailure.class,()->clubs.manager(owner,id,member,true));
            assertEquals("MEMBER",jdbc.queryForObject("SELECT member_role FROM club_member WHERE club_id=? AND uid=?",String.class,id,member));
            assertThrows(GameFailure.class,clubs::unavailableEconomy);
        }
        @Test void pendingJoinProfilesAreVisibleOnlyToManagers(){
            long owner=player(),member=player(),applicant=player(),id=existingClub(owner,5,1);join(owner,id,member);clubs.apply(applicant,id,"JOIN");
            assertTrue(((Map<?,?>)clubs.roles(owner,id,List.of(applicant)).get("roles")).containsKey(Long.toString(applicant)));
            assertThrows(GameFailure.class,()->clubs.roles(member,id,List.of(applicant)));
            var page=(Map<?,?>)clubs.applications(owner,id,1,5).get("list");assertEquals(1L,page.get("total_page"));
            clubs.review(owner,id,applicant,"JOIN",2);
            assertThrows(GameFailure.class,()->clubs.roles(owner,id,List.of(applicant)));
        }
        @Test void adminClubScopeAndIdempotencyPreserveOperatorIdentity(){
            long owner=player(),applicant=player(),otherOwner=player(),id=existingClub(owner,5,1),other=existingClub(otherOwner,5,1),actor=900001;
            jdbc.update("INSERT INTO admin_data_scope VALUES(?,'CLUB',?)",actor,Long.toString(id));
            assertThrows(SecurityException.class,()->clubAdmin.detail(actor,other));
            clubs.apply(applicant,id,"JOIN");
            var command=new com.ruoyi.game.club.ClubAdminService.Command("review",applicant,"JOIN",1,null,null,"隔离圈范围审批验收");
            String key=UUID.randomUUID().toString();
            var first=clubAdmin.command(actor,id,command,key);var second=clubAdmin.command(actor,id,command,key);
            assertEquals(first.result().get("operationId"),second.result().get("operationId"));
            assertFalse(first.notifications().isEmpty());assertTrue(second.notifications().isEmpty());
            assertEquals("ADMIN",jdbc.queryForObject("SELECT actor_type FROM club_event WHERE club_id=? AND action='REVIEW_JOIN'",String.class,id));
            assertEquals(actor,jdbc.queryForObject("SELECT actor_id FROM club_event WHERE club_id=? AND action='REVIEW_JOIN'",Long.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE operator_id=? AND action='club:review'",Integer.class,actor));
            jdbc.update("DELETE FROM admin_data_scope WHERE principal_id=?",actor);
            assertThrows(SecurityException.class,()->clubAdmin.command(actor,id,command,key));
        }
        @Test void adminOnlineSortMatchesMembershipAndUnavailableIsNotZero(){
            long owner=player(),member=player(),otherOwner=player(),id=existingClub(owner,5,1),other=existingClub(otherOwner,5,1);join(owner,id,member);
            var response=clubAdmin.list(1L,null,null,null,null,"online",1,200,Set.of(member),"AVAILABLE","2026-11-06T16:00:00Z");
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("rows");
            assertEquals(Long.toString(id),rows.getFirst().get("clubId"));assertEquals(1L,((Number)rows.getFirst().get("onlineCount")).longValue());
            var unavailable=clubAdmin.list(1L,id,null,null,null,"id",1,50,null,"UNAVAILABLE","2026-11-06T16:00:00Z");
            @SuppressWarnings("unchecked") var unknown=(List<Map<String,Object>>)unavailable.get("rows");assertNull(unknown.getFirst().get("onlineCount"));
            assertThrows(com.ruoyi.game.admin.GameDataUnavailable.class,()->clubAdmin.list(1L,null,null,null,null,"online",1,50,null,"UNAVAILABLE","2026-11-06T16:00:00Z"));
        }
        @Test void consumerManagementFlagIncludesOwnerAndAutoBuildRemainsClosed(){
            long owner=player(),manager=player(),member=player(),id=existingClub(owner,5,1);join(owner,id,manager);join(owner,id,member);
            assertEquals(true,clubs.config(owner,id).get("is_manager"));
            clubs.manager(owner,id,manager,true);
            assertEquals(true,clubs.config(manager,id).get("is_manager"));
            assertEquals(false,clubs.config(member,id).get("is_manager"));
            var change=clubs.config(manager,id,false);
            assertEquals(0,((Map<?,?>)change.notifications().getFirst().packet().get("config")).get("only_manager_open_room"));
            assertEquals(Map.of("strength_status",0,"ore_status",0),clubs.autoBuild(owner,id,"6003").get("config"));
            assertThrows(GameFailure.class,()->clubs.autoBuild(member,id,"6003"));
        }

    }
    @Nested class DailyWalletReconciliation {
        private record Fixture(String id,long uid){ }
        @BeforeEach void isolatedJobQueue(){jdbc.update("DELETE FROM wallet_reconciliation_detail");jdbc.update("DELETE FROM wallet_reconciliation_run");}
        private Fixture fixture(boolean test){
            var identity=auth.verify(auth.createGuest());long uid=identity.uid();
            if(test)jdbc.update("UPDATE user_account SET is_test=TRUE WHERE id=?",identity.accountId());
            String id=UUID.randomUUID().toString();
            reconciliationMapper.begin(id,reconciliationJobs.today(),uid,0,"SYSTEM",null,java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            // 隔离夹具只覆盖本次新 UID 和两个清算账户，不扫描其他测试方法的账户。
            jdbc.update("UPDATE wallet_reconciliation_run SET cursor_owner=? WHERE id=?",uid,id);
            return new Fixture(id,uid);
        }
        private long number(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
        private void due(Fixture fixture){jdbc.update("UPDATE wallet_reconciliation_run SET retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",fixture.id());}
        private void finish(com.ruoyi.game.economy.WalletReconciliationJobs.Lease lease){
            for(int step=0;step<20;step++){
                var result=reconciliationJobs.process(lease);
                if(result==com.ruoyi.game.economy.WalletReconciliationJobs.Step.DONE)return;
                assertEquals(com.ruoyi.game.economy.WalletReconciliationJobs.Step.PROGRESSED,result);
            }
            fail("隔离核对未在账户预算内结束");
        }
        private void fund(long uid,long amount){wallets.post(new WalletPosting(Kind.REWARD,"recon_fixture:"+UUID.randomUUID(),"fixture-v1","reconciliation-fixture",List.of(new Entry(new Account(OwnerType.PERSONAL,uid,Currency.CARD),amount,0),new Entry(new Account(OwnerType.SYSTEM,0,Currency.CARD),-amount,0))));}

        @Test void concurrentDailyCreationReturnsOnePersistentRun() throws Exception {
            Set<String> runs=ConcurrentHashMap.newKeySet();
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Future<?>> results=new ArrayList<>();for(int i=0;i<16;i++)results.add(workers.submit(()->runs.add((String)reconciliationJobs.ensureToday("SYSTEM",null).get("id"))));
                for(var result:results)result.get(10,TimeUnit.SECONDS);
            }
            assertEquals(1,runs.size());assertEquals(1,number("SELECT COUNT(*) FROM wallet_reconciliation_run"));
        }
        @Test void realZeroAccountsCompleteOnceAndDoNotCreateMoney(){
            var fixture=fixture(false);var lease=reconciliationJobs.claim();assertEquals(fixture.id(),lease.id());finish(lease);
            assertEquals("DONE",reconciliationMapper.metadata(fixture.id()).get("status"));
            assertEquals(4,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            assertEquals(4,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=?",fixture.id()));
            assertEquals(com.ruoyi.game.economy.WalletReconciliationJobs.Step.LOST,reconciliationJobs.process(lease));assertFalse(reconciliationJobs.failed(lease,"LATE_ERROR"));
            assertEquals(0,number("SELECT SUM(balance+frozen) FROM wallet_account WHERE uid=?",fixture.uid()));
            assertEquals(0,number("SELECT COUNT(*) FROM wallet_ledger WHERE owner_type='PERSONAL' AND owner_id=?",fixture.uid()));
        }
        @Test void mismatchIsRecordedWithoutRepairAndNewAccountsWaitForAnotherRun(){
            var fixture=fixture(false);fund(fixture.uid(),10);jdbc.update("UPDATE wallet_account SET balance=balance+1 WHERE uid=? AND currency='card'",fixture.uid());
            long later=auth.verify(auth.createGuest()).uid();finish(reconciliationJobs.claim());
            assertEquals("DONE_WITH_ISSUES",reconciliationMapper.metadata(fixture.id()).get("status"));assertEquals(1,number("SELECT mismatched_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            assertEquals("10",jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.expectedAvailable')) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL' AND owner_id=? AND currency='card'",String.class,fixture.id(),fixture.uid()));
            assertEquals("11",jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.actualAvailable')) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL' AND owner_id=? AND currency='card'",String.class,fixture.id(),fixture.uid()));
            assertEquals(11,number("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",fixture.uid()));
            assertEquals(0,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL' AND owner_id=?",fixture.id(),later));
        }
        @Test void testAccountsAreExcludedFromTheDailyScope(){
            var fixture=fixture(true);finish(reconciliationJobs.claim());
            assertEquals(2,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            assertEquals(0,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL'",fixture.id()));
        }
        @Test void clubAccountsAreCheckedButTestClubsAreExcluded(){
            for(boolean test:List.of(false,true)){
                isolatedJobQueue();var fixture=fixture(false);String name=UUID.randomUUID().toString().substring(0,8);
                jdbc.update("INSERT INTO club(name,owner_uid,max_members,is_test,created_at,updated_at) VALUES(?,?,5,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",name,fixture.uid(),test);
                long club=number("SELECT id FROM club WHERE owner_uid=? AND name=?",fixture.uid(),name);
                jdbc.update("INSERT INTO club_wallet_account(club_id,currency) VALUES(?,'card'),(?,'diamond')",club,club);
                jdbc.update("UPDATE wallet_reconciliation_run SET club_highwater=?,cursor_kind=1,cursor_owner=?,cursor_currency='' WHERE id=?",club,club,fixture.id());
                finish(reconciliationJobs.claim());
                assertEquals(test?0:2,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='CLUB'",fixture.id()));
                assertEquals(test?2:4,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            }
        }
        @Test void concurrentClaimAndExpiredLeasePreserveOneCursorOwner() throws Exception {
            var fixture=fixture(false);List<com.ruoyi.game.economy.WalletReconciliationJobs.Lease> leases=new ArrayList<>();
            try(ExecutorService workers=Executors.newFixedThreadPool(4)){
                List<Future<com.ruoyi.game.economy.WalletReconciliationJobs.Lease>> results=new ArrayList<>();for(int i=0;i<4;i++)results.add(workers.submit(reconciliationJobs::claim));
                for(var result:results){var lease=result.get(5,TimeUnit.SECONDS);if(lease!=null)leases.add(lease);}
            }
            assertEquals(1,leases.size());var old=leases.getFirst();jdbc.update("UPDATE wallet_reconciliation_run SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",fixture.id());
            var fresh=reconciliationJobs.claim();assertNotEquals(old.token(),fresh.token());
            assertEquals(com.ruoyi.game.economy.WalletReconciliationJobs.Step.LOST,reconciliationJobs.process(old));assertFalse(reconciliationJobs.failed(old,"OLD_WORKER"));assertFalse(reconciliationJobs.release(old));
            finish(fresh);assertEquals(4,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=?",fixture.id()));
        }
        @Test void lateCursorFailureRollsBackResultAndDoesNotSkipAccount(){
            var fixture=fixture(false);var lease=reconciliationJobs.claim();
            doThrow(new IllegalStateException("injected reconciliation cursor failure")).when(reconciliationMapper).advance(anyString(),anyString(),anyInt(),anyLong(),anyString(),anyInt(),anyInt(),any(),any());
            assertThrows(IllegalStateException.class,()->reconciliationJobs.process(lease));assertEquals(0,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=?",fixture.id()));
            assertEquals(0,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));assertEquals(fixture.uid(),number("SELECT cursor_owner FROM wallet_reconciliation_run WHERE id=?",fixture.id()));assertEquals("",jdbc.queryForObject("SELECT cursor_currency FROM wallet_reconciliation_run WHERE id=?",String.class,fixture.id()));
            assertTrue(reconciliationJobs.failed(lease,"STORAGE_FAILURE"));reset(reconciliationMapper);due(fixture);finish(reconciliationJobs.claim());
            assertEquals(4,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
        }
        @Test void fiveFailuresStopAndAuditedManualRetryPreservesProgress(){
            var fixture=fixture(false);
            doThrow(new DataAccessResourceFailureException("injected unavailable ledger scan")).when(reconciliationMapper).nextPersonal(anyLong(),anyString(),anyLong());
            for(int attempt=1;attempt<=5;attempt++){
                var lease=reconciliationJobs.claim();assertNotNull(lease);assertThrows(DataAccessResourceFailureException.class,()->reconciliationJobs.process(lease));assertTrue(reconciliationJobs.failed(lease,"STORAGE_FAILURE"));due(fixture);
            }
            assertEquals("FAILED",reconciliationMapper.metadata(fixture.id()).get("status"));assertNull(reconciliationJobs.claim());reset(reconciliationMapper);
            String key=UUID.randomUUID().toString();var first=reconciliationAdmin.retry(1,fixture.id(),"隔离数据库故障恢复验收",key);var replay=reconciliationAdmin.retry(1,fixture.id(),"隔离数据库故障恢复验收",key);
            assertEquals(first.get("operationId"),replay.get("operationId"));assertEquals(1,number("SELECT manual_retries FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            finish(reconciliationJobs.claim());assertEquals(4,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            assertEquals(1,number("SELECT COUNT(*) FROM admin_audit WHERE action='wallet:daily:retry' AND target=?",fixture.id()));
        }
        @Test void accountSnapshotStaysConsistentWhileAnotherThreadPostsMoney() throws Exception {
            var fixture=fixture(false);fund(fixture.uid(),10);var lease=reconciliationJobs.claim();CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
            doAnswer(invocation->{
                var row=jdbc.queryForMap("SELECT balance,frozen,version FROM wallet_account WHERE uid=? AND currency='card'",fixture.uid());read.countDown();
                if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("snapshot gate timed out");return row;
            }).when(walletMapper).personalView(fixture.uid(),"card");
            try(ExecutorService worker=Executors.newSingleThreadExecutor()){
                var result=worker.submit(()->reconciliationJobs.process(lease));try{assertTrue(read.await(3,TimeUnit.SECONDS));fund(fixture.uid(),5);}finally{release.countDown();}
                assertEquals(com.ruoyi.game.economy.WalletReconciliationJobs.Step.PROGRESSED,result.get(5,TimeUnit.SECONDS));
            }
            assertEquals("10",jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.actualAvailable')) FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL' AND owner_id=? AND currency='card'",String.class,fixture.id(),fixture.uid()));
            assertEquals("MATCH",jdbc.queryForObject("SELECT status FROM wallet_reconciliation_detail WHERE run_id=? AND owner_type='PERSONAL' AND owner_id=? AND currency='card'",String.class,fixture.id(),fixture.uid()));
            assertEquals(15,number("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",fixture.uid()));
        }
        @Test void lostCommitResultResumesFromPersistentCursorWithoutDuplicateResult(){
            var fixture=fixture(false);var lease=reconciliationJobs.claim();var once=new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(invocation->{
                var row=jdbc.queryForMap("SELECT balance,frozen,version FROM wallet_account WHERE uid=? AND currency='card'",fixture.uid());
                if(once.compareAndSet(false,true))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){throw new DataAccessResourceFailureException("injected lost check result after commit");}});
                return row;
            }).when(walletMapper).personalView(fixture.uid(),"card");
            assertThrows(DataAccessResourceFailureException.class,()->reconciliationJobs.process(lease));assertEquals(1,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));
            assertTrue(reconciliationJobs.failed(lease,"CHECK_RESULT_UNKNOWN"));due(fixture);finish(reconciliationJobs.claim());
            assertEquals(4,number("SELECT checked_count FROM wallet_reconciliation_run WHERE id=?",fixture.id()));assertEquals(4,number("SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=?",fixture.id()));
        }
        @Test void adminRunCommandIsIdempotentAndQueriesRemainScopedAndBounded(){
            String key=UUID.randomUUID().toString();var first=reconciliationAdmin.trigger(1,"隔离本日核对命令验收",key);var replay=reconciliationAdmin.trigger(1,"隔离本日核对命令验收",key);
            assertEquals(first.get("operationId"),replay.get("operationId"));assertEquals(1,number("SELECT COUNT(*) FROM wallet_reconciliation_run"));
            var today=reconciliationJobs.today();assertThrows(SecurityException.class,()->reconciliationAdmin.list(999,today,today,null,1,50));
            var listing=reconciliationAdmin.list(1,today,today,null,1,50);assertEquals(false,listing.get("processingEnabled"));
            var metadata=(Map<?,?>)((List<?>)listing.get("rows")).getFirst();for(String field:List.of("leaseToken","leaseUntil","cursorOwner","personalHighwater"))assertFalse(metadata.containsKey(field));
            assertThrows(IllegalArgumentException.class,()->reconciliationAdmin.list(1,today.minusDays(31),today,null,1,50));
            assertThrows(IllegalArgumentException.class,()->reconciliationAdmin.list(1,today,today,"BOGUS",1,50));
            assertThrows(IllegalArgumentException.class,()->reconciliationAdmin.list(1,today,today,null,1,201));
            doThrow(new DataAccessResourceFailureException("injected reconciliation query failure")).when(reconciliationMapper).list(any(),any(),any(),anyInt(),anyInt());
            assertThrows(com.ruoyi.game.admin.GameDataUnavailable.class,()->reconciliationAdmin.list(1,today,today,null,1,50));
        }
    }

    @Nested class WalletTransactions {
        private WalletPosting batchTransfer(long from,long to,String key){return new WalletPosting(Kind.TRANSFER,key,"fixture-v1","batch-validation",List.of(new Entry(new Account(OwnerType.PERSONAL,from,Currency.CARD),-1,0),new Entry(new Account(OwnerType.PERSONAL,to,Currency.CARD),1,0)));}
        @Test void batchCommandsForTheSameAccountObservePriorUpdatesAndReplayOriginalReceipts(){
            long uid=player();fund(uid,10);var account=personal(uid,Currency.CARD);
            var freeze=new WalletPosting(Kind.FREEZE,"freeze:"+UUID.randomUUID(),"fixture-v1","batch-validation",List.of(new Entry(account,-6,6)));
            var release=new WalletPosting(Kind.RELEASE,"release:"+UUID.randomUUID(),"fixture-v1","batch-validation",List.of(new Entry(account,2,-2)));
            var first=wallets.postAll(List.of(freeze,release));
            assertEquals(4,first.get(0).balances().getFirst().available());assertEquals(6,first.get(0).balances().getFirst().frozen());
            assertEquals(6,first.get(1).balances().getFirst().available());assertEquals(4,first.get(1).balances().getFirst().frozen());
            fund(uid,5);var replay=wallets.postAll(List.of(freeze,release));
            assertTrue(replay.stream().allMatch(WalletPostingService.Receipt::replayed));
            assertEquals(first.stream().map(WalletPostingService.Receipt::balances).toList(),replay.stream().map(WalletPostingService.Receipt::balances).toList());
            assertEquals(11,balance(uid));assertEquals(4,jdbc.queryForObject("SELECT frozen FROM wallet_account WHERE uid=? AND currency='card'",Long.class,uid));
        }
        @Test void opposingBatchInputOrdersUseOneGlobalAccountLockOrder() throws Exception {
            long a=player(),b=player(),c=player(),d=player();fund(a,100);fund(c,100);
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Future<?>> results=new ArrayList<>();
                for(int i=0;i<50;i++){
                    var first=batchTransfer(a,b,"batch:"+UUID.randomUUID());var second=batchTransfer(c,d,"batch:"+UUID.randomUUID());
                    List<WalletPosting> ordered=i%2==0?List.of(first,second):List.of(second,first);
                    results.add(workers.submit(()->wallets.postAll(ordered)));
                }
                for(var result:results)result.get(10,TimeUnit.SECONDS);
            }
            for(long uid:List.of(a,b,c,d))assertEquals(50,jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,uid));
        }
        @Test void lateSecondPostingFailureRollsBackTheEntireBatch(){
            long a=player(),b=player(),c=player(),d=player();fund(a,100);fund(c,100);
            String firstKey="batch:"+UUID.randomUUID(),secondKey="batch:"+UUID.randomUUID();
            doThrow(new IllegalStateException("injected second posting failure")).when(walletMapper).posted(anyString(),argThat(json->json!=null&&json.contains("\"owner\":"+d)),any());
            assertThrows(IllegalStateException.class,()->wallets.postAll(List.of(batchTransfer(a,b,firstKey),batchTransfer(c,d,secondKey))));
            assertEquals(100,jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,a));
            assertEquals(0,jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,b));
            assertEquals(100,jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,c));
            assertEquals(0,jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,d));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transaction WHERE business_key IN (?,?)",Long.class,firstKey,secondKey));
        }

        private long player(){return auth.verify(auth.createGuest()).uid();}
        private Account personal(long uid,Currency currency){return new Account(OwnerType.PERSONAL,uid,currency);}
        private Account system(Currency currency){return new Account(OwnerType.SYSTEM,0,currency);}
        private WalletPosting reward(long uid,long amount,String key){return new WalletPosting(Kind.REWARD,key,"test-v1","isolated-test",List.of(new Entry(personal(uid,Currency.CARD),amount,0),new Entry(system(Currency.CARD),-amount,0)));}
        private void fund(long uid,long amount){wallets.post(reward(uid,amount,"reward:"+UUID.randomUUID()));}
        private long balance(long uid){return jdbc.queryForObject("SELECT balance FROM wallet_account WHERE uid=? AND currency='card'",Long.class,uid);}
        @Test void parallelRetriesHaveOnePostingAndCanonicalEquivalentEntries() throws Exception {
            long uid=player();String key="reward:"+UUID.randomUUID();var command=reward(uid,10,key);
            Set<String> transactions=new HashSet<>();
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Callable<WalletPostingService.Receipt>> calls=new ArrayList<>();for(int n=0;n<16;n++)calls.add(()->wallets.post(command));
                for(var result:workers.invokeAll(calls))transactions.add(result.get().transactionId());
            }
            assertEquals(1,transactions.size());assertEquals(10,balance(uid));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE owner_type='PERSONAL' AND owner_id=?",Integer.class,uid));
            var equivalent=new WalletPosting(Kind.REWARD,key,"test-v1","isolated-test",List.of(new Entry(system(Currency.CARD),-10,0),new Entry(personal(uid,Currency.CARD),4,0),new Entry(personal(uid,Currency.CARD),6,0)));
            assertTrue(wallets.post(equivalent).replayed());assertEquals(10,balance(uid));
            assertThrows(IllegalArgumentException.class,()->wallets.post(reward(uid,11,key)));
        }
        @Test void opposingTransfersUseStableAccountLockOrder() throws Exception {
            long a=player(),b=player();fund(a,50);fund(b,50);
            try(ExecutorService workers=Executors.newFixedThreadPool(8)){
                List<Callable<Void>> calls=new ArrayList<>();
                for(int n=0;n<100;n++){long from=n%2==0?a:b,to=n%2==0?b:a;calls.add(()->{wallets.post(new WalletPosting(Kind.TRANSFER,"transfer:"+UUID.randomUUID(),"test-v1","isolated-transfer",List.of(new Entry(personal(from,Currency.CARD),-1,0),new Entry(personal(to,Currency.CARD),1,0))));return null;});}
                for(var result:workers.invokeAll(calls))result.get(10,TimeUnit.SECONDS);
            }
            assertEquals(50,balance(a));assertEquals(50,balance(b));assertEquals("MATCH",wallets.reconcile(personal(a,Currency.CARD)).get("status"));assertEquals("MATCH",wallets.reconcile(personal(b,Currency.CARD)).get("status"));
        }
        @Test void freezeAndReleasePreventOverspendingAndDuplicateRelease(){
            long uid=player();fund(uid,10);Account account=personal(uid,Currency.CARD);
            wallets.post(new WalletPosting(Kind.FREEZE,"freeze:"+UUID.randomUUID(),"test-v1","test-freeze",List.of(new Entry(account,-7,7))));
            assertEquals(3,balance(uid));assertEquals(7,jdbc.queryForObject("SELECT frozen FROM wallet_account WHERE uid=? AND currency='card'",Long.class,uid));
            assertThrows(GameFailure.class,()->wallets.post(new WalletPosting(Kind.FREEZE,"freeze:"+UUID.randomUUID(),"test-v1","test-freeze",List.of(new Entry(account,-4,4)))));
            var release=new WalletPosting(Kind.RELEASE,"release:"+UUID.randomUUID(),"test-v1","test-freeze",List.of(new Entry(account,7,-7)));
            wallets.post(release);assertTrue(wallets.post(release).replayed());assertEquals(10,balance(uid));
            assertEquals("MATCH",wallets.reconcile(account).get("status"));
        }
        @Test void failureAtFinalPostingMarkerRollsBackAllAccountsAndEntries(){
            long uid=player();String key="reward:"+UUID.randomUUID();long clearing=jdbc.queryForObject("SELECT balance FROM wallet_system_account WHERE currency='card'",Long.class);
            doThrow(new IllegalStateException("injected final marker failure")).when(walletMapper).posted(anyString(),anyString(),any());
            assertThrows(IllegalStateException.class,()->wallets.post(reward(uid,10,key)));
            assertEquals(0,balance(uid));assertEquals(clearing,jdbc.queryForObject("SELECT balance FROM wallet_system_account WHERE currency='card'",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transaction WHERE business_key=?",Integer.class,key));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE owner_type='PERSONAL' AND owner_id=?",Integer.class,uid));
        }
        @Test void currenciesMustBalanceIndependentlyAndClientIntegersRemainExact(){
            long uid=player();assertThrows(IllegalArgumentException.class,()->new WalletPosting(Kind.EXCHANGE,"exchange:"+UUID.randomUUID(),"test-v1","test-only-not-a-business-rate",List.of(new Entry(personal(uid,Currency.CARD),-1,0),new Entry(personal(uid,Currency.DIAMOND),7,0))));
            fund(uid,WalletPosting.MAX_CLIENT_INTEGER);String key="reward:"+UUID.randomUUID();
            assertThrows(IllegalArgumentException.class,()->wallets.post(reward(uid,1,key)));assertEquals(WalletPosting.MAX_CLIENT_INTEGER,balance(uid));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transaction WHERE business_key=?",Integer.class,key));
        }
        @Test void reconciliationDetectsManualDifferenceWithoutChangingBalance(){
            long uid=player();fund(uid,10);jdbc.update("UPDATE wallet_account SET balance=balance+1 WHERE uid=? AND currency='card'",uid);
            var report=wallets.reconcile(personal(uid,Currency.CARD));assertEquals("MISMATCH",report.get("status"));assertEquals("10",report.get("expectedAvailable"));assertEquals("11",report.get("actualAvailable"));assertEquals(11,balance(uid));
        }
        @Test void replayReturnsOriginalReceiptAfterLaterTransactions(){
            long uid=player();var original=reward(uid,10,"reward:"+UUID.randomUUID());var first=wallets.post(original);fund(uid,5);var replay=wallets.post(original);
            assertTrue(replay.replayed());assertEquals(first.transactionId(),replay.transactionId());assertEquals(first.balances(),replay.balances());assertEquals(15,balance(uid));
        }
        @Test void adminLedgerHasBoundedDatesAndAuditedIdempotentReconciliation(){
            long uid=player();fund(uid,10);Account account=personal(uid,Currency.CARD);java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            var list=walletAdmin.ledger(1,account,today,today,1,50);assertEquals(1L,list.get("total"));assertEquals("10",((Map<?,?>)list.get("account")).get("available"));
            assertThrows(IllegalArgumentException.class,()->walletAdmin.ledger(1,account,today.minusDays(31),today,1,50));
            assertThrows(SecurityException.class,()->walletAdmin.ledger(900002,account,today,today,1,50));
            String key=UUID.randomUUID().toString();var report=walletAdmin.reconcile(1,account,"隔离环境人工余额核对",key);var replay=walletAdmin.reconcile(1,account,"隔离环境人工余额核对",key);
            assertEquals(report.get("operationId"),replay.get("operationId"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_reconciliation WHERE owner_type='PERSONAL' AND owner_id=?",Integer.class,uid));
            assertEquals(10,balance(uid));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='wallet:reconcile' AND target=?",Integer.class,"PERSONAL:"+uid+":card"));
            assertThrows(IllegalArgumentException.class,()->walletAdmin.account("SYSTEM",0,"card"));
        }

    }

}
