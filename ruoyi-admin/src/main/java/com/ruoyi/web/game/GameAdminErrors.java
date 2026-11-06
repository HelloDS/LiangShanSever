package com.ruoyi.web.game;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={GameAdminController.class,NoticeAdminController.class,ReportAdminController.class})
@Order(-20)
public class GameAdminErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(400,e.getMessage());}
    @ExceptionHandler(SecurityException.class)
    public AjaxResult forbidden(SecurityException e){return AjaxResult.error(403,e.getMessage());}
}
