package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface WalletAdminMapper {
    @Select("SELECT CAST(l.id AS CHAR) AS id,l.transaction_id AS transactionId,t.business_key AS businessKey,t.kind,t.rule_version AS ruleVersion,t.reference_id AS reference,CAST(l.available_delta AS CHAR) AS availableDelta,CAST(l.frozen_delta AS CHAR) AS frozenDelta,CAST(l.balance_after AS CHAR) AS balanceAfter,CAST(l.frozen_after AS CHAR) AS frozenAfter,l.created_at AS createdAt FROM wallet_ledger l JOIN wallet_transaction t ON t.id=l.transaction_id WHERE l.owner_type=#{type} AND l.owner_id=#{owner} AND l.currency=#{currency} AND l.created_at>=#{start} AND l.created_at<#{end} ORDER BY l.created_at DESC,l.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> ledger(@Param("type") String type,@Param("owner") long owner,@Param("currency") String currency,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM wallet_ledger WHERE owner_type=#{type} AND owner_id=#{owner} AND currency=#{currency} AND created_at>=#{start} AND created_at<#{end}")
    long count(@Param("type") String type,@Param("owner") long owner,@Param("currency") String currency,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
    @Insert("INSERT INTO wallet_reconciliation(id,owner_type,owner_id,currency,status,result_json,checked_by,checked_at) VALUES(#{id},#{type},#{owner},#{currency},#{status},#{result},#{actor},#{now})") int reconcile(Map<String,Object> row);
}
