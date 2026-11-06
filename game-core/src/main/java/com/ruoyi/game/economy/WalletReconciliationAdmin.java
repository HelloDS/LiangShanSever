package com.ruoyi.game.economy;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.WalletReconciliationMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletReconciliationAdmin {
    private final WalletReconciliationMapper mapper;
    private final WalletReconciliationJobs jobs;
    private final GameAdminService permissions;
    private final AdminOperations operations;
    private final Clock clock;
    private final boolean processingEnabled;
    public WalletReconciliationAdmin(WalletReconciliationMapper mapper,WalletReconciliationJobs jobs,GameAdminService permissions,AdminOperations operations,Clock clock,@Value("${game.reconciliation.worker-enabled:true}") boolean processingEnabled){this.mapper=mapper;this.jobs=jobs;this.permissions=permissions;this.operations=operations;this.clock=clock;this.processingEnabled=processingEnabled;}
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> list(long actor,LocalDate start,LocalDate end,String status,int page,int limit){
        dates(start,end);page(page,limit);
        if(status!=null&&!Set.of("READY","RUNNING","DONE","DONE_WITH_ISSUES","FAILED").contains(status))throw new IllegalArgumentException("核对状态无效");
        try{permissions.requirePlatform(actor);return Map.of("code",200,"rows",mapper.list(start,end,status,(page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),"total",mapper.count(start,end,status),"asOf",clock.instant().toString(),"processingEnabled",processingEnabled);}
        catch(DataAccessException failure){throw new GameDataUnavailable("账本核对查询暂不可用");}
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> detail(long actor,String id,String status,int page,int limit){
        WalletReconciliationJobs.uuid(id);page(page,limit);
        if(status!=null&&!Set.of("MATCH","MISMATCH").contains(status))throw new IllegalArgumentException("核对明细状态无效");
        try{
            permissions.requirePlatform(actor);
            var run=mapper.metadata(id);if(run==null)throw new IllegalArgumentException("核对记录不存在");
            return Map.of("run",AdminTimes.utc(run),"rows",mapper.details(id,status,(page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),"total",mapper.detailsCount(id,status),"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("账本核对明细暂不可用");}
    }
    @Transactional(timeout=5)
    public Map<String,Object> trigger(long actor,String reason,String key){
        try{permissions.requirePlatform(actor);return operations.execute(actor,"wallet:daily:run",jobs.today().toString(),reason,key,Map.of(),()->{
            var run=jobs.ensureToday("ADMIN",actor);String id=(String)run.get("id");
            return new AdminOperations.Change(Map.of(),Map.of("runId",id),Map.of("run",AdminTimes.utc(run)));
        });}catch(DataAccessException failure){throw new GameDataUnavailable("账本核对任务暂不可创建");}
    }
    @Transactional(timeout=5)
    public Map<String,Object> retry(long actor,String id,String reason,String key){
        WalletReconciliationJobs.uuid(id);
        try{permissions.requirePlatform(actor);return operations.execute(actor,"wallet:daily:retry",id,reason,key,Map.of(),()->{
            var before=mapper.metadata(id);if(before==null)throw new IllegalArgumentException("核对记录不存在");
            var run=jobs.retry(id);
            return new AdminOperations.Change(Map.of("status",before.get("status")),Map.of("status",run.get("status")),Map.of("run",AdminTimes.utc(run)));
        });}catch(DataAccessException failure){throw new GameDataUnavailable("账本核对任务暂不可重试");}
    }
    private static void dates(LocalDate start,LocalDate end){
        if(start==null||end==null||end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>30||start.getYear()<1000||end.getYear()>9999)throw new IllegalArgumentException("请选择最多 31 天的有效日期范围");
    }
    private static void page(int page,int limit){if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");}
}
