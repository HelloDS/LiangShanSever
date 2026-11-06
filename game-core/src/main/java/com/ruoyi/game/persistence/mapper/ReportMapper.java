package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ReportMapper {
    @Insert("INSERT INTO user_report(id,reporter_uid,report_type,anonymous,detail,phone_cipher,wechat_cipher,contact_mask,created_at) VALUES(#{id},#{uid},#{type},#{anonymous},#{detail},#{phone},#{wechat},#{mask},#{now})")
    int insert(Map<String,Object> report);
    @Select("<script>SELECT id,CASE WHEN anonymous=TRUE THEN NULL ELSE CAST(reporter_uid AS CHAR) END AS reporterUid,report_type AS type,anonymous,detail,contact_mask AS contactMask,status,process_note AS processNote,created_at AS createdAt,processed_at AS processedAt FROM user_report <if test='status != null'>WHERE status=#{status}</if> ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> list(@Param("status") String status,@Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM user_report <if test='status != null'>WHERE status=#{status}</if></script>")
    long count(@Param("status") String status);
    @Select("SELECT status,anonymous,phone_cipher AS phone,wechat_cipher AS wechat FROM user_report WHERE id=#{id} FOR UPDATE")
    Map<String,Object> lock(String id);
    @Update("UPDATE user_report SET status=#{status},process_note=#{note},processed_by=#{actor},processed_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int process(@Param("id") String id,@Param("status") String status,@Param("note") String note,@Param("actor") long actor,@Param("now") LocalDateTime now);
}
