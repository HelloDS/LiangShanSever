package com.ruoyi.game.admin;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.game.persistence.mapper.NoticeMapper;
import com.ruoyi.game.protocol.GameJsonCodec;

@Service
public class NoticeService {
    private final NoticeMapper mapper;
    private final GameAdminService permissions;
    private final AdminOperations operations;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final String base;
    public NoticeService(NoticeMapper mapper,GameAdminService permissions,AdminOperations operations,GameJsonCodec codec,Clock clock,
                         @Value("${game.public-base-url:http://localhost:8080}") String base){
        this.mapper=mapper;this.permissions=permissions;this.operations=operations;this.codec=codec;this.clock=clock;this.base=base;
    }
    public Map<String,Object> list(long actor,int page,int limit){
        permissions.requirePlatform(actor);
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        return Map.of("code",200,"rows",mapper.list((page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),"total",mapper.count());
    }
    public List<Map<String,Object>> versions(long actor,long id){permissions.requirePlatform(actor);return mapper.versions("notice:"+id).stream().map(AdminTimes::utc).toList();}
    public Map<String,Object> draft(long actor,Long id,String title,String content,String reason,String key){
        permissions.requirePlatform(actor);validate(title,content);
        return operations.execute(actor,"notice:draft",id==null?"new":id.toString(),reason,key,Map.of("title",title,"content",content),()->{
            mapper.lockNamespace();
            long actualId;
            if(id==null){Map<String,Object> values=new HashMap<>(Map.of("now",now()));mapper.create(values);actualId=((Number)values.get("id")).longValue();}
            else{if(mapper.lock(id)==null)throw new IllegalArgumentException("公告不存在");actualId=id;}
            long version=mapper.maxVersion("notice:"+actualId)+1;
            mapper.draft("notice:"+actualId,version,codec.write(Map.of("title",title,"content",content)),actor,now());
            return new AdminOperations.Change(Map.of(),Map.of("id",actualId,"version",version,"status","DRAFT"),Map.of("id",Long.toString(actualId),"version",version));
        });
    }
    public Map<String,Object> publish(long actor,long id,long version,boolean rollback,String reason,String key){
        permissions.requirePlatform(actor);
        return operations.execute(actor,rollback?"notice:rollback":"notice:publish",Long.toString(id),reason,key,Map.of("version",version),()->{
            mapper.lockNamespace();
            Map<String,Object> before=mapper.lock(id),source=mapper.version("notice:"+id,version);
            if(before==null||source==null)throw new IllegalArgumentException("公告版本不存在");
            if(mapper.otherPublished(id)>=4)throw new IllegalArgumentException("客户端最多同时展示 4 条公告，请先下架其他公告");
            var payload=codec.read((String)source.get("payload"));String title=payload.get("title").asString(),content=payload.get("content").asString();validate(title,content);
            long chosen=version;
            if(rollback){
                if(!"PUBLISHED".equals(source.get("status")))throw new IllegalArgumentException("只能回滚至已发布的历史版本");
                chosen=mapper.maxVersion("notice:"+id)+1;mapper.draft("notice:"+id,chosen,codec.write(payload),actor,now());
            }else if(!"DRAFT".equals(source.get("status")))throw new IllegalArgumentException("该版本已发布，请创建新草稿");
            mapper.publishVersion("notice:"+id,chosen,actor,now());
            mapper.publish(id,chosen,title,content,base+"/cgc_server/notices/"+id+"/"+chosen+".png",now());
            return new AdminOperations.Change(before,Map.of("id",id,"version",chosen,"published",true),Map.of("id",Long.toString(id),"version",chosen));
        });
    }
    public Map<String,Object> unpublish(long actor,long id,String reason,String key){
        permissions.requirePlatform(actor);
        return operations.execute(actor,"notice:unpublish",Long.toString(id),reason,key,Map.of(),()->{
            mapper.lockNamespace();var before=mapper.lock(id);if(before==null)throw new IllegalArgumentException("公告不存在");
            mapper.unpublish(id,now());return new AdminOperations.Change(before,Map.of("published",false),Map.of("id",Long.toString(id)));
        });
    }
    public String publicContent(long id,long version){return mapper.publicContent(id,version);}
    private void validate(String title,String content){
        if(title==null||title.isBlank()||title.codePointCount(0,title.length())>12)throw new IllegalArgumentException("公告标题为 1 至 12 字");
        if(content==null||content.isBlank()||content.codePointCount(0,content.length())>450||content.lines().count()>18)throw new IllegalArgumentException("公告正文限 450 字、18 行");
        com.ruoyi.game.lobby.NoticeArtwork.validate(content);
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
