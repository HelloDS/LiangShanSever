package com.ruoyi.web.game;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.economy.WalletAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/wallets/{type}/{owner}/{currency}")
public class WalletAdminController {
    private final WalletAdminService service;
    public WalletAdminController(WalletAdminService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:wallet:query')")
    public Map<String,Object> ledger(@PathVariable String type,@PathVariable long owner,@PathVariable String currency,@RequestParam LocalDate start,@RequestParam LocalDate end,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){return service.ledger(SecurityUtils.getUserId(),service.account(type,owner,currency),start,end,pageNum,pageSize);}
    public record Check(String reason){ }
    @PostMapping("/reconcile") @PreAuthorize("@ss.hasPermi('game:wallet:reconcile')")
    public AjaxResult reconcile(@PathVariable String type,@PathVariable long owner,@PathVariable String currency,@RequestHeader("Idempotency-Key") String key,@RequestBody Check body){return AjaxResult.success(service.reconcile(SecurityUtils.getUserId(),service.account(type,owner,currency),body.reason(),key));}
}
