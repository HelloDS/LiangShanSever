package com.ruoyi.web.game;

import java.util.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.admin.NoticeService;
import com.ruoyi.game.lobby.NoticeArtwork;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/notices")
public class NoticeAdminController {
    private final NoticeService service;
    public NoticeAdminController(NoticeService service){this.service=service;}
    public record Draft(Long id,String title,String content,String reason) { }
    public record Publish(long version,String reason) { }
    public record Reason(String reason) { }
    @GetMapping @PreAuthorize("@ss.hasPermi('game:notice:list')")
    public Map<String,Object> list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){return service.list(SecurityUtils.getUserId(),pageNum,pageSize);}
    @GetMapping("/{id}/versions") @PreAuthorize("@ss.hasPermi('game:notice:list')")
    public AjaxResult versions(@PathVariable long id){return AjaxResult.success(service.versions(SecurityUtils.getUserId(),id));}
    @PostMapping("/drafts") @PreAuthorize("@ss.hasPermi('game:notice:edit')")
    public AjaxResult draft(@RequestBody Draft body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.draft(SecurityUtils.getUserId(),body.id(),body.title(),body.content(),body.reason(),key));}
    @PostMapping("/{id}/publish") @PreAuthorize("@ss.hasPermi('game:notice:publish')")
    public AjaxResult publish(@PathVariable long id,@RequestBody Publish body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.publish(SecurityUtils.getUserId(),id,body.version(),false,body.reason(),key));}
    @PostMapping("/{id}/rollback") @PreAuthorize("@ss.hasPermi('game:notice:publish')")
    public AjaxResult rollback(@PathVariable long id,@RequestBody Publish body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.publish(SecurityUtils.getUserId(),id,body.version(),true,body.reason(),key));}
    @PostMapping("/{id}/unpublish") @PreAuthorize("@ss.hasPermi('game:notice:publish')")
    public AjaxResult unpublish(@PathVariable long id,@RequestBody Reason body,@RequestHeader("Idempotency-Key") String key){return AjaxResult.success(service.unpublish(SecurityUtils.getUserId(),id,body.reason(),key));}
    @PostMapping(value="/preview",produces=MediaType.IMAGE_PNG_VALUE) @PreAuthorize("@ss.hasPermi('game:notice:edit')")
    public byte[] preview(@RequestBody Draft body){
        service.list(SecurityUtils.getUserId(),1,1);
        if(body.title()==null||body.title().length()>24||body.content()==null||body.content().length()>900)throw new IllegalArgumentException("公告内容过长");
        return NoticeArtwork.render(body.title(),body.content());
    }
}
