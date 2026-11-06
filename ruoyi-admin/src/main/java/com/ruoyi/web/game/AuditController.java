package com.ruoyi.web.game;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.admin.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/audit")
public class AuditController {
    private final AuditService service;
    public AuditController(AuditService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:audit:query')")
    public Map<String,Object> list(@RequestParam(required=false) Long operatorId,@RequestParam(required=false) String action,@RequestParam(required=false) String target,@RequestParam(required=false) String operationId,
            @RequestParam LocalDate start,@RequestParam LocalDate end,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return service.list(SecurityUtils.getUserId(),operatorId,action,target,operationId,start,end,pageNum,pageSize);
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('game:audit:query')")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(SecurityUtils.getUserId(),id));}
}
