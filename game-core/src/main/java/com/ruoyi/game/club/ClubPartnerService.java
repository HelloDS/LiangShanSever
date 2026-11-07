package com.ruoyi.game.club;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.ClubMapper;
import com.ruoyi.game.persistence.mapper.PartnerMapper;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 圈内单层合伙人关系与授权。当前归属唯一，历史事实保留原归属。 */
@Service
public class ClubPartnerService {
    public record Update(Map<String,Object> ack,List<Long> changed) { }
    private final ClubMapper clubs;
    private final PartnerMapper partners;
    private final GameJsonCodec codec;
    private final Clock clock;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    public ClubPartnerService(ClubMapper clubs,PartnerMapper partners,GameJsonCodec codec,Clock clock) {
        this.clubs=clubs;
        this.partners=partners;
        this.codec=codec;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> memberPage(long actor,long club,Long target,Long partner,int page) {
        var circle=access(actor,club,false);
        boolean manager=manager(actor,club);
        if(!manager && (partner==null || partner!=actor))throw denied();
        if(partner!=null)requirePartner(circle,club,partner);
        var rows=profiles(club,LocalDate.now(clock.withZone(ZONE)));
        if(!manager)rows=rows.stream().filter(row->Objects.equals(parent(row),actor)).toList();
        if(target!=null)rows=rows.stream().filter(row->number(row,"uid")==target).toList();
        return page(rows,page,6);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> partner(long actor,long club,long target) {
        var circle=access(actor,club,false);
        scoped(actor,club,target);
        requirePartner(circle,club,target);
        var row=profiles(club,LocalDate.now(clock.withZone(ZONE))).stream()
                .filter(value->number(value,"uid")==target).findFirst().orElseThrow(ClubPartnerService::denied);
        return Map.of("state",0,"partner",row);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> partnerPage(long actor,long club,String date,int page,Map<String,String> sort,boolean include) {
        access(actor,club,false);
        if(!manager(actor,club))throw denied();
        var all=profiles(club,date(date));
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:all)if(number(row,"is_partner")==1) {
            var result=new LinkedHashMap<>(row);
            long target=number(row,"uid"),games=0,score=0;
            for(var member:teamProfiles(club,target,date(date),include)) {
                games=Math.addExact(games,number(member,"game_number"));
                score=Math.addExact(score,number(member,"score"));
            }
            result.put("game_number",games);
            result.put("score",score);
            rows.add(result);
        }
        sort(rows,sort);
        return page(rows,page,11);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> team(long actor,long club,long target,String date,int page,Map<String,String> sort,boolean include) {
        var circle=access(actor,club,false);
        scoped(actor,club,target);
        requirePartner(circle,club,target);
        var rows=teamProfiles(club,target,date(date),include);
        sort(rows,sort);
        return page(rows,page,13);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> member(long actor,long club,long target,long member) {
        access(actor,club,false);
        scoped(actor,club,target);
        if(!Objects.equals(partners.parent(club,member),target) && member!=target)throw denied();
        var row=profiles(club,LocalDate.now(clock.withZone(ZONE))).stream()
                .filter(value->number(value,"uid")==member).findFirst().orElseThrow(ClubPartnerService::denied);
        return Map.of("state",0,"member",row);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> achievement(long actor,long club,long target,String date,boolean include) {
        var circle=access(actor,club,false);
        scoped(actor,club,target);
        requirePartner(circle,club,target);
        long games=0,score=0;
        for(var row:teamProfiles(club,target,date(date),include)) {
            games=Math.addExact(games,number(row,"game_number"));
            score=Math.addExact(score,number(row,"score"));
        }
        return Map.of("state",0,"achievement",Map.of("strength",0,"ore",0,"score",score,"game_number",games));
    }

    @Transactional
    public Update add(long actor,long club,List<Long> targets) {
        access(actor,club,true);
        requireManager(actor,club);
        validateMembers(club,targets);
        var failures=new ArrayList<Map<String,Object>>();
        var changed=new ArrayList<Long>();
        for(long target:targets) {
            if(partners.exists(club,target) || partners.parent(club,target)!=null || "OWNER".equals(clubs.memberRole(club,target))) {
                failures.add(Map.of("uid",target,"status",1));
                continue;
            }
            partners.add(club,target,actor,now());
            changed.add(target);
            event(actor,club,target,"PARTNER_ADD",Map.of("partner",target));
        }
        return new Update(Map.of("state",0,"fails",failures),List.copyOf(changed));
    }

    @Transactional
    public Update remove(long actor,long club,long target) {
        access(actor,club,true);
        requireManager(actor,club);
        if(!partners.exists(club,target))return new Update(Map.of("state",0),List.of());
        partners.removeRelations(club,target);
        partners.remove(club,target);
        event(actor,club,target,"PARTNER_REMOVE",Map.of("partner",target));
        return new Update(Map.of("state",0),List.of(target));
    }

    @Transactional
    public Map<String,Object> bind(long actor,long club,long target,List<Long> members,boolean reset) {
        var circle=access(actor,club,true);
        requireManager(actor,club);
        requirePartner(circle,club,target);
        validateMembers(club,members);
        var failures=new ArrayList<Map<String,Object>>();
        for(long member:members) {
            if(member==target || partners.exists(club,member) || "OWNER".equals(clubs.memberRole(club,member))) {
                failures.add(Map.of("uid",member,"name",profileName(club,member),"status",1));
                continue;
            }
            Long previous=partners.parent(club,member);
            if(Objects.equals(previous,target))continue;
            partners.bind(club,member,target,now());
            if(reset)partners.resetStats(club,target,member,now());
            event(actor,club,member,"PARTNER_BIND",Map.of("partner",target,"previous",previous==null?0:previous,"reset",reset));
        }
        return Map.of("state",0,"fails",failures);
    }

    @Transactional
    public Map<String,Object> unbind(long actor,long club,long target,long member,boolean reset) {
        access(actor,club,true);
        requireManager(actor,club);
        if(partners.unbind(club,member,target)>0) {
            if(reset)partners.resetStats(club,target,member,now());
            event(actor,club,member,"PARTNER_UNBIND",Map.of("partner",target,"reset",reset));
        }
        return Map.of("state",0);
    }

    @Transactional
    public Map<String,Object> permission(long actor,long club,List<Long> targets,boolean all,boolean allowed) {
        access(actor,club,true);
        requireManager(actor,club);
        var actual=all?partners.members(club).stream().filter(row->number(row,"is_partner")==1).map(row->number(row,"uid")).toList():targets;
        validateMembers(club,actual);
        for(long target:actual)if(!partners.exists(club,target))throw denied();
        for(long target:actual) {
            partners.permission(club,target,allowed);
            event(actor,club,target,"PARTNER_AUTH",Map.of("partner",target,"review",allowed));
        }
        return Map.of("state",0);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> logs(long actor,long club,long target,String date,int page) {
        access(actor,club,false);
        scoped(actor,club,target);
        var day=date(date);
        var start=day.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:partners.logs(club,target,start,start.plusDays(1))) {
            String text="操作人 "+row.get("actor")+" "+row.get("action")+" "+Objects.toString(row.get("target"),"");
            rows.add(Map.of("log",text,"time",time(row.get("time"))));
        }
        return page(rows,page,6);
    }

    private List<Map<String,Object>> profiles(long club,LocalDate date) {
        Map<Long,Map<String,Object>> totals=new HashMap<>();
        for(var value:daily(club,date))totals.merge(number(value,"uid"),value,(left,right)->{
            var sum=new HashMap<>(left);
            sum.put("games",Math.addExact(number(left,"games"),number(right,"games")));
            sum.put("score",Math.addExact(number(left,"score"),number(right,"score")));
            return sum;
        });
        var result=new ArrayList<Map<String,Object>>();
        for(var member:partners.members(club)) {
            var row=new LinkedHashMap<>(member);
            long uid=number(member,"uid");
            var stats=totals.get(uid);
            row.put("strength",0);
            row.put("ore",0);
            row.put("lock",0);
            row.put("game_number",stats==null?0:number(stats,"games"));
            row.put("score",stats==null?0:number(stats,"score"));
            row.put("game_at",stats==null?null:time(stats.get("gameAt")));
            row.put("is_partner_member",member.get("partnerUid")==null?0:1);
            row.put("has_review_auth",Boolean.TRUE.equals(member.get("hasReviewAuth"))?1:0);
            if(member.get("partnerUid")!=null)row.put("parent_partner",Map.of("uid",member.get("partnerUid"),"name",member.get("partnerName")));
            result.add(row);
        }
        return result;
    }

    private List<Map<String,Object>> daily(long club,LocalDate date) {
        var start=date.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return partners.daily(club,start,start.plusDays(1));
    }

    private ArrayList<Map<String,Object>> teamProfiles(long club,long target,LocalDate date,boolean include) {
        var current=profiles(club,date);
        var indexed=new LinkedHashMap<Long,Map<String,Object>>();
        for(var row:current)if(Objects.equals(parent(row),target) || include && number(row,"uid")==target) {
            var copy=new LinkedHashMap<>(row);
            copy.put("game_number",0L);
            copy.put("score",0L);
            copy.put("game_at",null);
            indexed.put(number(row,"uid"),copy);
        }
        var start=date.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Map<Long,Map<String,Object>> historical=new HashMap<>();
        for(var row:partners.historicalProfiles(club,target,start,start.plusDays(1)))historical.put(number(row,"uid"),row);
        for(var stats:daily(club,date)) {
            long uid=number(stats,"uid");
            if(number(stats,"partnerUid")!=target || !include && uid==target)continue;
            var row=indexed.get(uid);
            if(row==null) {
                var publicRole=historical.get(uid);
                if(publicRole==null)continue;
                row=new LinkedHashMap<>(publicRole);
                row.put("lock",1);
                row.put("strength",0);
                row.put("ore",0);
                indexed.put(uid,row);
            }
            row.put("game_number",number(stats,"games"));
            row.put("score",number(stats,"score"));
            row.put("game_at",time(stats.get("gameAt")));
        }
        return new ArrayList<>(indexed.values());
    }

    private Map<String,Object> access(long actor,long club,boolean lock) {
        var circle=lock?clubs.lock(club):clubs.club(club);
        if(circle==null || !"ACTIVE".equals(circle.get("status")))throw new GameFailure(-38,"亲友圈不可用");
        if(clubs.memberRole(club,actor)==null)throw denied();
        return circle;
    }
    private void scoped(long actor,long club,long target) {
        if(!manager(actor,club) && actor!=target)throw denied();
        if(!manager(actor,club) && !partners.exists(club,actor))throw denied();
    }
    private boolean manager(long uid,long club){return Set.of("OWNER","MANAGER").contains(Objects.toString(clubs.memberRole(club,uid),""));}
    private void requireManager(long actor,long club){if(!manager(actor,club))throw denied();}
    private void requirePartner(Map<String,Object> circle,long club,long target){if(number(circle,"owner")!=target && !partners.exists(club,target))throw denied();}
    private void validateMembers(long club,List<Long> members) {
        if(members.size()>100 || new HashSet<>(members).size()!=members.size())throw new GameFailure(-4,"成员批次数量无效");
        for(long uid:members)if(clubs.memberRole(club,uid)==null)throw denied();
    }
    private String profileName(long club,long uid){return partners.members(club).stream().filter(row->number(row,"uid")==uid).map(row->(String)row.get("name")).findFirst().orElse("");}
    private void event(long actor,long club,long target,String action,Map<String,Object> details){clubs.event(club,actor,"PLAYER",target,action,codec.write(details),now());}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private LocalDate date(String text) {
        try {
            LocalDate value=LocalDate.parse(text),today=LocalDate.now(clock.withZone(ZONE));
            if(value.isAfter(today) || value.isBefore(today.minusYears(1)))throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new GameFailure(-4,"日期无效");
        }
    }
    private static Long parent(Map<String,Object> row){Object value=row.get("partnerUid");return value==null?null:((Number)value).longValue();}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static GameFailure denied(){return new GameFailure(-90,"没有此合伙人范围的权限");}
    private static String time(Object value){var utc=value instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(LocalDateTime)value;return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
    private static Map<String,Object> page(List<Map<String,Object>> rows,int page,int size) {
        if(page<1 || page>10000)throw new GameFailure(-4,"页码无效");
        int start=Math.min(rows.size(),(page-1)*size),end=Math.min(rows.size(),start+size);
        return Map.of("state",0,"page",page,"total",rows.size(),"total_page",Math.max(1,(rows.size()+size-1)/size),"rows",List.copyOf(rows.subList(start,end)));
    }
    private static void sort(List<Map<String,Object>> rows,Map<String,String> sort) {
        if(sort.isEmpty())return;
        if(sort.size()!=1)throw new GameFailure(-4,"排序无效");
        var entry=sort.entrySet().iterator().next();
        if(!Set.of("strength","ore","score","game_number").contains(entry.getKey()) || !Set.of("asc","desc").contains(entry.getValue()))throw new GameFailure(-4,"排序无效");
        Comparator<Map<String,Object>> comparator=Comparator.comparingLong(row->number(row,entry.getKey()));
        if("desc".equals(entry.getValue()))comparator=comparator.reversed();
        rows.sort(comparator.thenComparingLong(row->number(row,"uid")));
    }
}
