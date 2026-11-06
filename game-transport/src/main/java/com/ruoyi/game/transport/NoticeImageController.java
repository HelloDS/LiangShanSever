package com.ruoyi.game.transport;

import com.ruoyi.game.admin.NoticeService;
import com.ruoyi.game.lobby.NoticeArtwork;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class NoticeImageController {
    private final NoticeService notices;
    private final GameJsonCodec codec;
    public NoticeImageController(NoticeService notices,GameJsonCodec codec){this.notices=notices;this.codec=codec;}
    @GetMapping(value="/cgc_server/notices/{id}/{version}.png",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable long id,@PathVariable long version){
        String json=notices.publicContent(id,version);if(json==null)return ResponseEntity.notFound().build();
        var data=codec.read(json);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noCache()).body(NoticeArtwork.render(data.get("title").asString(),data.get("content").asString()));
    }
}
