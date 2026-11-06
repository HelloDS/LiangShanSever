package com.ruoyi.game.transport;

import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.lobby.PlayerService;
import com.ruoyi.game.lobby.LobbyService;
import com.ruoyi.game.lobby.ReportService;
import com.ruoyi.game.protocol.*;
import tools.jackson.databind.JsonNode;
import io.netty.channel.Channel;

@Component
public class GameDispatcher {
    private final GuestAuthService auth;
    private final GameSessionService sessions;
    private final PlayerService players;
    private final ConnectionRegistry registry;
    private final OnlinePresence online;
    private final LobbyService lobby;
    private final GameRateLimiter limiter;
    private final ReportService reports;
    private final GameLoginService login;
    private final ClubWireRouter clubs;
    public GameDispatcher(GuestAuthService auth, GameSessionService sessions, PlayerService players,
                          ConnectionRegistry registry, OnlinePresence online, LobbyService lobby,GameRateLimiter limiter,ReportService reports,GameLoginService login,ClubWireRouter clubs) {
        this.auth=auth; this.sessions=sessions; this.players=players; this.registry=registry; this.online=online;this.lobby=lobby;this.limiter=limiter;
        this.reports=reports;this.login=login;this.clubs=clubs;
    }
    public record Result(Map<String,Object> ack,ConnectionRegistry.Binding binding,List<com.ruoyi.game.club.ClubService.Notification> notifications) {
        public Result(Map<String,Object> ack,ConnectionRegistry.Binding binding){this(ack,binding,List.of());}
    }
    public Result dispatch(WireRequest request, ConnectionRegistry.Binding binding, Channel channel) {
        if ("login".equals(request.path())) {
            if (!"user".equals(request.api())) throw new GameFailure(-2,"登录通道不支持此接口");
            if ("reg".equals(request.command())) {
                String account=GameJsonCodec.text(request.data(),"account",46);
                if (!account.startsWith("tk_")) throw new GameFailure(-5,"请使用有效游客凭据");
                GuestIdentity identity=auth.verify(account.substring(3));
                limiter.account(identity.uid());
                if (binding != null && binding.identity().uid()!=identity.uid()) throw new GameFailure(-5,"请重新建立连接");
                Map<String,Object> role=players.ownRole(identity.uid());
                Map<String,Object> notices=players.notices();
                String token=login.login(identity,"REG",null);
                binding=registry.bind(identity,channel,token); online.connected(binding);
                Map<String,Object> loginData=new LinkedHashMap<>();
                loginData.put("role",role); loginData.put("notices",notices);
                loginData.put("paoMaDeng",List.of()); loginData.put("m_id",null); loginData.put("saplingIsOpen",false);
                return new Result(Map.of("state",0,"sessionToken",token,"user",Map.of("id",identity.accountId(),"uid",identity.uid()),"loginData",loginData),binding);
            }
            if ("resetfd".equals(request.command())) {
                String token=GameJsonCodec.text(request.data(),"sessionToken",43);
                GuestIdentity identity=sessions.verify(token);
                limiter.account(identity.uid());
                if (binding!=null && binding.identity().uid()!=identity.uid()) throw new GameFailure(-5,"连接身份不匹配");
                login.login(identity,"RESETFD",token); binding=registry.bind(identity,channel,token); online.connected(binding);
                return new Result(Map.of("state",0),binding);
            }
            throw new GameFailure(-2,"登录操作不可用");
        }
        String token=GameJsonCodec.text(request.data(),"sessionToken",43);
        if (!registry.current(binding) || !binding.token().equals(token)) throw new GameFailure(-5,"连接未认证或已被替换");
        GuestIdentity identity=sessions.verify(token);
        if (identity.uid()!=binding.identity().uid()) throw new GameFailure(-5,"会话身份不匹配");
        limiter.account(identity.uid());
        if(clubs.supports(request)){
            var reply=clubs.dispatch(request,identity.uid());return new Result(reply.ack(),binding,reply.notifications());
        }
        return new Result(logic(request,identity.uid()),binding);
    }
    private Map<String,Object> logic(WireRequest req,long uid) {
        JsonNode data=req.data();
        return switch(req.api()+"/"+req.command()) {
            case "role/exchangeCard","role/exchangeDiamond","club/transferDiamond","club/transferToClub","club/exchangeCard","club/exchangeDiamond","cfg/getExchangeRate" -> throw new GameFailure(-18,"兑换或划转规则尚未确认，暂不可执行");
            case "role/syncCard" -> { var role=players.ownRole(uid); yield Map.of("state",0,"card",role.get("card"),"diamond",role.get("diamond")); }
            case "role/getRoleInfo" -> Map.of("state",0,"role",players.publicRole(uid(data,"targetUid")));
            case "role/getRoles" -> {
                JsonNode uids=data.get("uids");
                if(uids==null||!uids.isArray()||uids.size()>200) throw new GameFailure(-2,"玩家列表无效");
                List<Map<String,Object>> roles=new ArrayList<>();
                for(JsonNode id:uids) roles.add(players.publicRole(uid(id)));
                yield Map.of("state",0,"roles",roles);
            }
            case "role/syncPos" -> {
                if(!data.path("latitude").isNumber()||!data.path("longitude").isNumber()) throw new GameFailure(-2,"位置参数无效");
                players.location(uid,data.get("latitude").asDouble(),data.get("longitude").asDouble()); yield Map.of("state",0);
            }
            case "manager/getNotices" -> {Map<String,Object> response=new LinkedHashMap<>();response.put("state",0);response.put("notices",players.notices());yield response;}
            case "role/createRole" -> Map.of("state",0,"role",players.ownRole(uid));
            case "role/syncError" -> Map.of("state",0); // 不记录客户端提交的任意错误原文。
            case "role/syncNetWorkType" -> Map.of("state",0);
            case "role/checkRolePhone" -> Map.of("state",0,"is_approved",0);
            case "identify/getIdentifyInfo" -> Map.of("state",0,"isIdentify",-1);
            case "game/returnGame" -> {Map<String,Object> response=new LinkedHashMap<>();response.put("state",0);response.put("room",null);yield response;}
            case "role/getZhanji" -> Map.of("state",0,"zhanji",List.of());
            case "activity/getActivitys" -> Map.of("state",0,"activitys",List.of());
            case "activity/getActivityOpenType" -> Map.of("state",0,"status",Map.of());
            case "koi/getInfo" -> lobby.koi(uid);
            case "activity/incrActivityVisit" -> {lobby.visit(uid,GameJsonCodec.text(data,"activityId",48));yield Map.of("state",0);}
            case "cfg/getGame" -> lobby.gameConfiguration(GameJsonCodec.text(data,"game_id",4));
            case "inform/addInformInfo" -> {
                if(!data.path("contact_way").isIntegralNumber())throw new GameFailure(-4,"联系类型无效");
                reports.submit(uid,new ReportService.Submission(GameJsonCodec.text(data,"type",32),data.get("contact_way").asInt(),optionalText(data,"tel",32),optionalText(data,"wechat",80),GameJsonCodec.text(data,"detail",2000)));
                yield Map.of("state",0);
            }
            default -> throw new GameFailure(-2,"此功能尚未开放");
        };
    }
    private static long uid(JsonNode data,String field) { return uid(data.path(field)); }
    private static String optionalText(JsonNode data,String field,int max){
        JsonNode value=data.get(field);if(value==null||value.isNull())return "";
        if(!value.isString()||value.asString().length()>max)throw new GameFailure(-4,"字段无效："+field);
        return value.asString();
    }
    private static long uid(JsonNode id) {
        String value=id.isIntegralNumber()?id.asString():id.isString()?id.asString():"";
        if(!value.matches("[1-9][0-9]{0,8}")) throw new GameFailure(-2,"玩家编号无效");
        return Long.parseLong(value);
    }
}
