package com.ruoyi.game.club;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.economy.ConfirmedBusinessRules;
import com.ruoyi.game.economy.WalletPosting;
import com.ruoyi.game.economy.WalletPostingService;
import com.ruoyi.game.economy.PlayerOperations;
import static com.ruoyi.game.economy.WalletPosting.OwnerType.PERSONAL;
import static com.ruoyi.game.economy.WalletPosting.OwnerType.CLUB;
import static com.ruoyi.game.economy.WalletPosting.Currency.CARD;
import static com.ruoyi.game.economy.WalletPosting.Currency.DIAMOND;
import static com.ruoyi.game.economy.WalletPosting.OwnerType.SYSTEM;
import com.ruoyi.game.lobby.PlayerService;
import com.ruoyi.game.persistence.mapper.ClubMapper;
import com.ruoyi.game.persistence.mapper.PartnerMapper;
import com.ruoyi.game.persistence.mapper.ClubRecordMapper;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 圈组织权威数据。圈状态、成员权限及资产转入使用同一个事务边界。 */
@Service
public class ClubService {
    private final ClubMapper mapper;
    private final PlayerService players;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final com.ruoyi.game.admin.GameAdminService admin;
    private final WalletPostingService wallets;
    private final PlayerOperations operations;
    private final PartnerMapper partners;
    private final ClubRecordMapper records;
    public ClubService(ClubMapper mapper, PlayerService players, GameJsonCodec codec, Clock clock,
                       com.ruoyi.game.admin.GameAdminService admin, WalletPostingService wallets,PlayerOperations operations,PartnerMapper partners,ClubRecordMapper records) {
        this.mapper=mapper;
        this.players=players;
        this.codec=codec;
        this.clock=clock;
        this.admin=admin;
        this.wallets=wallets;
        this.operations=operations;
        this.partners=partners;
        this.records=records;
    }

    public record Actor(long id,boolean admin){public static Actor player(long uid){return new Actor(uid,false);}public static Actor operator(long id){return new Actor(id,true);}}
    public record Notification(List<Long> recipients,Map<String,Object> packet){public Notification{recipients=List.copyOf(recipients);packet=Map.copyOf(packet);}}
    public record Change(List<Notification> notifications){ }
    private Change notify(long id,List<Long> recipients,String command){return new Change(List.of(new Notification(List.copyOf(recipients),Map.of("cmd",command,"club_id",id))));}
    private Change silent(){return new Change(List.of());}

    public Map<String,Object> creationCost() {
        return Map.of("state",0,"need_card",ConfirmedBusinessRules.CLUB_CREATION_CARDS,"refund",false);
    }

    @Transactional(timeout=5)
    public Map<String,Object> create(long uid, String requestedName) {
        String name=clubName(requestedName);
        var identity=mapper.lockActivePlayer(uid);
        if(identity==null)throw new GameFailure(-6,"玩家不存在或不可用");
        // 旧请求只有名称。同一创建者和首次名称始终指向原订单，改名后重发也不会重复转入。
        Long existing=mapper.createdClub(uid,name);
        if(existing!=null)return clubAndWallet(uid,active(existing,false));
        var createdAt=now();
        var row=new HashMap<String,Object>(Map.of("name",name,"uid",uid,"now",createdAt,
                "maxMembers",ConfirmedBusinessRules.CLUB_INITIAL_MEMBERS,"maxManagers",ConfirmedBusinessRules.CLUB_MANAGER_CAP,
                "test",Boolean.TRUE.equals(identity.get("isTest"))));
        try {
            mapper.create(row);
        } catch (DuplicateKeyException duplicate) {
            throw new GameFailure(-36,"亲友圈名字重复");
        }
        long id=number(row,"id");
        mapper.createOwner(id,uid,createdAt);
        mapper.createWallets(id);
        long cards=ConfirmedBusinessRules.CLUB_CREATION_CARDS;
        wallets.post(new WalletPosting(WalletPosting.Kind.TRANSFER,"club_creation:"+id,
                ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION,"club/"+id,List.of(
                new WalletPosting.Entry(new WalletPosting.Account(PERSONAL,uid,CARD),-cards,0),
                new WalletPosting.Entry(new WalletPosting.Account(CLUB,id,CARD),cards,0))));
        event(id,uid,uid,"CREATE",Map.of("name",name,"cardsTransferred",cards));
        return clubAndWallet(uid,mapper.club(id));
    }

    @Transactional(timeout=5)
    public Map<String,Object> rename(long uid,long id,String requestedName) {
        var club=active(id,true);
        owner(id,uid);
        String name=clubName(requestedName);
        String previous=(String)club.get("name");
        if(!name.equals(previous)) {
            try {
                mapper.rename(id,name,now());
            } catch (DuplicateKeyException duplicate) {
                throw new GameFailure(-36,"亲友圈名字重复");
            }
            event(id,uid,null,"RENAME",Map.of("before",previous,"after",name));
        }
        return clubAndWallet(uid,mapper.club(id));
    }

    @Transactional(timeout=5)
    public Change disband(long uid,long id) {
        var club=mapper.lock(id);
        if(club==null || number(club,"owner")!=uid)throw new GameFailure(-90,"仅圈主可解散");
        if("DISBANDED".equals(club.get("status")))return silent();
        if(!"ACTIVE".equals(club.get("status")))throw new GameFailure(-38,"亲友圈不可用");
        if(mapper.hasLiveRooms(id))throw new GameFailure(-18,"亲友圈仍有未关闭房间");
        var time=now();
        mapper.disband(id,time);
        mapper.closeApplications(id,uid,time);
        // 旧成员关系、余额与流水用于历史核查。关闭后的圈不参与业务，也不返还资产。
        var members=mapper.members(id);
        for(long recipient:members)if(recipient!=uid)mapper.message(recipient,id,(String)club.get("name"),"disbandClub",time);
        event(id,uid,null,"DISBAND",Map.of("refund",false));
        return notify(id,members,"disbandClub");
    }

    public Map<String,Object> upgradeConfig() {
        return Map.of("state",0,"config",Map.of("upper",ConfirmedBusinessRules.CLUB_MEMBER_CAP,
                "card",ConfirmedBusinessRules.CLUB_UPGRADE_CARDS,"upgrade",ConfirmedBusinessRules.CLUB_UPGRADE_MEMBERS));
    }

    @Transactional(timeout=5)
    public Map<String,Object> upgrade(long uid,long id,String operationId) {
        var club=active(id,true);
        owner(id,uid);
        operations.execute(uid,operationId,Map.of("action","club/clubUpgrade","club",id),()->{
            int before=Math.toIntExact(number(club,"max_mem"));
            if(before>=ConfirmedBusinessRules.CLUB_MEMBER_CAP)throw new GameFailure(-42,"人数容量已达上限");
            int after=Math.min(ConfirmedBusinessRules.CLUB_MEMBER_CAP,before+ConfirmedBusinessRules.CLUB_UPGRADE_MEMBERS);
            long cost=ConfirmedBusinessRules.CLUB_UPGRADE_CARDS;
            wallets.post(new WalletPosting(WalletPosting.Kind.CONSUMPTION,"club_upgrade:"+uid+":"+operationId,
                    ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION,"club/"+id,List.of(
                    new WalletPosting.Entry(new WalletPosting.Account(CLUB,id,CARD),-cost,0),
                    new WalletPosting.Entry(new WalletPosting.Account(SYSTEM,0,CARD),cost,0))));
            mapper.capacity(id,after,now());
            event(id,uid,null,"UPGRADE",Map.of("before",before,"after",after,"cards",cost,"operationId",operationId));
            return Map.of("state",0);
        });
        return Map.of("state",0,"club",wireClub(mapper.club(id)));
    }

    @Transactional(timeout=5)
    public Map<String,Object> deposit(long uid,long id,long cards,long diamonds,String operationId) {
        active(id,true);
        owner(id,uid);
        if(cards<0 || diamonds<0 || cards>WalletPosting.MAX_CLIENT_INTEGER
                || diamonds>WalletPosting.MAX_CLIENT_INTEGER || cards==0 && diamonds==0)throw bad("请输入有效的转入数量");
        operations.execute(uid,operationId,Map.of("action","club/deposit","club",id,"card",cards,"diamond",diamonds),()->{
            var postings=new ArrayList<WalletPosting>();
            if(cards>0)postings.add(depositPosting(uid,id,cards,CARD,operationId));
            if(diamonds>0)postings.add(depositPosting(uid,id,diamonds,DIAMOND,operationId));
            wallets.postAll(postings);
            event(id,uid,null,"DEPOSIT",Map.of("card",cards,"diamond",diamonds,"operationId",operationId));
            return Map.of("state",0);
        });
        var role=assetValues(mapper.lockPersonalAssets(uid));
        var circle=assetValues(mapper.lockAssets(id));
        return Map.of("state",0,"roleCard",role.get("card"),"roleDiamond",role.get("diamond"),
                "clubCard",circle.get("card"),"clubDiamond",circle.get("diamond"),"operationId",operationId);
    }

    private WalletPosting depositPosting(long uid,long id,long amount,WalletPosting.Currency currency,String operationId) {
        return new WalletPosting(WalletPosting.Kind.TRANSFER,"club_deposit:"+uid+":"+operationId+":"+currency.code(),
                ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION,"club/"+id,List.of(
                new WalletPosting.Entry(new WalletPosting.Account(PERSONAL,uid,currency),-amount,0),
                new WalletPosting.Entry(new WalletPosting.Account(CLUB,id,currency),amount,0)));
    }

    @Transactional(timeout=5)
    public Map<String,Object> exchange(long uid,long id,long output,boolean toCards,String operationId) {
        active(id,true);
        owner(id,uid);
        long rate=ConfirmedBusinessRules.CARDS_PER_DIAMOND;
        if(output<1 || output>WalletPosting.MAX_CLIENT_INTEGER || toCards && output%rate!=0
                || !toCards && output>WalletPosting.MAX_CLIENT_INTEGER/rate)throw bad("兑换数量无效");
        long cards=toCards?output:Math.multiplyExact(output,rate),diamonds=toCards?output/rate:output;
        operations.execute(uid,operationId,Map.of("action",toCards?"club/exchangeCard":"club/exchangeDiamond","club",id,"output",output),()->{
            long cardDelta=toCards?cards:-cards,diamondDelta=toCards?-diamonds:diamonds;
            wallets.post(new WalletPosting(WalletPosting.Kind.EXCHANGE,"club_exchange:"+uid+":"+operationId,
                    ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION,"club/"+id,List.of(
                    new WalletPosting.Entry(new WalletPosting.Account(CLUB,id,CARD),cardDelta,0),
                    new WalletPosting.Entry(new WalletPosting.Account(SYSTEM,0,CARD),-cardDelta,0),
                    new WalletPosting.Entry(new WalletPosting.Account(CLUB,id,DIAMOND),diamondDelta,0),
                    new WalletPosting.Entry(new WalletPosting.Account(SYSTEM,0,DIAMOND),-diamondDelta,0))));
            event(id,uid,null,"EXCHANGE",Map.of("cards",cardDelta,"diamonds",diamondDelta,"operationId",operationId));
            return Map.of("state",0);
        });
        var circle=assetValues(mapper.lockAssets(id));
        return Map.of("state",0,"clubCard",circle.get("card"),"clubDiamond",circle.get("diamond"),"operationId",operationId);
    }

    private static Map<String,Long> assetValues(List<Map<String,Object>> rows) {
        var result=new HashMap<String,Long>();
        for(var row:rows)result.put((String)row.get("currency"),number(row,"balance"));
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> mine(long uid){
        Map<String,Object> clubs=new LinkedHashMap<>();
        for(var row:mapper.mine(uid))clubs.put(row.get("id").toString(),wireClub(row));
        return Map.of("state",0,"clubs",clubs,"msgRedPoint",mapper.unread(uid)>0);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(long uid,long id){
        var club=active(id,false);member(id,uid);return Map.of("state",0,"club",wireClub(club));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> members(long uid,long id){active(id,false);member(id,uid);return Map.of("state",0,"mems",mapper.members(id));}

    @Transactional(readOnly=true)
    public Map<String,Object> roles(long uid,long id,List<Long> targets){
        active(id,false);String caller=member(id,uid);if(targets.size()>200)throw bad("一次最多查询 200 名成员");
        Map<String,Object> roles=new LinkedHashMap<>();
        for(long target:targets){
            String role=mapper.memberRole(id,target);
            if(role==null&&(!manager(caller)||!"PENDING".equals(mapper.application(id,target,"JOIN"))))throw new GameFailure(-90,"目标不在本圈授权范围");
            Map<String,Object> value=new LinkedHashMap<>(players.publicRole(target));
            value.put("club_level","OWNER".equals(role)?0:"MANAGER".equals(role)?1:2);
            var stats=records.memberStats(id,target);
            value.put("game_number",stats.get("games"));
            value.put("game_at",stats.get("gameAt")==null?null:time(stats.get("gameAt")));
            // 建设和合伙人未启用，旧版踢出提示仍读取这三个字段。
            value.put("strength",0);value.put("ore",0);value.put("is_partner",partners.exists(id,target)?1:0);
            roles.put(Long.toString(target),value);
        }
        return Map.of("state",0,"roles",roles);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> config(long uid,long id){
        var club=active(id,false);String role=member(id,uid);
        return Map.of("state",0,"config",Map.of("only_manager_open_room",Boolean.TRUE.equals(club.get("onlyManagerOpenRoom"))?1:0),"is_manager",manager(role),"is_partner",partners.exists(id,uid));
    }
    @Transactional public Change config(long uid,long id,boolean onlyManagers){return config(Actor.player(uid),id,onlyManagers);}
    @Transactional
    public Change config(Actor actor,long id,boolean onlyManagers){
        active(id,true);authorize(actor,id,false);mapper.config(id,onlyManagers,now());event(id,actor,null,"CONFIG",Map.of("only_manager_open_room",onlyManagers));
        return new Change(List.of(new Notification(mapper.members(id),Map.of("cmd","clubConfigChange","club_id",id,"config",Map.of("only_manager_open_room",onlyManagers?1:0)))));
    }
    @Transactional
    public Change apply(long uid,long id,String kind){
        var club=active(id,true);
        if(!Set.of("JOIN","LEAVE").contains(kind))throw bad("申请类型无效");
        String role=mapper.memberRole(id,uid);
        if("JOIN".equals(kind)){
            if(role!=null)throw new GameFailure(-39,"已经在亲友圈内");
            if(mapper.activePlayer(uid)!=1)throw new GameFailure(-6,"玩家不存在或不可用");
        }else{
            if(role==null)throw new GameFailure(-41,"不在亲友圈内");
            if("OWNER".equals(role))throw new GameFailure(-90,"圈主不能申请退出");
        }
        if("PENDING".equals(mapper.application(id,uid,kind)))return silent();
        mapper.apply(id,uid,kind,now());event(id,uid,uid,"APPLY_"+kind,Map.of("clubName",club.get("name")));
        List<Long> managers=mapper.members(id).stream().filter(target->manager(mapper.memberRole(id,target))).toList();
        return notify(id,managers,"JOIN".equals(kind)?"club_apply":"club_applyLeave");
    }
    @Transactional(readOnly=true)
    public Map<String,Object> applications(long uid,long id,int page,int limit){
        active(id,false);reviewPermission(id,uid);if(page<1||page>100000||limit<1||limit>100)throw bad("分页参数无效");
        long count=mapper.applicationCount(id,"JOIN");List<Map<String,Object>> rows=new ArrayList<>();
        for(var row:mapper.applications(id,"JOIN",(page-1)*limit,limit)){
            rows.add(Map.of("apply_role",players.publicRole(number(row,"uid")),"apply_time",time(row.get("appliedAt"))));
        }
        return Map.of("state",0,"list",Map.of("page",page,"total_page",(count+limit-1)/limit,"rows",rows));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> leaveApplications(long uid,long id){
        active(id,false);manage(id,uid);Map<String,Object> list=new LinkedHashMap<>();
        if(mapper.applicationCount(id,"LEAVE")>10000)throw bad("申请数量超出查询范围");
        for(var row:mapper.applications(id,"LEAVE",0,10000))list.put(row.get("uid").toString(),time(row.get("appliedAt")));
        return Map.of("state",0,"list",list);
    }
    @Transactional public Change review(long actor,long id,long target,String kind,int decision){return review(Actor.player(actor),id,target,kind,decision);}
    @Transactional
    public Change review(Actor actor,long id,long target,String kind,int decision){
        var club=active(id,true);
        String actorRole;
        if(!actor.admin() && "JOIN".equals(kind)) {
            reviewPermission(id,actor.id());
            actorRole=member(id,actor.id());
        } else actorRole=authorize(actor,id,false);
        if(!Set.of("JOIN","LEAVE").contains(kind)||!(decision==1||decision==2))throw bad("审批参数无效");
        if(!"PENDING".equals(mapper.application(id,target,kind)))throw bad("申请已处理，请刷新");
        String targetRole=mapper.memberRole(id,target);
        if("JOIN".equals(kind)){
            if(targetRole!=null)throw new GameFailure(-39,"玩家已在圈内");
            if(decision==1){
                if(mapper.activePlayer(target)!=1)throw new GameFailure(-6,"玩家不存在或不可用");
                if(number(club,"current_mem")>=Math.min(number(club,"max_mem"),com.ruoyi.game.economy.ConfirmedBusinessRules.CLUB_MEMBER_CAP))throw new GameFailure(-42,"圈成员已达上限");
                mapper.join(id,target,now());mapper.count(id,1,now());
            }
        }else{
            if(targetRole==null)throw new GameFailure(-41,"玩家已不在圈内");
            mayRemove(actor,actorRole,target,targetRole);
            if(decision==1){removePartnerMembership(id,target);mapper.leave(id,target);mapper.count(id,-1,now());}
        }
        if(mapper.review(id,target,kind,decision==1?"APPROVED":"REJECTED",actor.id(),actor.admin()?"ADMIN":"PLAYER",now())!=1)throw new IllegalStateException("申请状态并发变化");
        String prefix="JOIN".equals(kind)?"apply":"applyLeave";
        mapper.message(target,id,(String)club.get("name"),prefix+(decision==1?"Suc":"Fail"),now());
        event(id,actor,target,"REVIEW_"+kind,Map.of("decision",decision));
        return decision==1?notify(id,List.of(target),"JOIN".equals(kind)?"club_applyOk":"club_applyLeaveOk"):silent();
    }
    @Transactional public Change kick(long actor,long id,long target){return kick(Actor.player(actor),id,target);}
    @Transactional
    public Change kick(Actor actor,long id,long target){
        var club=active(id,true);String actorRole=authorize(actor,id,false),targetRole=member(id,target);
        mayRemove(actor,actorRole,target,targetRole);
        removePartnerMembership(id,target);
        if(mapper.leave(id,target)!=1)throw new GameFailure(-41,"玩家已不在圈内");
        mapper.count(id,-1,now());
        // 将尚未处理的退圈申请置为已批准，避免留有无法处理的队列记录。
        mapper.review(id,target,"LEAVE","APPROVED",actor.id(),actor.admin()?"ADMIN":"PLAYER",now());
        mapper.message(target,id,(String)club.get("name"),"kick",now());event(id,actor,target,"KICK",Map.of("previousRole",targetRole));
        return notify(id,List.of(target),"club_kick");
    }
    @Transactional public Change manager(long actor,long id,long target,boolean enabled){return manager(Actor.player(actor),id,target,enabled);}
    @Transactional
    public Change manager(Actor actor,long id,long target,boolean enabled){
        var club=active(id,true);authorize(actor,id,true);String targetRole=member(id,target);
        if("OWNER".equals(targetRole))throw new GameFailure(-90,"不能修改圈主身份");
        String desired=enabled?"MANAGER":"MEMBER";if(desired.equals(targetRole))return silent();
        if(enabled){
            Object maximum=club.get("maxManagers");
            int limit=maximum==null?ConfirmedBusinessRules.CLUB_MANAGER_CAP:Math.min(((Number)maximum).intValue(),ConfirmedBusinessRules.CLUB_MANAGER_CAP);
            if(mapper.managers(id)>=limit)throw new GameFailure(-90,"管理员数量已达本圈配置上限");
        }
        mapper.role(id,target,desired);event(id,actor,target,"SET_ROLE",Map.of("before",targetRole,"after",desired));
        return new Change(List.of(new Notification(mapper.members(id),Map.of("cmd","clubManagerChange","club_id",id,"member",Map.of("uid",target,"level",enabled?1:2)))));
    }
    @Transactional
    public Map<String,Object> messages(long uid){
        var rows=mapper.messages(uid);List<String> messages=new ArrayList<>();long through=0;
        for(var row:rows){messages.add(messageName((String)row.get("name"))+","+row.get("kind")+","+time(row.get("createdAt")));through=Math.max(through,number(row,"id"));}
        if(through>0)mapper.readMessages(uid,through,now());return Map.of("state",0,"msgs",messages);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> rooms(long uid,long id){
        active(id,false);String role=member(id,uid);Map<String,Object> red=new LinkedHashMap<>();
        if(manager(role)){if(mapper.applicationCount(id,"JOIN")>0)red.put("apply",1);if(mapper.applicationCount(id,"LEAVE")>0)red.put("applyLeave",1);}
        Map<String,Object> response=new LinkedHashMap<>();response.put("state",0);response.put("list",Map.of());response.put("redPoints",red);response.put("default_room",null);response.put("automatic_room_enabled",false);return response;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> defaultRoom(long uid,long id){active(id,false);member(id,uid);Map<String,Object> response=new LinkedHashMap<>();response.put("state",0);response.put("config",null);return response;}
    @Transactional(readOnly=true)
    public Map<String,Object> autoBuild(long uid,long id,String gameId){
        active(id,false);manage(id,uid);
        if(!Set.of("6008","6014","6038","6073","6003","6009","6055","6060","6061","6056","6057").contains(gameId))throw new GameFailure(-16,"游戏不存在");
        // 原圈主/管理员入圈即逐游戏关闭自动体力/矿石；本库从未启用，也不开放该功能。
        return Map.of("state",0,"config",Map.of("strength_status",0,"ore_status",0));
    }
    public void unavailableEconomy(){throw new GameFailure(-18,"圈费用及资源规则尚未确认，暂不可执行");}
    private Map<String,Object> clubAndWallet(long uid,Map<String,Object> club) {
        return Map.of("state",0,"club",wireClub(club),"card",players.ownRole(uid).get("card"));
    }
    private static String clubName(String value) {
        if(value==null)throw bad("亲友圈名称不能为空");
        String name=Normalizer.normalize(value.strip(),Normalizer.Form.NFC);
        if(name.isEmpty() || name.length()>8)throw bad("亲友圈名称为 1 至 8 字");
        if(name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT
                ||c=='<'||c=='>'||c=='&'||c==','))throw new GameFailure(-43,"亲友圈名称包含特殊字符");
        return name;
    }
    private Map<String,Object> active(long id,boolean lock){
        var club=lock?mapper.lock(id):mapper.club(id);
        if(club==null||!"ACTIVE".equals(club.get("status")))throw new GameFailure(-38,"亲友圈不存在或不可用");return club;
    }
    private String member(long id,long uid){String role=mapper.memberRole(id,uid);if(role==null)throw new GameFailure(-41,"不在亲友圈内");return role;}
    private String manage(long id,long uid){String role=member(id,uid);if(!manager(role))throw new GameFailure(-90,"没有本圈管理权限");return role;}
    private void reviewPermission(long id,long uid) {
        String role=member(id,uid);
        if(!manager(role) && !partners.mayReview(id,uid))throw new GameFailure(-90,"没有本圈入圈审批权限");
    }
    private void removePartnerMembership(long id,long uid) {
        partners.removeRelations(id,uid);
        partners.remove(id,uid);
    }
    private void owner(long id,long uid){if(!"OWNER".equals(member(id,uid)))throw new GameFailure(-90,"仅圈主可执行此操作");}
    private static boolean manager(String role){return "OWNER".equals(role)||"MANAGER".equals(role);}
    private static void mayRemove(Actor actor,String actorRole,long target,String targetRole){
        if((!actor.admin()&&actor.id()==target)||"OWNER".equals(targetRole)||("MANAGER".equals(actorRole)&&"MANAGER".equals(targetRole)))throw new GameFailure(-90,"没有移除此成员的权限");
    }
    private String authorize(Actor actor,long id,boolean ownerOnly){
        if(actor.admin()){admin.requireClub(actor.id(),id);return "OWNER";}
        if(ownerOnly){owner(id,actor.id());return "OWNER";}return manage(id,actor.id());
    }
    private void event(long id,long actor,Long target,String action,Map<String,Object> details){event(id,Actor.player(actor),target,action,details);}
    private void event(long id,Actor actor,Long target,String action,Map<String,Object> details){mapper.event(id,actor.id(),actor.admin()?"ADMIN":"PLAYER",target,action,codec.write(details),now());}
    private static Map<String,Object> wireClub(Map<String,Object> row){Map<String,Object> value=new LinkedHashMap<>();for(String key:List.of("id","name","owner","current_mem","max_mem","card","diamond"))value.put(key,row.get(key));value.put("max_mem",(int)Math.min(number(row,"max_mem"),com.ruoyi.game.economy.ConfirmedBusinessRules.CLUB_MEMBER_CAP));return value;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static GameFailure bad(String message){return new GameFailure(-4,message);}
    private static String messageName(String name){return name.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace(",","，");}
    private static String time(Object value){LocalDateTime utc=value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
