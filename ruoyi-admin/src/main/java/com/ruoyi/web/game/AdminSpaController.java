package com.ruoyi.web.game;

import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminSpaController {
    @GetMapping({"/admin", "/admin/", "/admin/{*path}"})
    public ResponseEntity<Resource> admin(@PathVariable(required=false) String path,
                                         @RequestHeader(value="Accept",defaultValue="") String accept) {
        String relative=path==null?"":path.replaceFirst("^/","");
        Path normalized=Path.of(relative).normalize();
        if(normalized.isAbsolute()||normalized.startsWith("..")||relative.contains("\\"))return ResponseEntity.notFound().build();
        Resource resource=new ClassPathResource("static/admin/"+normalized);
        if(!resource.exists()||!resource.isReadable()||relative.isBlank()) {
            if(!accept.contains("text/html")||relative.contains("."))return ResponseEntity.notFound().build();
            resource=new ClassPathResource("static/admin/index.html");
            if(!resource.exists())return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .cacheControl(CacheControl.noCache()).body(resource);
    }
}
