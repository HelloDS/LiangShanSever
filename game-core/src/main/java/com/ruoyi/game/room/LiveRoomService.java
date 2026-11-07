package com.ruoyi.game.room;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.club.ClubService.Notification;
import com.ruoyi.game.economy.PlayerOperations;
import com.ruoyi.game.games.landlord.*;
import com.ruoyi.game.lobby.PlayerService;
import com.ruoyi.game.persistence.mapper.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import static com.ruoyi.game.room.LiveRoomState.*;

/** 实际圈房间入口。分片负责顺序，独立房间池负责事务，推送只使用提交后的状态。 */
@Service
public class LiveRoomService implements InitializingBean,DisposableBean {
    public record Reply(Map<String,Object> ack,List<Notification> notifications) {
        public Reply { notifications=List.copyOf(notifications); }
    }
    public record TimedNotice(List<Notification> notifications) { }
    private final LiveRoomMapper mapper;
    private final RoomJournalMapper journalMapper;
    private final ClubMapper clubs;
    private final PartnerMapper partners;
    private final PlayerService players;
    private final GuestAuthService auth;
    private final PlayerOperations operations;
    private final RoomTransactions transactions;
    private final RoomJournal journal;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final String node=UUID.randomUUID().toString();
    private final ConcurrentMap<String,RoomMailboxes.Handle> handles=new ConcurrentHashMap<>();
    private final LiveRoomEngine engine;
    private final RoomMailboxes<String,LiveRoomEngine.Command,LiveRoomEngine.Event> rooms;

    public LiveRoomService(LiveRoomMapper mapper,RoomJournalMapper journalMapper,ClubMapper clubs,PartnerMapper partners,
            PlayerService players,GuestAuthService auth,PlayerOperations operations,RoomTransactions transactions,
            RoomJournal journal,GameJsonCodec codec,Clock clock,ApplicationEventPublisher events) {
        this.mapper=mapper;this.journalMapper=journalMapper;this.clubs=clubs;this.partners=partners;
        this.players=players;this.auth=auth;this.operations=operations;this.transactions=transactions;
        this.journal=journal;this.codec=codec;this.clock=clock;this.events=events;
        engine=new LiveRoomEngine(codec);
        var persistence=new JournalRoomPersistence<String,LiveRoomEngine.Command,LiveRoomEngine.Event>(journal,this::plan);
        rooms=new RoomMailboxes<>(new RoomMailboxes.Config(4,256,1024,16,4,128,50),persistence,clock);
    }
    private RoomJournalCommand plan(RoomPersistence.Write<String,LiveRoomEngine.Command,LiveRoomEngine.Event> write) {
        var expected=engine.apply(write.previous(),write.command());
        if(!expected.state().equals(write.candidate().state()) || !expected.event().equals(write.event()))throw new IllegalArgumentException("房间计划与确定性动作不一致");
        var event=write.event();
        var payload=Map.of("state",codec.object(write.candidate().state()),"tag",event.tag(),"sender",event.sender());
        return new RoomJournalCommand(write.previous(),write.candidate(),event.kind(),codec.write(payload),LiveRoomEngine.RULE,2,LiveRoomEngine.VERSION,
                write.command().actor(),event.postings(),event.seat(),true,write.occurredAt());
    }
    @Override public void afterPropertiesSet() {
        for(String id:mapper.active(LiveRoomEngine.VERSION))own(id);
    }
    private RoomMailboxes.Handle own(String id) {
        return handles.computeIfAbsent(id,key->{
            var recovered=journal.claim(key,node,2,LiveRoomEngine.VERSION);
            if(recovered.snapshotSeq()!=recovered.version() || !recovered.snapshotHash().equals(journal.stateHash(recovered.snapshotJson())))throw new RoomRejected("SNAPSHOT_CORRUPT");
            var state=codec.value(recovered.snapshotJson(),LiveRoomState.class);
            if(!state.round().phase().equals(recovered.phase()) || state.round().number()!=recovered.round())throw new RoomRejected("SNAPSHOT_CORRUPT");
            return rooms.register(new RoomSnapshot<>(key,recovered.ownerEpoch(),recovered.version(),recovered.round(),recovered.phase(),recovered.snapshotJson()),engine);
        });
    }
    public Reply create(GuestIdentity actor,String game,Map<String,Object> raw,String operation) {
        var options=options(game,raw);
        Long club=options.get("club_id") instanceof Number value?value.longValue():null;
        boolean automatic=Boolean.TRUE.equals(options.get("morenroom"));
        var inserted=new java.util.concurrent.atomic.AtomicBoolean();
        var ack=transactions.execute(()->operations.execute(actor.uid(),operation,Map.of("kind","roomCreate","game",game,"options",options),()->{
            journalMapper.lockCredential(actor.credentialId());journalMapper.lockRole(actor.uid(),actor.accountId());journalMapper.lockAccount(actor.accountId());
            auth.verifySession(actor);
            Map<String,Object> circle=club==null?null:access(actor.uid(),club,true,false);
            boolean manager=club!=null && manager(actor.uid(),club);
            if(circle!=null && Boolean.TRUE.equals(circle.get("onlyManagerOpenRoom")) && !manager && !automatic)throw denied();
            if(automatic) {
                if(club==null || mapper.config(club)==null || !Boolean.TRUE.equals(mapper.config(club).get("autoCreate")))throw new GameFailure(-18,"默认自动开房尚未开启");
                var existing=availableDefault(club);
                if(existing!=null)return Map.of("state",0,"roomId",existing);
            }
            boolean seated=club==null || !manager;
            if(seated && mapper.binding(actor.uid())!=null)throw new GameFailure(-10,"玩家已有未结束房间");
            String id=insert(actor.uid(),actor.uid(),actor.test(),club,automatic?defaultOptions(club):options,automatic,seated);
            inserted.set(true);return Map.of("state",0,"roomId",id);
        }));
        String id=(String)ack.get("roomId");
        if("CLOSED".equals(mapper.byId(id).get("status")))throw new GameFailure(-19,"此创建操作对应的房间已经结束");
        var handle=own(id);var state=state(handle);
        var response=new LinkedHashMap<String,Object>();response.put("state",0);response.put("room",LiveRoomProjection.room(state));response.put("role",players.ownRole(actor.uid()));
        return new Reply(response,inserted.get()?circleNotice(state,"club_createRoom",actor.uid()):List.of());
    }
    private String insert(long owner,long creator,boolean test,Long club,Map<String,Object> options,boolean defaultRoom,boolean seated) {
        String id=UUID.randomUUID().toString(),code=allocateCode();
        int rounds=((Number)options.get("playNumber")).intValue();
        var seats=seated?new Seats(Map.of(0,creator),Map.of(creator,players.publicRole(creator))):new Seats(Map.of(),Map.of());
        var state=new LiveRoomState(new Details(code,owner,club,rounds,Boolean.TRUE.equals(options.get("aa")),options),seats,
                new Round(0,"WAITING",Set.of(),null,Map.of(),0,0,null,Map.of(),false,clock.millis()+30*60_000),seated?Map.of(creator,0L):Map.of(),Map.of(),null);
        String json=codec.write(state);
        var values=new HashMap<String,Object>();
        values.put("id",id);values.put("code",code);values.put("owner",owner);values.put("creator",creator);
        values.put("payer",club==null?owner:null);values.put("club",club);values.put("options",codec.write(options));
        values.put("rule",LiveRoomEngine.RULE);values.put("test",test);values.put("defaultRoom",defaultRoom);values.put("now",now());
        values.put("node",node);values.put("hash",journal.stateHash(json));values.put("snapshot",json);values.put("engine",LiveRoomEngine.VERSION);
        mapper.create(values);mapper.activate(code,id);mapper.runtime(values);journalMapper.ownership(id,1,node,now());
        if(seated){journalMapper.bind(creator,id,0,now());journalMapper.seat(creator,id,0,now());}
        return id;
    }
    private String allocateCode() {
        int code=mapper.nextCode();
        for(int attempt=0;attempt<1024;attempt++) {
            String value=Integer.toString(code);code=code==999999?100000:code+1;
            if(mapper.occupied(value)==null){mapper.advanceCode(code);return value;}
        }
        throw new GameFailure(-10,"可用房号繁忙，请稍后重试");
    }
    public CompletionStage<Reply> join(GuestIdentity actor,String code) {
        var row=mapper.byCode(code);if(row==null)throw new GameFailure(-19,"房间不存在或已结束");
        var state=codec.value((String)row.get("snapshot"),LiveRoomState.class);
        if(state.details().club()!=null)access(actor.uid(),state.details().club(),false,false);
        String id=(String)row.get("roomId");
        if(id.equals(mapper.binding(actor.uid())))return CompletableFuture.completedFuture(joined(state));
        var command=command(actor,"JOIN",null,players.publicRole(actor.uid()),Map.of(),0,false);
        return submit(id,command).thenApply(next->{var reply=joined(next);var notices=new ArrayList<>(reply.notifications());notices.addAll(changed(next,"joinRoom",actor.uid()));return new Reply(reply.ack(),notices);});
    }
    private Reply joined(LiveRoomState state){return new Reply(Map.of("state",0,"room",LiveRoomProjection.room(state),"roles",state.seats().roles(),"leaveGames",List.of()),List.of());}
    public CompletionStage<Reply> leave(GuestIdentity actor) {
        String id=bound(actor.uid());
        return submit(id,command(actor,"LEAVE",null,Map.of(),Map.of(),0,false)).thenApply(state->new Reply(Map.of("state",0),changed(state,"leaveRoom",actor.uid())));
    }
    public CompletionStage<Reply> close(GuestIdentity actor,String code) {
        String id=code==null?bound(actor.uid()):roomId(code);
        var current=state(own(id));
        boolean authorized=current.details().owner()==actor.uid();
        if(current.details().club()!=null){access(actor.uid(),current.details().club(),false,false);authorized=manager(actor.uid(),current.details().club());}
        return submit(id,command(actor,"CLOSE",null,Map.of(),Map.of(),0,authorized)).thenApply(state->new Reply(Map.of("state",0,"role",players.ownRole(actor.uid())),changed(state,"disbandRoom",actor.uid())));
    }
    public CompletionStage<Reply> action(GuestIdentity actor,LandlordAction action) {
        String id=bound(actor.uid());var current=state(own(id));
        var affiliations=new LinkedHashMap<Long,Long>();
        long root=current.details().club()==null?current.details().owner():((Number)clubs.club(current.details().club()).get("owner")).longValue();
        if(action instanceof LandlordAction.Ready && current.partners().isEmpty())for(long uid:current.seats().ordered()) {
            Long parent=current.details().club()==null?null:partners.parent(current.details().club(),uid);
            affiliations.put(uid,parent!=null?parent:current.details().club()!=null && partners.exists(current.details().club(),uid)?uid:root);
        }
        return submit(id,command(actor,"ACTION",action,Map.of(),affiliations,0,false)).thenApply(next->{
            String tag=action instanceof LandlordAction.Ready?next.round().number()>current.round().number()?"startGame":"ready":action instanceof LandlordAction.Bid?"score":action instanceof LandlordAction.Pass?"buchu":action instanceof LandlordAction.ToggleAuto?"auto":"chupai";
            return new Reply(Map.of("state",0),changed(next,tag,actor.uid()));
        });
    }
    public CompletionStage<Reply> vote(GuestIdentity actor,int value) {
        return submit(bound(actor.uid()),command(actor,"VOTE",null,Map.of(),Map.of(),value,false)).thenApply(next->new Reply(Map.of("state",0),changed(next,"voteEndGame",actor.uid())));
    }
    public Reply initialize(long uid) {
        var state=state(own(bound(uid)));
        return new Reply(Map.of("state",0),List.of(new Notification(List.of(uid),gamePacket(state,uid,"initGame",uid))));
    }
    public Reply voteStatus(long uid) {
        String id=mapper.binding(uid);
        if(id==null)return new Reply(Map.of("state",0),List.of());
        var state=state(own(id));var notices=new ArrayList<Notification>();
        if(state.vote()!=null)for(long sender:state.vote().answers().keySet())notices.add(new Notification(List.of(uid),votePacket(state,sender)));
        return new Reply(Map.of("state",0),notices);
    }
    public Map<String,Object> returning(long uid) {
        String id=mapper.binding(uid);var result=new LinkedHashMap<String,Object>();result.put("state",0);
        if(id==null){result.put("room",null);return result;}
        var state=state(own(id));result.put("room",LiveRoomProjection.room(state));result.put("roles",state.seats().roles());result.put("leaveGames",List.of());return result;
    }
    public Reply defaultConfig(long actor,long club) {
        access(actor,club,false,false);var row=mapper.config(club);
        var response=new LinkedHashMap<String,Object>();response.put("state",0);
        response.put("config",row==null?null:Map.of("club_id",club,"game_id",row.get("gameId"),"create_data",codec.object((String)row.get("options")),"auto_create",Boolean.TRUE.equals(row.get("autoCreate"))));
        return new Reply(response,List.of());
    }
    public Reply configure(long actor,long club,String game,Map<String,Object> raw) {
        var normalized=new LinkedHashMap<>(raw);normalized.put("club_id",club);normalized.remove("morenroom");
        var config=options(game,normalized);
        transactions.execute(()->{access(actor,club,true,true);mapper.configure(club,codec.write(config),actor,now());clubs.event(club,actor,"PLAYER",null,"ROOM_CONFIG",codec.write(config),now());return null;});
        return defaultConfig(actor,club);
    }
    public Reply automatic(long actor,long club,boolean enabled) {
        transactions.execute(()->{access(actor,club,true,true);if(mapper.automatic(club,enabled,actor,now())!=1)throw new GameFailure(-4,"请先设置默认玩法");clubs.event(club,actor,"PLAYER",null,"ROOM_AUTO",codec.write(Map.of("enabled",enabled)),now());return null;});
        if(enabled)replenish(club);
        return defaultConfig(actor,club);
    }
    public Reply circleList(long actor,long club) {
        access(actor,club,false,false);var list=new LinkedHashMap<String,Object>();String defaultCode=null;
        for(var row:mapper.circleRooms(club)) {
            list.put((String)row.get("code"),"WAITING".equals(row.get("status"))?1:2);
            if(Boolean.TRUE.equals(row.get("isDefault")) && "WAITING".equals(row.get("status")) && ((Number)row.get("players")).intValue()<3)defaultCode=(String)row.get("code");
        }
        var red=new LinkedHashMap<String,Object>();
        if(manager(actor,club)){if(clubs.applicationCount(club,"JOIN")>0)red.put("apply",1);if(clubs.applicationCount(club,"LEAVE")>0)red.put("applyLeave",1);}
        var ack=new LinkedHashMap<String,Object>();ack.put("state",0);ack.put("list",list);ack.put("default_room",defaultCode);ack.put("automatic_room_enabled",true);ack.put("redPoints",red);
        return new Reply(ack,List.of());
    }
    public Reply infos(long actor,List<String> codes) {
        if(codes.size()>6 || new HashSet<>(codes).size()!=codes.size())throw new GameFailure(-4,"房间页参数无效");
        var metadata=new LinkedHashMap<String,Object>();var roles=new LinkedHashMap<Long,Object>();
        for(String code:codes) {
            var row=mapper.byCode(code);if(row==null)continue;
            var state=state(own((String)row.get("roomId")));
            if(state.details().club()==null)throw denied();access(actor,state.details().club(),false,false);
            metadata.put(code,LiveRoomProjection.room(state));roles.putAll(state.seats().roles());
        }
        return new Reply(Map.of("state",0,"room",metadata,"roles",roles),List.of());
    }
    private Map<String,Object> access(long actor,long club,boolean lock,boolean manage) {
        var circle=lock?clubs.lock(club):clubs.club(club);
        if(circle==null || !"ACTIVE".equals(circle.get("status")))throw new GameFailure(-38,"亲友圈不可用");
        if(clubs.memberRole(club,actor)==null || manage && !manager(actor,club))throw denied();
        return circle;
    }
    private boolean manager(long actor,long club){return Set.of("OWNER","MANAGER").contains(Objects.toString(clubs.memberRole(club,actor),""));}
    private static GameFailure denied(){return new GameFailure(-90,"没有此房间的操作权限");}
    private String roomId(String code){var row=mapper.byCode(code);if(row==null)throw new GameFailure(-19,"房间不存在或已结束");return (String)row.get("roomId");}
    private String bound(long uid){String id=mapper.binding(uid);if(id==null)throw new GameFailure(-19,"玩家当前未在房间内");return id;}
    private LiveRoomState state(RoomMailboxes.Handle handle){return codec.value(rooms.snapshot(handle).state(),LiveRoomState.class);}
    private LiveRoomEngine.Command command(GuestIdentity actor,String kind,LandlordAction action,Map<String,Object> role,Map<Long,Long> affiliations,int vote,boolean authorized) {
        return new LiveRoomEngine.Command(actor,kind,action,role,affiliations,action instanceof LandlordAction.Ready?LandlordDeck.deal():null,clock.millis(),vote,authorized);
    }
    private CompletionStage<LiveRoomState> submit(String id,LiveRoomEngine.Command command) {
        return rooms.submit(own(id),command).thenApply(snapshot->codec.value(snapshot.state(),LiveRoomState.class)).exceptionally(error->{
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof RoomRejected rejected)throw new GameFailure(-10,switch(rejected.code()) {
                case "ROOM_FULL" -> "房间已满或已经开始";
                case "NOT_YOUR_TURN" -> "尚未轮到你操作";
                case "FORBIDDEN" -> "没有此房间的操作权限";
                case "VOTE_REQUIRED" -> "游戏进行中，请通过牌桌投票解散";
                case "ALREADY_READY" -> "玩家已经准备";
                case "BID_TOO_LOW" -> "叫分必须高于当前最高叫分";
                case "GAME_ALREADY_STARTED" -> "游戏已经开始，请通过投票解散";
                default -> "操作未完成，请刷新牌桌状态后重试";
            });
            throw new CompletionException(cause);
        });
    }
    private List<Notification> changed(LiveRoomState state,String tag,long sender) {
        var notices=new ArrayList<Notification>();
        boolean game=Set.of("ready","startGame","score","chupai","buchu","auto").contains(tag);
        for(long uid:state.seats().ordered()) {
            Map<String,Object> packet;
            if(game)packet=gamePacket(state,uid,tag,sender);
            else if("voteEndGame".equals(tag))packet=votePacket(state,sender);
            else packet=Map.of("cmd",tag,"room",LiveRoomProjection.room(state),"roles",state.seats().roles(),"sender",sender);
            notices.add(new Notification(List.of(uid),packet));
            if("CLOSED".equals(state.round().phase()) && "voteEndGame".equals(tag))notices.add(new Notification(List.of(uid),Map.of("cmd","disbandRoom","sender",sender)));
        }
        String circleTag=switch(tag){case "joinRoom"->"club_joinRoom";case "leaveRoom"->"club_leaveRoom";case "disbandRoom","roomTimeOver"->"club_disbandRoom";case "startGame"->"club_gameStart";case "chupai"->"CLOSED".equals(state.round().phase())?"club_gameOver":"";default->"";};
        if("CLOSED".equals(state.round().phase()))circleTag="club_disbandRoom";
        if(!circleTag.isEmpty())notices.addAll(circleNotice(state,circleTag,sender));
        return notices;
    }
    private Map<String,Object> gamePacket(LiveRoomState state,long viewer,String tag,long sender) {
        var packet=new LinkedHashMap<String,Object>();packet.put("cmd","gameMessage");packet.put("tag",tag);packet.put("sender",sender);packet.put("uid",sender);
        packet.put("game",LiveRoomProjection.game(state,viewer));packet.put("cardsInfo",LiveRoomProjection.cards(state,viewer));
        packet.put("card",LiveRoomProjection.cards(state,viewer).get(Long.toString(viewer)));
        packet.put("currentUid",state.round().currentUid());packet.put("scoreInfo",state.round().bids());
        if("chupai".equals(tag) && state.round().play()!=null && state.round().play().finished())packet.put("endData",Map.of("game",LiveRoomProjection.game(state,viewer)));
        return packet;
    }
    private Map<String,Object> votePacket(LiveRoomState state,long sender) {
        var packet=new LinkedHashMap<String,Object>();packet.put("cmd","voteEndGame");packet.put("sender",sender);
        packet.put("room",LiveRoomProjection.room(state));packet.put("roles",state.seats().roles());
        packet.put("result",state.vote()==null?0:state.vote().answers().getOrDefault(sender,0));
        packet.put("allChoose",state.vote()==null || "CLOSED".equals(state.round().phase()));
        packet.put("voteEndGameTime",state.vote()==null?0:Math.max(0,(state.vote().until()-clock.millis())/1000));return packet;
    }
    private List<Notification> circleNotice(LiveRoomState state,String cmd,long sender) {
        Long club=state.details().club();if(club==null)return List.of();
        String defaultId=availableDefault(club);String defaultCode=defaultId==null?null:(String)mapper.byId(defaultId).get("code");
        var packet=new LinkedHashMap<String,Object>();packet.put("cmd",cmd);packet.put("club_id",club);
        packet.put("room",LiveRoomProjection.room(state));packet.put("roles",state.seats().roles());
        packet.put("room_id",state.details().code());
        var role=state.seats().roles().get(sender);
        if(role!=null)packet.put("role",role);
        // 原大厅每次房间变更会同步 default_room。无可用桌时明确给 null。
        packet.put("default_room",defaultCode);
        return List.of(new Notification(clubs.members(club),packet));
    }
    private String availableDefault(long club) {
        return mapper.circleRooms(club).stream().filter(row->Boolean.TRUE.equals(row.get("isDefault")) && "WAITING".equals(row.get("status")) && ((Number)row.get("players")).intValue()<3).map(row->(String)row.get("roomId")).findFirst().orElse(null);
    }
    private Map<String,Object> defaultOptions(long club){return codec.object((String)mapper.config(club).get("options"));}
    private void replenish(long club) {
        String id=transactions.execute(()->{
            var circle=clubs.lock(club);var config=mapper.config(club);
            if(circle==null || !"ACTIVE".equals(circle.get("status")) || config==null || !Boolean.TRUE.equals(config.get("autoCreate")) || availableDefault(club)!=null)return null;
            long owner=((Number)circle.get("owner")).longValue();
            if(clubs.activePlayer(owner)!=1)return null;
            return insert(owner,owner,mapper.isTest(owner),club,defaultOptions(club),true,false);
        });
        if(id!=null)events.publishEvent(new TimedNotice(circleNotice(state(own(id)),"club_createRoom",0)));
    }
    @Scheduled(fixedDelay=1000,initialDelay=5000)
    public void tick() {
        for(var entry:handles.entrySet()) {
            RoomSnapshot<String> snapshot;
            try{snapshot=rooms.snapshot(entry.getValue());}catch(RoomRejected unavailable){handles.remove(entry.getKey(),entry.getValue());continue;}
            var current=codec.value(snapshot.state(),LiveRoomState.class);
            if("CLOSED".equals(current.round().phase())){rooms.retireClosed(entry.getValue());continue;}
            long due=current.vote()==null?current.round().dueAt():Math.min(current.round().dueAt(),current.vote().until());
            if(due>clock.millis())continue;
            var command=command(null,"TICK",null,Map.of(),Map.of(),0,false);
            rooms.submitTimer(entry.getValue(),RoomMailboxes.Fence.of(snapshot),command).whenComplete((next,error)->{
                if(error!=null)return;
                var state=codec.value(next.state(),LiveRoomState.class);String tag;
                if(current.vote()!=null && current.vote().until()<=command.now())tag="voteEndGame";
                else tag=switch(current.round().phase()){case "BIDDING"->"score";case "PLAYING"->current.round().play().target()==null?"chupai":"buchu";default->"roomTimeOver";};
                events.publishEvent(new TimedNotice(changed(state,tag,current.round().currentUid())));
            });
        }
    }
    @Scheduled(fixedDelay=3000,initialDelay=5000)
    public void replenish(){for(long club:mapper.automaticClubs())replenish(club);}
    @Override public void destroy(){rooms.close();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public static Map<String,Object> options(String game,Map<String,Object> raw) {
        if(!"6003".equals(game))throw new GameFailure(-18,"当前圈房间已接入斗地主 6003，其他玩法待接入");
        Object count=raw.get("playNumber"),aa=raw.get("aa");
        if(!(count instanceof Number number) || !(number.intValue()==6 || number.intValue()==12) || number.doubleValue()!=number.intValue() || !(aa instanceof Boolean))throw new GameFailure(-4,"斗地主建房选项无效");
        var options=new LinkedHashMap<String,Object>();options.put("playNumber",number.intValue());options.put("cardType",number.intValue()==6?4:8);
        options.put("aa",aa);options.put("max_player",3);options.put("sharePlayDes",number.intValue()+"局 "+(raw.get("club_id")==null?"":"亲友圈支付"));
        options.put("shareTitleDes","");
        if(raw.get("club_id")!=null) {
            Object id=raw.get("club_id");String text=Objects.toString(id,"");
            if(!text.matches("[1-9][0-9]{0,8}"))throw new GameFailure(-4,"亲友圈编号无效");
            options.put("club_id",Long.parseLong(text));options.put("aa",false);
        }
        if(Boolean.TRUE.equals(raw.get("morenroom")))options.put("morenroom",true);
        return Collections.unmodifiableMap(options);
    }
}
