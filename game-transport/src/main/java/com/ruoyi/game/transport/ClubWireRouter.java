package com.ruoyi.game.transport;

import java.util.*;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.club.ClubService;
import com.ruoyi.game.protocol.WireRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ClubWireRouter {
    private final ClubService clubs;
    public ClubWireRouter(ClubService clubs){this.clubs=clubs;}
    public record Reply(Map<String,Object> ack,List<ClubService.Notification> notifications){ }
    private static final Set<String> ROUTES=Set.of("build/getAutoBuildConfig","club/getClubs","club/getClub","club/getClubMems","club/getRoles","club/getClubConfig","club/setClubConfig","club/apply","club/applyLeave","club/getApplyList","club/getApplyLeaveList","club/reviewApply","club/reviewApplyLeave","club/kick","club/setClubManager","club/removeClubManager","club/getClubMsgs","club/getRoomList","club/getClubZhanJi","club/getRoleZhanJi","room/getDefaultRoomConfig","club/getCreateNeedCard","club/createClub","club/rename","club/disbandClub","club/transferCard","club/clubUpgrade","cfg/getClubUpgradeConfig");
    public boolean supports(WireRequest req){return ROUTES.contains(req.api()+"/"+req.command());}
    public Reply dispatch(WireRequest req,long uid){
        JsonNode d=req.data();Map<String,Object> ack=null;ClubService.Change change=null;
        switch(req.api()+"/"+req.command()){
            case "build/getAutoBuildConfig" -> ack=clubs.autoBuild(uid,id(d,"club_id"),com.ruoyi.game.protocol.GameJsonCodec.text(d,"game_id",4));
            case "club/getClubs" -> ack=clubs.mine(uid);
            case "club/getClub" -> ack=clubs.get(uid,id(d,"id"));
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
            case "club/getClubZhanJi" -> ack=clubs.records(uid,id(d,"id"),true);
            case "club/getRoleZhanJi" -> ack=clubs.records(uid,id(d,"id"),false);
            case "room/getDefaultRoomConfig" -> ack=clubs.defaultRoom(uid,id(d,"club_id"));
            default -> clubs.unavailableEconomy();
        }
        return new Reply(ack==null?Map.of("state",0):ack,change==null?List.of():change.notifications());
    }
    private static long id(JsonNode d,String key){return id(d.path(key));}
    private static long id(JsonNode value){String text=value.isIntegralNumber()||value.isString()?value.asString():"";if(!text.matches("[1-9][0-9]{0,8}"))throw bad();return Long.parseLong(text);}
    private static int integer(JsonNode d,String key){JsonNode value=d.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt())throw bad();return value.asInt();}
    private static GameFailure bad(){return new GameFailure(-4,"亲友圈请求参数无效");}
}
