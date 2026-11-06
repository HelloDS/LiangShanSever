package com.ruoyi.web.game;

import java.time.Instant;
import java.util.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.admin.GameAdminService;
import com.ruoyi.game.transport.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin")
public class GameAdminController {
    private final GameAdminService service;
    private final OnlinePresence presence;
    private final ConnectionRegistry registry;
    private final com.ruoyi.game.admin.OnlineHistoryService history;
    public GameAdminController(GameAdminService service,OnlinePresence presence,ConnectionRegistry registry,com.ruoyi.game.admin.OnlineHistoryService history) {
        this.service=service;this.presence=presence;this.registry=registry;this.history=history;
    }
    @GetMapping("/dashboard") @PreAuthorize("@ss.hasPermi('game:dashboard:query')")
    public AjaxResult dashboard() {
        Map<String,Object> data=new LinkedHashMap<>(service.dashboard(SecurityUtils.getUserId()));
        data.put("online",presence.snapshot());return AjaxResult.success(data);
    }
    @GetMapping("/online/history") @PreAuthorize("@ss.hasPermi('game:dashboard:query')")
    public AjaxResult history(@RequestParam java.time.LocalDate date){return AjaxResult.success(history.day(SecurityUtils.getUserId(),date));}
    @GetMapping("/players") @PreAuthorize("@ss.hasPermi('game:player:list')")
    public Map<String,Object> players(@RequestParam(required=false) Long uid,@RequestParam(required=false) String name,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {
        return service.players(SecurityUtils.getUserId(),uid,name,status,pageNum,pageSize);
    }
    @GetMapping("/players/{uid}") @PreAuthorize("@ss.hasPermi('game:player:query')")
    public AjaxResult player(@PathVariable long uid) {return AjaxResult.success(service.player(SecurityUtils.getUserId(),uid));}

    public record Command(String reason) { }
    @PostMapping("/players/{uid}/ban") @PreAuthorize("@ss.hasPermi('game:player:ban')")
    public AjaxResult ban(@PathVariable long uid,@RequestHeader("Idempotency-Key") String key,@RequestBody Command body) {
        return command(uid,"ban",key,body);
    }
    @PostMapping("/players/{uid}/unban") @PreAuthorize("@ss.hasPermi('game:player:ban')")
    public AjaxResult unban(@PathVariable long uid,@RequestHeader("Idempotency-Key") String key,@RequestBody Command body) {
        return command(uid,"unban",key,body);
    }
    @PostMapping("/players/{uid}/kick") @PreAuthorize("@ss.hasPermi('game:player:kick')")
    public AjaxResult kick(@PathVariable long uid,@RequestHeader("Idempotency-Key") String key,@RequestBody Command body) {
        return command(uid,"kick",key,body);
    }
    private AjaxResult command(long uid,String action,String key,Command body) {
        return AjaxResult.success(service.command(SecurityUtils.getUserId(),uid,action,body.reason(),key));
    }
    @GetMapping("/online") @PreAuthorize("@ss.hasPermi('game:online:list')")
    public Map<String,Object> online(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize) {
        service.requirePlatform(SecurityUtils.getUserId());
        if(pageNum<1||pageSize<1||pageSize>200)throw new IllegalArgumentException("分页参数无效");
        var sample=presence.sample();
        Map<String,Object> result=new LinkedHashMap<>();result.put("code",200);result.put("asOf",sample.asOf());result.put("dataStatus",sample.dataStatus());
        if(sample.uids()==null) {result.put("rows",List.of());result.put("total",null);return result;}
        List<Map<String,Object>> rows=registry.all().stream().filter(binding->sample.uids().contains(binding.identity().uid()))
                .sorted(Comparator.comparingLong(binding->binding.identity().uid()))
                .skip((long)(pageNum-1)*pageSize).limit(pageSize)
                .map(binding->Map.<String,Object>of("uid",Long.toString(binding.identity().uid()),"node","local",
                        "lastActivity",Instant.ofEpochMilli(binding.lastActivity()).toString(),"state","LOBBY")).toList();
        result.put("rows",rows);result.put("total",sample.uids().size());return result;
    }
}
