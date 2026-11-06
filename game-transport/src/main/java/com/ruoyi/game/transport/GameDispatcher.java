package com.ruoyi.game.transport;

import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.lobby.PlayerService;
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
    public GameDispatcher(GuestAuthService auth, GameSessionService sessions, PlayerService players,
                          ConnectionRegistry registry, OnlinePresence online) {
        this.auth=auth; this.sessions=sessions; this.players=players; this.registry=registry; this.online=online;
    }
    public record Result(Map<String,Object> ack, ConnectionRegistry.Binding binding) { }
    public Result dispatch(WireRequest request, ConnectionRegistry.Binding binding, Channel channel) {
        if ("login".equals(request.path())) {
            if (!"user".equals(request.api())) throw new GameFailure(-2,"登录通道不支持此接口");
            if ("reg".equals(request.command())) {
                String account=GameJsonCodec.text(request.data(),"account",46);
                if (!account.startsWith("tk_")) throw new GameFailure(-1,"请使用有效游客凭据");
                GuestIdentity identity=auth.verify(account.substring(3));
                if (binding != null && binding.identity().uid()!=identity.uid()) throw new GameFailure(-1,"请重新建立连接");
                String token=sessions.create(identity);
                auth.recordLogin(identity,"REG");
                binding=registry.bind(identity,channel,token); online.update(binding);
                Map<String,Object> loginData=new LinkedHashMap<>();
                loginData.put("role",players.ownRole(identity.uid())); loginData.put("notices",players.notices());
                loginData.put("paoMaDeng",List.of()); loginData.put("m_id",0); loginData.put("saplingIsOpen",false);
                return new Result(Map.of("state",0,"sessionToken",token,"user",Map.of("id",identity.accountId(),"uid",identity.uid()),"loginData",loginData),binding);
            }
            if ("resetfd".equals(request.command())) {
                String token=GameJsonCodec.text(request.data(),"sessionToken",43);
                GuestIdentity identity=sessions.verify(token);
                if (binding!=null && binding.identity().uid()!=identity.uid()) throw new GameFailure(-1,"连接身份不匹配");
                auth.recordLogin(identity,"RESETFD"); binding=registry.bind(identity,channel,token); online.update(binding);
                return new Result(Map.of("state",0),binding);
            }
            throw new GameFailure(-2,"登录操作不可用");
        }
        String token=GameJsonCodec.text(request.data(),"sessionToken",43);
        if (!registry.current(binding) || !binding.token().equals(token)) throw new GameFailure(-1,"连接未认证或已被替换");
        GuestIdentity identity=sessions.verify(token);
        if (identity.uid()!=binding.identity().uid()) throw new GameFailure(-1,"会话身份不匹配");
        return new Result(logic(request,identity.uid()),binding);
    }
    private Map<String,Object> logic(WireRequest req,long uid) {
        JsonNode data=req.data();
        return switch(req.api()+"/"+req.command()) {
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
            case "manager/getNotices" -> Map.of("state",0,"notices",players.notices());
            case "role/createRole" -> Map.of("state",0,"role",players.ownRole(uid));
            case "role/syncError" -> Map.of("state",0); // 不记录客户端提交的任意错误原文。
            default -> throw new GameFailure(-2,"此功能尚未开放");
        };
    }
    private static long uid(JsonNode data,String field) { return uid(data.path(field)); }
    private static long uid(JsonNode id) {
        String value=id.isIntegralNumber()?id.asString():id.isString()?id.asString():"";
        if(!value.matches("[1-9][0-9]{0,8}")) throw new GameFailure(-2,"玩家编号无效");
        return Long.parseLong(value);
    }
}
