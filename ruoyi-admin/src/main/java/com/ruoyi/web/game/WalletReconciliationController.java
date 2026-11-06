package com.ruoyi.web.game;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.economy.WalletReconciliationAdmin;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/reconciliation")
public class WalletReconciliationController {
    public record Reason(String reason){ }
    private final WalletReconciliationAdmin service;
    public WalletReconciliationController(WalletReconciliationAdmin service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:reconciliation:query')")
    public Map<String,Object> list(@RequestParam LocalDate start,@RequestParam LocalDate end,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return service.list(SecurityUtils.getUserId(),start,end,status,pageNum,pageSize);
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('game:reconciliation:query')")
    public AjaxResult detail(@PathVariable String id,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return AjaxResult.success(service.detail(SecurityUtils.getUserId(),id,status,pageNum,pageSize));
    }
    @PostMapping("/today") @PreAuthorize("@ss.hasPermi('game:reconciliation:run')")
    public AjaxResult trigger(@RequestBody Reason body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.trigger(SecurityUtils.getUserId(),body.reason(),key));}
    @PostMapping("/{id}/retry") @PreAuthorize("@ss.hasPermi('game:reconciliation:retry')")
    public AjaxResult retry(@PathVariable String id,@RequestBody Reason body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.retry(SecurityUtils.getUserId(),id,body.reason(),key));}
}
