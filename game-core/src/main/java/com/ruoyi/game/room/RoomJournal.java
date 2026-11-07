package com.ruoyi.game.room;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.economy.*;
import com.ruoyi.game.outbox.OutboxStore;
import com.ruoyi.game.persistence.mapper.RoomJournalMapper;
import com.ruoyi.game.persistence.mapper.ClubMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.room.RoomPersistence.Decision;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 同一房间池事务中的事件、检查点、占座、账本和 outbox。没有公开写入控制器。 */
@Service
public class RoomJournal {
    public static final int MAX_RECOVERY_EVENTS=2048;
    private final RoomTransactions transactions;
    private final RoomJournalMapper mapper;
    private final GameJsonCodec codec;
    private final GuestAuthService auth;
    private final WalletPostingService wallets;
    private final OutboxStore outbox;
    private final Clock clock;
    private final LiveRoomFinalizer finalizer;
    private final ClubMapper clubs;
    public RoomJournal(RoomTransactions transactions,RoomJournalMapper mapper,GameJsonCodec codec,GuestAuthService auth,
            WalletPostingService wallets,OutboxStore outbox,Clock clock,LiveRoomFinalizer finalizer,ClubMapper clubs){
        this.transactions=transactions;this.mapper=mapper;this.codec=codec;this.auth=auth;this.wallets=wallets;this.outbox=outbox;this.clock=clock;
        this.finalizer=finalizer;
        this.clubs=clubs;
    }
    private record Encoded(String beforeHash,String afterHash,String payload,String snapshot,String requestHash,LocalDateTime occurred){ }
    private Encoded encode(RoomJournalCommand command){
        String previous=codec.canonicalObject(command.previous().state()),snapshot=codec.canonicalObject(command.candidate().state());
        String payload=codec.canonicalObject(command.eventJson()),before=hash(previous),after=hash(snapshot);
        Instant occurred=command.occurredAt().truncatedTo(ChronoUnit.MICROS);
        Map<String,Object> body=new LinkedHashMap<>();body.put("room",command.previous().roomId());body.put("epoch",command.previous().ownerEpoch());
        body.put("previousVersion",command.previous().version());body.put("previousRound",command.previous().round());body.put("previousPhase",command.previous().phase());body.put("beforeHash",before);body.put("afterHash",after);
        body.put("round",command.candidate().round());body.put("phase",command.candidate().phase());body.put("kind",command.eventKind());
        body.put("event",payload);body.put("schema",command.schemaVersion());body.put("engine",command.engineVersion());body.put("rule",command.ruleVersion());
        body.put("actor",command.actor());body.put("postings",command.postings());body.put("seat",command.seatChange());body.put("checkpoint",command.checkpoint());body.put("occurred",occurred.toString());
        return new Encoded(before,after,payload,snapshot,hash(codec.canonicalObject(codec.write(body))),LocalDateTime.ofInstant(occurred,ZoneOffset.UTC));
    }
    public Decision commit(RoomJournalCommand command){
        Encoded encoded;
        try{encoded=encode(command);}catch(IllegalArgumentException failure){return Decision.NOT_COMMITTED;}
        try{return transactions.execute(()->{
            try{return append(command,encoded);}
            catch(GameFailure|DataIntegrityViolationException|IllegalArgumentException failure){throw new RejectedWork(failure);}
        });}
        catch(RejectedWork failure){return Decision.NOT_COMMITTED;}
        // 连接/提交结果异常交由调用方按 UNKNOWN 核对，不提前返回业务失败。
    }
    /** 只标记事务工作体内的已知拒绝；提交/afterCommit 抛出的同类异常仍属于未知结果。 */
    private static final class RejectedWork extends RuntimeException{RejectedWork(RuntimeException cause){super("房间事务工作已拒绝",cause);}}
    private Decision append(RoomJournalCommand command,Encoded encoded){
        var before=command.previous();var candidate=command.candidate();String room=before.roomId();
        // 与持久游客认证一致：先凭据，再角色、账号；与后台玩家锁序相容，已提交重试不重做效果。
        if(command.actor()!=null){mapper.lockCredential(command.actor().credentialId());mapper.lockRole(command.actor().uid(),command.actor().accountId());mapper.lockAccount(command.actor().accountId());}
        var runtime=mapper.lock(room);
        if(!compatible(runtime,command))return Decision.OWNER_CHANGED;
        var existing=mapper.selectEvent(room,candidate.version());
        if(existing!=null)return committed(runtime,existing,command,encoded);
        if(number(runtime,"version")!=before.version()||!encoded.beforeHash().equals(runtime.get("stateHash"))
                ||number(runtime,"roundNo")!=before.round()||!before.phase().equals(runtime.get("phase")))return Decision.OWNER_CHANGED;
        if("CLOSED".equals(runtime.get("status")))return Decision.NOT_COMMITTED;
        if(command.eventKind().startsWith("TEST_")&&!Boolean.TRUE.equals(runtime.get("isTest")))throw new IllegalArgumentException("测试事件不能写入正式房间");
        if(command.actor()!=null)auth.verifySession(command.actor());
        if("FORCE_CLOSE".equals(command.eventKind()) && !(runtime.get("clubId") instanceof Number))throw new GameFailure(-90,"强制解散仅用于亲友圈房间");
        if(LiveRoomEngine.VERSION.equals(command.engineVersion()) && runtime.get("clubId") instanceof Number club && command.actor()!=null) {
            var circle=clubs.lock(club.longValue());
            String role=clubs.memberRole(club.longValue(),command.actor().uid());
            if(circle==null || !"ACTIVE".equals(circle.get("status")) || role==null)throw new GameFailure(-90,"亲友圈成员身份已经改变");
            if(Set.of("CLOSE","FORCE_CLOSE").contains(command.eventKind()) && !Set.of("OWNER","MANAGER").contains(role))throw new GameFailure(-90,"没有房间管理权限");
            if(!command.postings().isEmpty())for(long uid:mapper.participants(room)) {
                if(clubs.memberRole(club.longValue(),uid)==null || clubs.activePlayer(uid)!=1)throw new GameFailure(-90,"参与者状态已经改变");
            }
        }
        validatePostings(command,runtime);
        LocalDateTime now=now();
        if(command.seatChange()!=null)seat(command,runtime,now);
        if(!command.postings().isEmpty())wallets.postAll(command.postings());
        Map<String,Object> values=new HashMap<>();
        values.put("room",room);values.put("version",candidate.version());values.put("previousVersion",before.version());values.put("epoch",before.ownerEpoch());
        values.put("round",candidate.round());values.put("phase",candidate.phase());values.put("kind",command.eventKind());values.put("payload",encoded.payload());
        values.put("requestHash",encoded.requestHash());values.put("beforeHash",encoded.beforeHash());values.put("afterHash",encoded.afterHash());
        values.put("schema",command.schemaVersion());values.put("engine",command.engineVersion());values.put("occurred",encoded.occurred());values.put("now",now);values.put("snapshot",encoded.snapshot());
        mapper.insertEvent(values);
        if(mapper.advance(values)!=1)throw new IllegalStateException("房间版本写入冲突");
        if(command.checkpoint()&&mapper.checkpoint(values)!=1)throw new IllegalStateException("房间检查点写入冲突");
        String status=switch(candidate.phase()){case "WAITING","READY"->"WAITING";case "ROUND_FINISHED"->"ROUND_FINISHED";case "CLOSED"->"CLOSED";default->"PLAYING";};
        // 大多数出牌仍为 PLAYING，不为每个动作重复写同一条后台元数据。
        // 阶段真的改变时必须确认写入，避免日志已前进而列表仍显示旧状态。
        if(!status.equals(runtime.get("status"))&&mapper.status(room,status,now)!=1)throw new IllegalStateException("房间状态写入未确认");
        if("CLOSED".equals(status)){finalizer.closing(command,now);mapper.releasePlayers(room);if(mapper.releaseCode(room)!=1)throw new IllegalStateException("活跃房号释放异常");}
        outbox.roomCommitted(room,candidate.version(),command.ruleVersion(),command.occurredAt());
        return Decision.COMMITTED;
    }
    public Decision resolve(RoomJournalCommand command){
        Encoded encoded;
        try{encoded=encode(command);}catch(IllegalArgumentException failure){return Decision.NOT_COMMITTED;}
        return transactions.execute(()->{
            var runtime=mapper.lock(command.previous().roomId());
            if(!compatible(runtime,command))return Decision.OWNER_CHANGED;
            var event=mapper.selectEvent(command.previous().roomId(),command.candidate().version());
            if(event!=null)return committed(runtime,event,command,encoded);
            return number(runtime,"version")==command.previous().version()&&encoded.beforeHash().equals(runtime.get("stateHash"))?Decision.NOT_COMMITTED:Decision.OWNER_CHANGED;
        });
    }
    private boolean compatible(Map<String,Object> runtime,RoomJournalCommand command){
        return runtime!=null&&"6003".equals(runtime.get("gameId"))&&number(runtime,"ownerEpoch")==command.previous().ownerEpoch()&&number(runtime,"schemaVersion")==command.schemaVersion()
                &&command.engineVersion().equals(runtime.get("engineVersion"))&&command.ruleVersion().equals(runtime.get("ruleVersion"));
    }
    private Decision committed(Map<String,Object> runtime,Map<String,Object> event,RoomJournalCommand command,Encoded encoded){
        return encoded.requestHash().equals(event.get("requestHash"))&&number(runtime,"version")==command.candidate().version()
                &&encoded.afterHash().equals(runtime.get("stateHash"))?Decision.COMMITTED:Decision.OWNER_CHANGED;
    }
    private void validatePostings(RoomJournalCommand command,Map<String,Object> runtime){
        if(command.postings().isEmpty())return;
        String room=command.previous().roomId();
        Set<Long> participants=new HashSet<>(mapper.participants(room));
        if(runtime.get("payerUid") instanceof Number payer)participants.add(payer.longValue());
        if(command.seatChange()!=null&&command.seatChange().action()==RoomJournalCommand.SeatAction.JOIN)participants.add(command.seatChange().uid());
        for(WalletPosting posting:command.postings()){
            if(!posting.businessKey().startsWith("room:"+room+":")||!posting.reference().equals(room)||!posting.ruleVersion().equals(command.ruleVersion()))
                throw new IllegalArgumentException("房间资金命令缺少一致的来源和规则");
            for(var entry:posting.entries()){
                var account=entry.account();
                if(account.type()==WalletPosting.OwnerType.PERSONAL&&!participants.contains(account.ownerId()))throw new IllegalArgumentException("资产主体不属于房间参与者或已声明付款人");
                if(account.type()==WalletPosting.OwnerType.CLUB&&(!(runtime.get("clubId") instanceof Number club)||club.longValue()!=account.ownerId()))throw new IllegalArgumentException("圈资产主体不属于此房间");
            }
        }
    }
    private void seat(RoomJournalCommand command,Map<String,Object> runtime,LocalDateTime now){
        if(!"WAITING".equals(runtime.get("status")))throw new IllegalArgumentException("仅等待阶段可改变占座");
        var seat=command.seatChange();String room=command.previous().roomId();
        if(seat.action()==RoomJournalCommand.SeatAction.JOIN){
            if(mapper.binding(seat.uid())!=null)throw new IllegalArgumentException("玩家已经占用其他房间或座位");
            mapper.bind(seat.uid(),room,seat.seat(),now);mapper.seat(seat.uid(),room,seat.seat(),now);
        }else{
            if(mapper.unbind(seat.uid(),room)!=1||mapper.unseat(seat.uid(),room)!=1)throw new IllegalArgumentException("玩家未在此房间占座");
        }
    }
    /** 取得新的持久所有者围栏并返回私有恢复材料；不在恢复时重做钱包或 outbox。 */
    public record Recovery(String roomId,long ownerEpoch,long version,int round,String phase,long snapshotSeq,int snapshotRound,
            String snapshotPhase,String snapshotJson,String snapshotHash,String stateHash,String gameId,int schemaVersion,String engineVersion,String ruleVersion,List<Map<String,Object>> events){
        public Recovery{events=events.stream().map(Map::copyOf).toList();}
    }
    public Recovery claim(String room,String node,int schema,String engine){
        if(room==null||!room.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||node==null||!node.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("房间所有者标识无效");
        return transactions.execute(()->{
            var runtime=mapper.lock(room);if(runtime==null||"CLOSED".equals(runtime.get("status")))throw new RoomRejected("ROOM_CLOSED");
            if(!"6003".equals(runtime.get("gameId"))||number(runtime,"schemaVersion")!=schema||!engine.equals(runtime.get("engineVersion")))throw new RoomRejected("UNSUPPORTED_ENGINE");
            long snapshotSeq=number(runtime,"snapshotSeq"),version=number(runtime,"version");
            if(version-snapshotSeq>MAX_RECOVERY_EVENTS)throw new RoomRejected("RECOVERY_BUDGET");
            var budget=mapper.recoveryBudget(room,snapshotSeq);
            if(number(budget,"total")>MAX_RECOVERY_EVENTS||number(budget,"bytes")>8L*1024*1024)throw new RoomRejected("RECOVERY_BUDGET");
            var events=mapper.tail(room,snapshotSeq);
            if(events.size()!=version-snapshotSeq)throw new RoomRejected("EVENT_GAP");
            String chain=(String)runtime.get("snapshotHash");long sequence=snapshotSeq;
            if(!chain.equals(hash(codec.canonicalObject((String)runtime.get("snapshotJson")))))throw new RoomRejected("SNAPSHOT_CORRUPT");
            for(var event:events){
                if(number(event,"eventSeq")!=++sequence||!chain.equals(event.get("beforeHash"))||number(event,"schemaVersion")!=schema||!engine.equals(event.get("engineVersion")))throw new RoomRejected("EVENT_GAP");
                chain=(String)event.get("afterHash");
            }
            if(!chain.equals(runtime.get("stateHash")))throw new RoomRejected("STATE_HASH_MISMATCH");
            long epoch=Math.incrementExact(number(runtime,"ownerEpoch"));
            if(mapper.owner(room,epoch-1,node,now())!=1)throw new IllegalStateException("房间所有者变更冲突");
            mapper.ownership(room,epoch,node,now());
            return new Recovery(room,epoch,version,(int)number(runtime,"roundNo"),(String)runtime.get("phase"),snapshotSeq,(int)number(runtime,"snapshotRound"),
                    (String)runtime.get("snapshotPhase"),(String)runtime.get("snapshotJson"),(String)runtime.get("snapshotHash"),(String)runtime.get("stateHash"),(String)runtime.get("gameId"),schema,engine,(String)runtime.get("ruleVersion"),events);
        });
    }
    public String stateHash(String stateJson){return hash(codec.canonicalObject(stateJson));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public static String hash(String json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
}
