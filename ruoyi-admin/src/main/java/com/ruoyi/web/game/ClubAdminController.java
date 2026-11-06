package com.ruoyi.web.game;

import java.util.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.club.ClubAdminService;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.transport.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/clubs")
public class ClubAdminController {
    private final ClubAdminService service;
    private final OnlinePresence presence;
    private final ConnectionRegistry registry;
    private final GameJsonCodec codec;
    public ClubAdminController(ClubAdminService service,OnlinePresence presence,ConnectionRegistry registry,GameJsonCodec codec){this.service=service;this.presence=presence;this.registry=registry;this.codec=codec;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:club:list')")
    public Map<String,Object> list(@RequestParam(required=false) Long id,@RequestParam(required=false) String name,@RequestParam(required=false) Long owner,@RequestParam(required=false) String status,@RequestParam(defaultValue="id") String sort,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        var sample=presence.sample();return service.list(SecurityUtils.getUserId(),id,name,owner,status,sort,pageNum,pageSize,sample.uids(),sample.dataStatus(),sample.asOf());
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('game:club:query')")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(SecurityUtils.getUserId(),id));}
    @GetMapping("/{id}/members") @PreAuthorize("@ss.hasPermi('game:club:query')")
    public Map<String,Object> members(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){return service.members(SecurityUtils.getUserId(),id,pageNum,pageSize,presence.sample(true).uids());}
    @GetMapping("/{id}/applications") @PreAuthorize("@ss.hasPermi('game:club:review')")
    public Map<String,Object> applications(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){return service.applications(SecurityUtils.getUserId(),id,pageNum,pageSize);}
    @PostMapping("/{id}/review") @PreAuthorize("@ss.hasPermi('game:club:review')")
    public AjaxResult review(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"review",key,body);}
    @PostMapping("/{id}/kick") @PreAuthorize("@ss.hasPermi('game:club:member')")
    public AjaxResult kick(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"kick",key,body);}
    @PostMapping("/{id}/manager") @PreAuthorize("@ss.hasPermi('game:club:role')")
    public AjaxResult manager(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"manager",key,body);}
    @PostMapping("/{id}/unmanager") @PreAuthorize("@ss.hasPermi('game:club:role')")
    public AjaxResult unmanager(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"unmanager",key,body);}
    @PostMapping("/{id}/config") @PreAuthorize("@ss.hasPermi('game:club:config')")
    public AjaxResult config(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"config",key,body);}
    @PostMapping("/{id}/status") @PreAuthorize("@ss.hasPermi('game:club:status')")
    public AjaxResult status(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody ClubAdminService.Command body){return command(id,"status",key,body);}
    private AjaxResult command(long id,String action,String key,ClubAdminService.Command body){
        var command=new ClubAdminService.Command(action,body.targetUid(),body.kind(),body.decision(),body.onlyManagers(),body.status(),body.reason());
        var result=service.command(SecurityUtils.getUserId(),id,command,key);
        for(var notification:result.notifications())registry.push(notification.recipients(),codec.write(notification.packet()));
        return AjaxResult.success(result.result());
    }
}
