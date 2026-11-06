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
    public GameHttpController(GuestAuthService auth,ConnectionRegistry connections,GameJsonCodec codec) {
        this.auth=auth;this.connections=connections;this.codec=codec;
    }
    @GetMapping(value="/api/auth/config",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> config() {return json(Map.of("state",0,"guest",true,"wx",false,"sms",false));}

    @PostMapping(value="/api/auth/{action}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> auth(@PathVariable String action,HttpServletRequest request) throws IOException {
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
