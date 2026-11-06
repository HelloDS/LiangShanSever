package com.ruoyi.game.transport;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.protocol.GameJsonCodec;

@RestControllerAdvice(assignableTypes=GameHttpController.class)
@org.springframework.core.annotation.Order(-100)
public class GameHttpErrors {
    private final GameJsonCodec codec;
    public GameHttpErrors(GameJsonCodec codec) {this.codec=codec;}
    @ExceptionHandler(GameFailure.class)
    public ResponseEntity<String> failure(GameFailure e) {return response(200,e.state(),e.getMessage());}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badInput() {return response(200,-2,"请求参数无效");}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unavailable() {return response(503,-5,"登录服务暂不可用，请稍后重试");}
    private ResponseEntity<String> response(int http,int state,String message) {
        return ResponseEntity.status(http).contentType(MediaType.APPLICATION_JSON).body(codec.write(GameJsonCodec.error(state,message)));
    }
}
