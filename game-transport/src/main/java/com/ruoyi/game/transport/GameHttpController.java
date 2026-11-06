package com.ruoyi.game.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class GameHttpController {
    private final GuestAuthService auth;
    private final ConnectionRegistry connections;
    private final GameJsonCodec codec;
    private final GameRateLimiter limiter;
    @org.springframework.beans.factory.annotation.Value("${game.customer-wechat:}")
    private String customerWechat;
    public GameHttpController(GuestAuthService auth,ConnectionRegistry connections,GameJsonCodec codec,GameRateLimiter limiter) {
        this.auth=auth;this.connections=connections;this.codec=codec;this.limiter=limiter;
    }
    @GetMapping(value="/api/auth/config",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> config() {return json(Map.of("state",0,"guest",true,"wx",false,"sms",false));}
    @GetMapping(value="/api/site",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> site(){return json(Map.of("kefuWechat",customerWechat));}
    @GetMapping(value="/api/ha",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> ha(){return json(Map.of("ha","single"));}
    @GetMapping(value="/cgc_server/gameList.php",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> games() throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new org.springframework.core.io.ClassPathResource("game/game-list.json").getContentAsString(StandardCharsets.UTF_8));
    }

    @PostMapping(value="/api/auth/{action}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> auth(@PathVariable String action,HttpServletRequest request) throws IOException {
        if(!"logout".equals(action))limiter.authentication(request.getRemoteAddr());
        byte[] bytes=request.getInputStream().readNBytes(GameJsonCodec.MAX_BYTES+1);
        if(bytes.length>GameJsonCodec.MAX_BYTES) return json(GameJsonCodec.error(-2,"请求过长"));
        var data=codec.read(new String(bytes,StandardCharsets.UTF_8));
        return switch(action) {
            case "guest" -> json(Map.of("state",0,"token",auth.createGuest()));
            case "check" -> {auth.verify(GameJsonCodec.text(data,"token",43));yield json(Map.of("state",0));}
            case "logout" -> {var identity=auth.logout(GameJsonCodec.text(data,"token",43));if(identity!=null)connections.disconnect(identity.uid());yield json(Map.of("state",0));}
            default -> json(GameJsonCodec.error(-2,"此登录方式尚未开放"));
        };
    }
    private ResponseEntity<String> json(Object value) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(codec.write(value));}
}
