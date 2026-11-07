package com.ruoyi.game.transport;

import java.util.*;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.club.ClubService;
import com.ruoyi.game.club.ClubGameRecords;
import com.ruoyi.game.protocol.WireRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ClubWireRouter {
    private final ClubService clubs;
    private final ClubGameRecords records;
    public ClubWireRouter(ClubService clubs,ClubGameRecords records){this.clubs=clubs;this.records=records;}
    public record Reply(Map<String,Object> ack,List<ClubService.Notification> notifications){ }
    private static final Set<String> ROUTES=Set.of("club/setClubMemComment","build/getAutoBuildConfig","club/getClubs","club/getClub","club/getClubMems","club/getRoles","club/getClubConfig","club/setClubConfig","club/apply","club/applyLeave","club/getApplyList","club/getApplyLeaveList","club/reviewApply","club/reviewApplyLeave","club/kick","club/setClubManager","club/removeClubManager","club/getClubMsgs","club/getRoomList","club/getRoomInfos","club/getClubZhanJi","club/getRoleZhanJi","room/getDefaultRoomConfig","club/getCreateNeedCard","club/createClub","club/rename","club/disbandClub","club/transferCard","club/clubUpgrade","cfg/getClubUpgradeConfig");
    public boolean supports(WireRequest req){return ROUTES.contains(req.api()+"/"+req.command())
            ||Set.of("club/transferDiamond","club/transferToClub","club/exchangeDiamond","club/exchangeCard").contains(req.api()+"/"+req.command());}
    public Reply dispatch(WireRequest req,long uid){
        JsonNode d=req.data();Map<String,Object> ack=null;ClubService.Change change=null;
        switch(req.api()+"/"+req.command()){
            case "build/getAutoBuildConfig" -> ack=clubs.autoBuild(uid,id(d,"club_id"),com.ruoyi.game.protocol.GameJsonCodec.text(d,"game_id",4));
            case "club/getClubs" -> ack=clubs.mine(uid);
            case "club/getClub" -> ack=clubs.get(uid,id(d,"id"));
            case "club/getCreateNeedCard" -> ack=clubs.creationCost();
            case "club/createClub" -> ack=clubs.create(uid,clubName(d));
            case "club/rename" -> ack=clubs.rename(uid,id(d,"id"),clubName(d),operation(d));
            case "club/disbandClub" -> change=clubs.disband(uid,id(d,"club_id"));
            case "cfg/getClubUpgradeConfig" -> ack=clubs.upgradeConfig();
            case "club/clubUpgrade" -> ack=clubs.upgrade(uid,id(d,"club_id"),operation(d));
            case "club/transferCard" -> ack=clubs.deposit(uid,id(d,"id"),amount(d,"card"),0,operation(d));
            case "club/transferDiamond" -> ack=clubs.deposit(uid,id(d,"club_id"),0,amount(d,"diamond"),operation(d));
            case "club/transferToClub" -> ack=clubs.deposit(uid,id(d,"club_id"),amount(d,"card"),amount(d,"diamond"),operation(d));
            case "club/exchangeCard" -> ack=clubs.exchange(uid,id(d,"club_id"),amount(d,"number"),true,operation(d));
            case "club/exchangeDiamond" -> ack=clubs.exchange(uid,id(d,"club_id"),amount(d,"number"),false,operation(d));
            case "club/setClubMemComment" -> ack=clubs.comment(uid,id(d,"club_id"),id(d,"uid"),comment(d));
            case "club/getClubMems" -> ack=clubs.members(uid,id(d,"club_id"));
            case "club/getRoles" -> {JsonNode values=d.get("uids");if(values==null||!values.isArray()||values.size()>200)throw bad();List<Long> uids=new ArrayList<>();for(JsonNode value:values)uids.add(id(value));ack=clubs.roles(uid,id(d,"club_id"),uids);}
            case "club/getClubConfig" -> ack=clubs.config(uid,id(d,"club_id"));
            case "club/setClubConfig" -> {JsonNode c=d.path("config"),v=c.path("only_manager_open_room");if(!c.isObject()||c.size()!=1||!v.isIntegralNumber()||!v.canConvertToInt()||!(v.asInt()==0||v.asInt()==1))throw bad();change=clubs.config(uid,id(d,"club_id"),v.asInt()==1);}
            case "club/apply" -> change=clubs.apply(uid,id(d,"id"),"JOIN");
            case "club/applyLeave" -> change=clubs.apply(uid,id(d,"id"),"LEAVE");
            case "club/getApplyList" -> ack=clubs.applications(uid,id(d,"id"),integer(d,"page"),integer(d,"limit"));
            case "club/getApplyLeaveList" -> ack=clubs.leaveApplications(uid,id(d,"id"));
            case "club/reviewApply" -> change=clubs.review(uid,id(d,"id"),id(d,"targetUid"),"JOIN",integer(d,"val"));
            case "club/reviewApplyLeave" -> change=clubs.review(uid,id(d,"id"),id(d,"targetUid"),"LEAVE",integer(d,"val"));
            case "club/kick" -> change=clubs.kick(uid,id(d,"id"),id(d,"targetUid"));
            case "club/setClubManager" -> change=clubs.manager(uid,id(d,"club_id"),id(d,"uid"),true);
            case "club/removeClubManager" -> change=clubs.manager(uid,id(d,"club_id"),id(d,"uid"),false);
            case "club/getClubMsgs" -> ack=clubs.messages(uid);
            case "club/getRoomList" -> ack=clubs.rooms(uid,id(d,"club_id"));
            case "club/getRoomInfos" -> {
                JsonNode ids=d.path("room_ids");
                if(!ids.isArray())throw bad();
                // 原大厅即使没有房间也会请求当前页详情。实际房间查询暂不开放。
                if(!ids.isEmpty())throw new GameFailure(-18,"亲友圈房间详情暂未开放");
                ack=Map.of("state",0,"room",Map.of(),"roles",Map.of());
            }
            case "club/getClubZhanJi" -> ack=records.club(uid,id(d,"id"),com.ruoyi.game.protocol.GameJsonCodec.text(d,"date",10),true,cursor(d));
            case "club/getRoleZhanJi" -> ack=records.club(uid,id(d,"id"),com.ruoyi.game.protocol.GameJsonCodec.text(d,"date",10),false,cursor(d));
            case "room/getDefaultRoomConfig" -> ack=clubs.defaultRoom(uid,id(d,"club_id"));
            default -> clubs.unavailableEconomy();
        }
        return new Reply(ack==null?Map.of("state",0):ack,change==null?List.of():change.notifications());
    }
    private static long id(JsonNode d,String key){return id(d.path(key));}
    private static long id(JsonNode value){String text=value.isIntegralNumber()||value.isString()?value.asString():"";if(!text.matches("[1-9][0-9]{0,8}"))throw bad();return Long.parseLong(text);}
    private static int integer(JsonNode d,String key){JsonNode value=d.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt())throw bad();return value.asInt();}
    private static String clubName(JsonNode d){JsonNode value=d.path("name");if(!value.isString()||value.asString().length()>32)throw bad();return value.asString();}
    private static String operation(JsonNode d){return com.ruoyi.game.protocol.GameJsonCodec.text(d,"operationId",36);}
    private static long amount(JsonNode d,String key){JsonNode value=d.path(key);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0||value.asLong()>com.ruoyi.game.economy.WalletPosting.MAX_CLIENT_INTEGER)throw bad();return value.asLong();}
    private static String comment(JsonNode data){var text=data.path("comment");if(!text.isString()||text.asString().length()>32)throw bad();return text.asString();}
    private static GameFailure bad(){return new GameFailure(-4,"亲友圈请求参数无效");}
    private static String cursor(JsonNode data){var value=data.path("cursor");if(value.isMissingNode())return "";if(!value.isString()||value.asString().length()>96)throw bad();return value.asString();}
}
