package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface WalletMapper {
    @Insert("INSERT INTO wallet_transaction(id,business_key,kind,request_hash,rule_version,reference_id,status,created_at) VALUES(#{id},#{key},#{kind},#{hash},#{rule},#{reference},'RUNNING',#{now}) ON DUPLICATE KEY UPDATE id=id") int begin(Map<String,Object> row);
    @Select("SELECT id,request_hash AS requestHash,status,receipt_json AS receipt FROM wallet_transaction WHERE business_key=#{key} FOR UPDATE") Map<String,Object> transaction(String key);
    @Update("UPDATE wallet_transaction SET status='POSTED',receipt_json=#{receipt},posted_at=#{now} WHERE id=#{id} AND status='RUNNING'") int posted(@Param("id") String id,@Param("receipt") String receipt,@Param("now") LocalDateTime now);
    @Select("SELECT balance,frozen,version FROM wallet_account WHERE uid=#{owner} AND currency=#{currency} FOR UPDATE") Map<String,Object> personal(@Param("owner") long owner,@Param("currency") String currency);
    @Select("SELECT balance,frozen,version FROM club_wallet_account WHERE club_id=#{owner} AND currency=#{currency} FOR UPDATE") Map<String,Object> club(@Param("owner") long owner,@Param("currency") String currency);
    @Select("SELECT balance,0 AS frozen,version FROM wallet_system_account WHERE currency=#{currency} FOR UPDATE") Map<String,Object> system(String currency);
    @Update("UPDATE wallet_account SET balance=#{balance},frozen=#{frozen},version=version+1 WHERE uid=#{owner} AND currency=#{currency} AND version=#{version}") int personalBalance(Map<String,Object> row);
    @Update("UPDATE club_wallet_account SET balance=#{balance},frozen=#{frozen},version=version+1 WHERE club_id=#{owner} AND currency=#{currency} AND version=#{version}") int clubBalance(Map<String,Object> row);
    @Update("UPDATE wallet_system_account SET balance=#{balance},version=version+1 WHERE currency=#{currency} AND version=#{version}") int systemBalance(Map<String,Object> row);
    @Insert("INSERT INTO wallet_ledger(transaction_id,owner_type,owner_id,currency,available_delta,frozen_delta,balance_after,frozen_after,account_version,created_at) VALUES(#{transaction},#{type},#{owner},#{currency},#{availableDelta},#{frozenDelta},#{balance},#{frozen},#{accountVersion},#{now})") int entry(Map<String,Object> row);
    @Select("SELECT balance,frozen,version FROM wallet_account WHERE uid=#{owner} AND currency=#{currency}") Map<String,Object> personalView(@Param("owner") long owner,@Param("currency") String currency);
    @Select("SELECT balance,frozen,version FROM club_wallet_account WHERE club_id=#{owner} AND currency=#{currency}") Map<String,Object> clubView(@Param("owner") long owner,@Param("currency") String currency);
    @Select("SELECT balance,0 AS frozen,version FROM wallet_system_account WHERE currency=#{currency}") Map<String,Object> systemView(String currency);
    @Select("SELECT balance,frozen FROM wallet_opening_balance WHERE owner_type=#{type} AND owner_id=#{owner} AND currency=#{currency}") Map<String,Object> opening(@Param("type") String type,@Param("owner") long owner,@Param("currency") String currency);
    @Select("SELECT COALESCE(SUM(available_delta),0) AS availableDelta,COALESCE(SUM(frozen_delta),0) AS frozenDelta,COUNT(*) AS entries,COALESCE(MAX(id),0) AS throughId FROM wallet_ledger WHERE owner_type=#{type} AND owner_id=#{owner} AND currency=#{currency}")
    @Options(timeout=5) Map<String,Object> totals(@Param("type") String type,@Param("owner") long owner,@Param("currency") String currency);
}
