package com.ruoyi.game.transport;

import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.club.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.protocol.WireRequest;
import tools.jackson.databind.JsonNode;

@Component
public class PartnerWireRouter {
    private final ClubPartnerService partners;
    private final ClubGameRecords records;
    private static final Set<String> COMMANDS=Set.of("getClubMembers","getPartner","getPartners","getPartnerMember",
            "getPartnerMembers","getPartnerLogs","setPartnerAuth","addPartner","addPartnerMember","removePartner",
            "removePartnerMember","getPartnerAchievement","getClubMemberGameRecords","getClubGameRecord");

    public PartnerWireRouter(ClubPartnerService partners,ClubGameRecords records) {
        this.partners=partners;
        this.records=records;
    }
    public boolean supports(WireRequest request){return "partner".equals(request.api()) && COMMANDS.contains(request.command());}

    public ClubWireRouter.Reply dispatch(WireRequest request,long uid) {
        JsonNode data=request.data();
        long club=id(data,"club_id");
        Map<String,Object> ack;
        var notifications=new ArrayList<ClubService.Notification>();
        switch(request.command()) {
            case "getClubMembers" -> ack=partners.memberPage(uid,club,optionalId(data,"uid"),optionalId(data,"partner_uid"),page(data));
            case "getPartner" -> ack=partners.partner(uid,club,id(data,"uid"));
            case "getPartners" -> ack=partners.partnerPage(uid,club,date(data),page(data),sort(data),flag(data,"is_contain_partner"));
            case "getPartnerMember" -> ack=partners.member(uid,club,id(data,"partner_uid"),id(data,"member_uid"));
            case "getPartnerMembers" -> ack=partners.team(uid,club,id(data,"partner_uid"),date(data),page(data),sort(data),flag(data,"is_contain_partner"));
            case "getPartnerAchievement" -> ack=partners.achievement(uid,club,id(data,"partner_uid"),date(data),flag(data,"is_contain_partner"));
            case "getPartnerLogs" -> ack=partners.logs(uid,club,id(data,"partner_uid"),date(data),page(data));
            case "setPartnerAuth" -> {
                boolean all=data.path("uids").isString() && "all".equals(data.path("uids").asString());
                ack=partners.permission(uid,club,all?List.of():ids(data,"uids"),all,flag(data,"has_review_auth"));
            }
            case "addPartner" -> {
                var result=partners.add(uid,club,ids(data,"uids"));
                ack=result.ack();
                for(long target:result.changed())notifications.add(new ClubService.Notification(List.of(target),
                        Map.of("cmd","becomePartner","club_id",club,"partner_uid",target)));
            }
            case "removePartner" -> {
                var result=partners.remove(uid,club,id(data,"partner_uid"));
                ack=result.ack();
                for(long target:result.changed())notifications.add(new ClubService.Notification(List.of(target),
                        Map.of("cmd","losePartner","club_id",club,"partner_uid",target)));
            }
            case "addPartnerMember" -> ack=partners.bind(uid,club,id(data,"partner_uid"),ids(data,"member_uids"),flag(data,"is_flush"));
            case "removePartnerMember" -> ack=partners.unbind(uid,club,id(data,"partner_uid"),id(data,"member_uid"),flag(data,"is_flush"));
            case "getClubMemberGameRecords" -> ack=records.member(uid,club,id(data,"partner_uid"),id(data,"member_uid"),date(data),page(data));
            case "getClubGameRecord" -> ack=records.detail(uid,club,id(data,"record_id"));
            default -> throw invalid();
        }
        return new ClubWireRouter.Reply(ack,List.copyOf(notifications));
    }

    private static String date(JsonNode data){return GameJsonCodec.text(data,"date",10);}
    private static int page(JsonNode data){var value=data.path("page");if(!value.isIntegralNumber()||!value.canConvertToInt())throw invalid();return value.asInt();}
    private static boolean flag(JsonNode data,String key){var value=data.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt()||!(value.asInt()==0||value.asInt()==1))throw invalid();return value.asInt()==1;}
    private static Long optionalId(JsonNode data,String key){var value=data.path(key);return value.isMissingNode()||value.isNull()?null:id(data,key);}
    private static long id(JsonNode data,String key){return id(data.path(key));}
    private static long id(JsonNode value){String text=value.isString()||value.isIntegralNumber()?value.asString():"";if(!text.matches("[1-9][0-9]{0,8}"))throw invalid();return Long.parseLong(text);}
    private static List<Long> ids(JsonNode data,String key) {
        var value=data.path(key);
        if(!value.isArray()||value.isEmpty()||value.size()>100)throw invalid();
        var result=new ArrayList<Long>();
        for(var item:value)result.add(id(item));
        if(new HashSet<>(result).size()!=result.size())throw invalid();
        return List.copyOf(result);
    }
    private static Map<String,String> sort(JsonNode data) {
        var value=data.path("sort");
        if(!value.isObject()||value.size()>1)throw invalid();
        var result=new HashMap<String,String>();
        for(var entry:value.properties()) {
            if(!entry.getValue().isString())throw invalid();
            result.put(entry.getKey(),entry.getValue().asString());
        }
        return result;
    }
    private static GameFailure invalid(){return new GameFailure(-4,"合伙人请求参数无效");}
}
