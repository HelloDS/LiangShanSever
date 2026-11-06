package com.ruoyi.game.club;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.*;

@Service
public class ClubAdminService {
    private final ClubAdminMapper mapper;
    private final ClubMapper clubs;
    private final ClubService organization;
    private final GameAdminService permissions;
    private final AdminOperations operations;
    private final Clock clock;
    public ClubAdminService(ClubAdminMapper mapper,ClubMapper clubs,ClubService organization,GameAdminService permissions,AdminOperations operations,Clock clock){this.mapper=mapper;this.clubs=clubs;this.organization=organization;this.permissions=permissions;this.operations=operations;this.clock=clock;}

    @Transactional(readOnly=true)
    public Map<String,Object> list(long actor,Long id,String name,Long owner,String status,String sort,int page,int limit,Set<Long> online,String dataStatus,String asOf){
        page(page,limit);if(name!=null&&name.length()>8)throw new IllegalArgumentException("圈名称筛选过长");
        if(status!=null&&!Set.of("ACTIVE","SUSPENDED","DISBANDED").contains(status))throw new IllegalArgumentException("圈状态无效");
        if(!Set.of("id","members","online","created").contains(sort))throw new IllegalArgumentException("排序字段无效");
        if("online".equals(sort)&&online==null)throw new GameDataUnavailable("在线数据暂不可用，请更换排序或稍后重试");
        var rows=mapper.list(actor,id,name,owner,status,sort,online,(page-1)*limit,limit).stream().map(AdminTimes::utc).map(row->{if(online==null)row.put("onlineCount",null);return row;}).toList();
        return Map.of("code",200,"rows",rows,"total",mapper.count(actor,id,name,owner,status),"dataStatus",dataStatus,"asOf",asOf);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long actor,long id){
        permissions.requireClub(actor,id);var club=clubs.club(id);if(club==null)throw new IllegalArgumentException("亲友圈不存在");
        return Map.of("club",AdminTimes.utc(club),"wallets",mapper.wallets(id));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> members(long actor,long id,int page,int limit,Set<Long> online){
        permissions.requireClub(actor,id);page(page,limit);
        var rows=mapper.members(id,(page-1)*limit,limit).stream().map(AdminTimes::utc).map(row->{row.put("online",online==null?null:online.contains(Long.parseLong(row.get("uid").toString())));return row;}).toList();
        return Map.of("code",200,"rows",rows,"total",mapper.memberCount(id));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> applications(long actor,long id,int page,int limit){permissions.requireClub(actor,id);page(page,limit);return Map.of("code",200,"rows",mapper.applications(id,(page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),"total",mapper.applicationCount(id));}

    public record Command(String action,Long targetUid,String kind,Integer decision,Boolean onlyManagers,String status,String reason){ }
    public record Reply(Map<String,Object> result,List<ClubService.Notification> notifications){ }
    @Transactional
    public Reply command(long actor,long id,Command command,String key){
        permissions.requireClub(actor,id);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("action",command.action());payload.put("targetUid",command.targetUid());payload.put("kind",command.kind());payload.put("decision",command.decision());payload.put("onlyManagers",command.onlyManagers());payload.put("status",command.status());
        List<ClubService.Notification> notifications=new ArrayList<>();
        var result=operations.execute(actor,"club:"+command.action(),Long.toString(id),command.reason(),key,payload,()->{
            var before=clubs.lock(id);if(before==null)throw new IllegalArgumentException("亲友圈不存在");
            ClubService.Actor operator=ClubService.Actor.operator(actor);ClubService.Change change;
            switch(command.action()){
                case "review" -> {requireTarget(command);if(command.kind()==null||command.decision()==null)throw new IllegalArgumentException("缺少审批参数");change=organization.review(operator,id,command.targetUid(),command.kind(),command.decision());}
                case "kick" -> {requireTarget(command);change=organization.kick(operator,id,command.targetUid());}
                case "manager","unmanager" -> {requireTarget(command);change=organization.manager(operator,id,command.targetUid(),"manager".equals(command.action()));}
                case "config" -> {if(command.onlyManagers()==null)throw new IllegalArgumentException("缺少配置");change=organization.config(operator,id,command.onlyManagers());}
                case "status" -> {
                    if(!Set.of("ACTIVE","SUSPENDED").contains(Objects.requireNonNullElse(command.status(),"")))throw new IllegalArgumentException("仅允许停用或恢复");
                    if("DISBANDED".equals(before.get("status")))throw new IllegalArgumentException("已解散圈不能恢复");
                    mapper.status(id,command.status(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));change=new ClubService.Change(List.of());
                }
                default -> throw new IllegalArgumentException("管理动作无效");
            }
            notifications.addAll(change.notifications());
            return new AdminOperations.Change(before,clubs.lock(id),Map.of("clubId",Long.toString(id),"action",command.action()));
        });
        return new Reply(result,List.copyOf(notifications));
    }
    private static void requireTarget(Command command){if(command.targetUid()==null||command.targetUid()<1)throw new IllegalArgumentException("缺少目标玩家");}
    private static void page(int page,int limit){if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");}
}
