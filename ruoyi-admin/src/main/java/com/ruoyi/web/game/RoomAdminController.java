package com.ruoyi.web.game;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.game.room.RoomAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/game-admin/rooms")
public class RoomAdminController {
    private final RoomAdminService service;
    public RoomAdminController(RoomAdminService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('game:room:query')")
    public Map<String,Object> list(@RequestParam(required=false) String roomCode,@RequestParam(required=false) Long ownerUid,@RequestParam(required=false) Long creatorUid,
            @RequestParam(required=false) String status,@RequestParam LocalDate start,@RequestParam LocalDate end,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize){
        return service.list(SecurityUtils.getUserId(),roomCode,ownerUid,creatorUid,status,start,end,pageNum,pageSize);
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('game:room:query')")
    public AjaxResult detail(@PathVariable String id){return AjaxResult.success(service.detail(SecurityUtils.getUserId(),id));}
}
