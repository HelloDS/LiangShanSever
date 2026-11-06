package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface AuditMapper {
    String COLUMNS="CAST(a.id AS CHAR) AS id,CAST(a.operator_id AS CHAR) AS operatorId,a.action,a.target,a.reason,a.created_at AS createdAt,a.operation_id AS operationId,o.status AS operationStatus,o.completed_at AS completedAt";
    String JOIN=" FROM admin_audit a LEFT JOIN admin_operation o ON o.operation_id=a.operation_id AND o.actor_id=a.operator_id AND o.action=a.action AND o.target=a.target ";
    String FILTER=" WHERE a.created_at &gt;=#{start} AND a.created_at &lt;#{end} <if test='operator!=null'>AND a.operator_id=#{operator}</if><if test='action!=null'>AND a.action=#{action}</if><if test='target!=null'>AND a.target=#{target}</if><if test='operationId!=null'>AND a.operation_id=#{operationId}</if>";

    @Select("<script>SELECT "+COLUMNS+JOIN+FILTER+" ORDER BY a.created_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    @Options(timeout=2)
    List<Map<String,Object>> list(@Param("operator") Long operator,@Param("action") String action,@Param("target") String target,@Param("operationId") String operationId,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("offset") int offset,@Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM admin_audit a"+FILTER+"</script>")
    @Options(timeout=2)
    long count(@Param("operator") Long operator,@Param("action") String action,@Param("target") String target,@Param("operationId") String operationId,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT "+COLUMNS+",CAST(a.before_json AS CHAR) AS beforeJson,CAST(a.after_json AS CHAR) AS afterJson"+JOIN+" WHERE a.id=#{id}")
    @Options(timeout=2)
    Map<String,Object> detail(long id);
}
