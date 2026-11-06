package com.ruoyi.web.game;

import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.lobby.ReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/reports")
public class ReportAdminController {
    private final ReportService reports;
    public ReportAdminController(ReportService reports){this.reports=reports;}
    public record Process(String status,String note){ }
    public record Contact(String reason){ }
    @GetMapping @PreAuthorize("@ss.hasPermi('game:report:list')")
    public Map<String,Object> list(@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return reports.list(SecurityUtils.getUserId(),status,pageNum,pageSize);
    }
    @PostMapping("/{id}/process") @PreAuthorize("@ss.hasPermi('game:report:process')")
    public AjaxResult process(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Process body){
        return AjaxResult.success(reports.process(SecurityUtils.getUserId(),id,body.status(),body.note(),key));
    }
    @PostMapping("/{id}/contact") @PreAuthorize("@ss.hasPermi('game:report:contact')")
    public AjaxResult contact(@PathVariable String id,@RequestBody Contact body){
        return AjaxResult.success(reports.contacts(SecurityUtils.getUserId(),id,body.reason()));
    }
}
