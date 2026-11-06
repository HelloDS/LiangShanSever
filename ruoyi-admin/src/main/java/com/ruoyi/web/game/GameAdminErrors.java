package com.ruoyi.web.game;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={GameAdminController.class,NoticeAdminController.class,ReportAdminController.class,ClubAdminController.class,WalletAdminController.class,OutboxAdminController.class,RoomAdminController.class,WalletReconciliationController.class})
@Order(-20)
public class GameAdminErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(400,e.getMessage());}
    @ExceptionHandler(com.ruoyi.game.auth.GameFailure.class)
    public AjaxResult business(com.ruoyi.game.auth.GameFailure e){return AjaxResult.error(400,e.getMessage());}
    @ExceptionHandler(com.ruoyi.game.admin.GameDataUnavailable.class)
    public org.springframework.http.ResponseEntity<AjaxResult> unavailable(com.ruoyi.game.admin.GameDataUnavailable e){return org.springframework.http.ResponseEntity.status(503).body(AjaxResult.error(503,e.getMessage()));}
    @ExceptionHandler(SecurityException.class)
    public AjaxResult forbidden(SecurityException e){return AjaxResult.error(403,e.getMessage());}
}
