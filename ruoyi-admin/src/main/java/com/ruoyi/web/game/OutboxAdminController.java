package com.ruoyi.web.game;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.outbox.OutboxAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/outbox")
public class OutboxAdminController {
    private final OutboxAdminService service;
    public OutboxAdminController(OutboxAdminService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:outbox:query')")
    public Map<String,Object> list(@RequestParam(required=false) String status,@RequestParam LocalDate start,@RequestParam LocalDate end,
            @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return service.list(SecurityUtils.getUserId(),status,start,end,pageNum,pageSize);
    }
    @GetMapping("/backlog") @PreAuthorize("@ss.hasPermi('game:outbox:query')")
    public AjaxResult backlog(){return AjaxResult.success(service.backlog(SecurityUtils.getUserId()));}
    public record Retry(String reason){ }
    @PostMapping("/{id}/retry") @PreAuthorize("@ss.hasPermi('game:outbox:retry')")
    public AjaxResult retry(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Retry body){
        return AjaxResult.success(service.retry(SecurityUtils.getUserId(),id,body.reason(),key));
    }
}
