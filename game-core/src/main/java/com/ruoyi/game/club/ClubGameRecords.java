package com.ruoyi.game.club;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.*;
import com.ruoyi.game.protocol.GameJsonCodec;

@Service
public class ClubGameRecords {
    private final ClubMapper clubs;
    private final PartnerMapper partners;
    private final ClubRecordMapper records;
    private final GameJsonCodec codec;
    private final Clock clock;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    public ClubGameRecords(ClubMapper clubs,PartnerMapper partners,ClubRecordMapper records,GameJsonCodec codec,Clock clock) {
        this.clubs=clubs;
        this.partners=partners;
        this.records=records;
        this.codec=codec;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> club(long actor,long club,String date,boolean all) {
        return club(actor,club,date,all,"");
    }
    @Transactional(readOnly=true)
    public Map<String,Object> club(long actor,long club,String date,boolean all,String cursor) {
        access(actor,club);
        if(all && !manager(actor,club))throw denied();
        var start=start(date);
        LocalDateTime before=null;Long beforeId=null;
        if(cursor!=null && !cursor.isEmpty()) {
            try {
                if(cursor.length()>96)throw new IllegalArgumentException();
                String[] values=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                if(values.length!=2 || !values[1].matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
                before=LocalDateTime.parse(values[0]);beforeId=Long.parseLong(values[1]);
                if(before.isBefore(start) || !before.isBefore(start.plusDays(1)))throw new IllegalArgumentException();
            } catch(RuntimeException invalid) {throw new GameFailure(-4,"战绩翻页参数无效");}
        }
        var fetched=records.clubRecords(club,all?null:actor,start,start.plusDays(1),before,beforeId);
        boolean more=fetched.size()>100;
        var rows=fetched.subList(0,Math.min(100,fetched.size()));
        var result=new ArrayList<Map<String,Object>>();
        var roles=new LinkedHashMap<String,Object>();
        for(var row:rows) {
            var detail=codec.object((String)row.get("record"));
            var players=players(detail);
            var scores=new LinkedHashMap<String,Object>();
            for(var entry:players.entrySet()) {
                var role=(Map<?,?>)entry.getValue();
                roles.put(entry.getKey(),role);
                scores.put(entry.getKey(),role.get("score"));
            }
            var value=new LinkedHashMap<String,Object>();
            value.put("record_id",row.get("id"));
            value.put("game_id",row.get("gameId"));
            value.put("room_id",row.get("roomCode"));
            value.put("playNumber",row.get("rounds"));
            value.put("gettime",time(row.get("finishedAt")));
            value.put("zhanji",scores);
            value.put("atInfo",detail.get("at_info"));
            value.put("movie_id",detail.get("movie_id"));
            value.put("sqUid",detail.get("sq_uid"));
            value.put("sqName",detail.get("sq_name"));
            result.add(value);
        }
        String next="";
        if(more) {
            var last=rows.getLast();
            String value=utc(last.get("finishedAt"))+"|"+last.get("id");
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return Map.of("state",0,"zhanjis",result,"roles",roles,"has_more",more,"next_cursor",next);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> member(long actor,long club,long partner,long uid,String date,int page) {
        access(actor,club);
        if(!manager(actor,club) && (actor!=partner || !partners.exists(club,actor)))throw denied();
        if(page<1 || page>10000)throw new GameFailure(-4,"页码无效");
        var start=start(date);
        var rows=records.partnerRecords(club,partner,uid,start,start.plusDays(1),(page-1)*8,8);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows)result.add(Map.of("record_id",row.get("id"),"game_id",row.get("gameId"),
                "room_id",row.get("roomCode"),"game_name",gameName((String)row.get("gameId")),
                "time",time(row.get("finishedAt")),"score",row.get("score"),"strength",0,"ore",0));
        long count=records.partnerCount(club,partner,uid,start,start.plusDays(1));
        return Map.of("state",0,"page",page,"total",count,"total_page",Math.max(1,(count+7)/8),"rows",result);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(long actor,long club,long id) {
        access(actor,club);
        var row=records.detail(id);
        if(row==null || ((Number)row.get("clubId")).longValue()!=club)throw denied();
        if(!manager(actor,club) && (!partners.exists(club,actor) || !records.containsPartner(id,actor)))throw denied();
        return Map.of("state",0,"record",codec.object((String)row.get("record")));
    }

    private void access(long actor,long club) {
        var circle=clubs.club(club);
        if(circle==null || !"ACTIVE".equals(circle.get("status")) || clubs.memberRole(club,actor)==null)throw denied();
    }
    private boolean manager(long actor,long club){return Set.of("OWNER","MANAGER").contains(Objects.toString(clubs.memberRole(club,actor),""));}
    private LocalDateTime start(String text) {
        try {
            LocalDate date=LocalDate.parse(text),today=LocalDate.now(clock.withZone(ZONE));
            if(date.isAfter(today) || date.isBefore(today.minusYears(1)))throw new IllegalArgumentException();
            return date.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new GameFailure(-4,"战绩日期无效");
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> players(Map<String,Object> record){return (Map<String,Object>)record.get("game_record");}
    private static String gameName(String id){return "6003".equals(id)?"斗地主":id;}
    private static GameFailure denied(){return new GameFailure(-90,"没有此战绩范围的权限");}
    private static LocalDateTime utc(Object value){return value instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(LocalDateTime)value;}
    private static String time(Object value){return utc(value).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
}
