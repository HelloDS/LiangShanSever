package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface NoticeMapper {
    @Select("SELECT version FROM config_namespace WHERE id='notices' FOR UPDATE")
    long lockNamespace();
    @Select("SELECT CAST(id AS CHAR) AS id,title,content,published,version,updated_at AS updatedAt FROM game_notice ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> list(@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM game_notice") long count();
    @Insert("INSERT INTO game_notice(title,content,updated_at) VALUES('', '', #{now})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int create(Map<String,Object> values);
    @Select("SELECT id,title,content,published,version FROM game_notice WHERE id=#{id} FOR UPDATE")
    Map<String,Object> lock(long id);
    @Select("SELECT COALESCE(MAX(version),0) FROM config_version WHERE config_key=#{key}") long maxVersion(String key);
    @Insert("INSERT INTO config_version(config_key,version,payload,status,created_by,created_at) VALUES(#{key},#{version},#{payload},'DRAFT',#{actor},#{now})")
    int draft(@Param("key") String key,@Param("version") long version,@Param("payload") String payload,@Param("actor") long actor,@Param("now") LocalDateTime now);
    @Select("SELECT version,payload,status,created_at AS createdAt,published_at AS publishedAt FROM config_version WHERE config_key=#{key} ORDER BY version DESC LIMIT 50")
    List<Map<String,Object>> versions(String key);
    @Select("SELECT payload,status FROM config_version WHERE config_key=#{key} AND version=#{version}")
    Map<String,Object> version(@Param("key") String key,@Param("version") long version);
    @Select("SELECT COUNT(*) FROM game_notice WHERE published=TRUE AND id<>#{id}") long otherPublished(long id);
    @Update("UPDATE config_version SET status='PUBLISHED',published_by=#{actor},published_at=#{now} WHERE config_key=#{key} AND version=#{version} AND status='DRAFT'")
    int publishVersion(@Param("key") String key,@Param("version") long version,@Param("actor") long actor,@Param("now") LocalDateTime now);
    @Update("UPDATE game_notice SET title=#{title},content=#{content},published=TRUE,version=#{version},image_link=#{image},updated_at=#{now} WHERE id=#{id}")
    int publish(@Param("id") long id,@Param("version") long version,@Param("title") String title,@Param("content") String content,@Param("image") String image,@Param("now") LocalDateTime now);
    @Update("UPDATE game_notice SET published=FALSE,updated_at=#{now} WHERE id=#{id}")
    int unpublish(@Param("id") long id,@Param("now") LocalDateTime now);
    @Select("SELECT c.payload FROM config_version c JOIN game_notice n ON c.config_key=CONCAT('notice:',n.id) AND c.version=n.version WHERE n.id=#{id} AND n.version=#{version} AND n.published=TRUE")
    String publicContent(@Param("id") long id,@Param("version") long version);
}
