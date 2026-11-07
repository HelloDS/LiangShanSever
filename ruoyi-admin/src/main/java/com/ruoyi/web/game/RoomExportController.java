package com.ruoyi.web.game;

import java.io.IOException;
import java.util.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.admin.GameDataUnavailable;
import com.ruoyi.game.room.RoomExports;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/room-exports")
@PreAuthorize("@ss.hasPermi('game:room:query') && @ss.hasPermi('game:room:export')")
public class RoomExportController {
    private final RoomExports jobs;private final RoomExportFiles files;private final boolean enabled;
    public RoomExportController(RoomExports jobs,RoomExportFiles files,@Value("${game.exports.enabled:true}") boolean enabled){this.jobs=jobs;this.files=files;this.enabled=enabled;}
    public record Request(RoomExports.Query query,String reason){ }
    @PostMapping public AjaxResult create(@RequestBody Request request,@RequestHeader("Idempotency-Key") String key){
        if(!enabled)throw new GameDataUnavailable("导出生成已暂停");
        if(request==null||request.query()==null)throw new IllegalArgumentException("缺少导出筛选");
        return AjaxResult.success(jobs.create(SecurityUtils.getUserId(),request.query(),request.reason(),key));
    }
    @GetMapping public AjaxResult list(){return AjaxResult.success(Map.of("jobs",jobs.list(SecurityUtils.getUserId()),"generationEnabled",enabled));}
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable String id){return AjaxResult.success(jobs.detail(SecurityUtils.getUserId(),id));}
    @GetMapping("/{id}/download") public ResponseEntity<InputStreamResource> download(@PathVariable String id){
        var download=jobs.download(SecurityUtils.getUserId(),id);
        try{return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).contentLength(download.bytes())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("rooms-"+id+".csv").build().toString()).body(new InputStreamResource(files.open(download)));}
        catch(IOException unavailable){throw new GameDataUnavailable("导出文件不可用，请重新生成或稍后重试");}
    }
}
