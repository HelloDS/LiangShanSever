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
        properties.add("game.pii-key",SecureTokens::create);
    }
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
}
