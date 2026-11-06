package com.ruoyi.game.economy;

import java.time.*;
import java.util.*;
import com.ruoyi.game.persistence.mapper.WalletReconciliationMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.ruoyi.game.economy.WalletPosting.*;
import com.ruoyi.game.economy.WalletPosting.Currency;

/** 当前一致性核对，不是历史日结。只写核对事实、游标与租约，绝不修正资产。 */
@Service
public class WalletReconciliationJobs {
    public static final String ALGORITHM="opening-ledger-v1";
    public record Lease(String id,String token){
        public Lease{uuid(id);uuid(token);}
    }
    public enum Step { PROGRESSED,DONE,LOST }
    private final WalletReconciliationMapper mapper;
    private final WalletPostingService wallets;
    private final GameJsonCodec codec;
    private final Clock clock;
    public WalletReconciliationJobs(WalletReconciliationMapper mapper,WalletPostingService wallets,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.wallets=wallets;this.codec=codec;this.clock=clock;}
    public LocalDate today(){return LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));}
    @Transactional(timeout=3)
    public Map<String,Object> ensureToday(String origin,Long actor){
        if(!("SYSTEM".equals(origin)&&actor==null||"ADMIN".equals(origin)&&actor!=null&&actor>0))throw new IllegalArgumentException("核对任务来源无效");
        LocalDate date=today();var existing=mapper.byDate(date);if(existing!=null)return existing;
        mapper.begin(UUID.randomUUID().toString(),date,mapper.personalHighwater(),mapper.clubHighwater(),origin,actor,now());
        return Objects.requireNonNull(mapper.lockDate(date));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=3)
    public Lease claim(){
        LocalDateTime now=now();var row=mapper.claimable(now);if(row==null)return null;
        String id=(String)row.get("id");
        if(number(row,"attempts")>=5){if(mapper.expire(id)!=1)throw new IllegalStateException("核对任务到期状态冲突");return null;}
        String token=UUID.randomUUID().toString();if(mapper.claim(id,token,now,now.plusSeconds(30))!=1)throw new IllegalStateException("核对任务领取冲突");return new Lease(id,token);
    }
    /** 一次最多检查一个账户；运行行、结果和游标同事务，资产/账本使用同一 RR 一致性读。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Step process(Lease lease){
        var row=mapper.lock(lease.id());LocalDateTime now=now();
        if(!owned(row,lease)||!date(row.get("leaseUntil")).isAfter(now))return Step.LOST;
        if(!ALGORITHM.equals(row.get("algorithmVersion")))throw new IllegalStateException("不支持此核对算法版本");
        int kind=Math.toIntExact(number(row,"cursorKind"));long owner=number(row,"cursorOwner");String currency=(String)row.get("cursorCurrency");
        Map<String,Object> next=null;
        while(kind<3){
            next=switch(kind){case 0->mapper.nextPersonal(owner,currency,number(row,"personalHighwater"));case 1->mapper.nextClub(owner,currency,number(row,"clubHighwater"));case 2->mapper.nextSystem(currency);default->throw new IllegalStateException("核对游标类型无效");};
            if(next!=null)break;kind++;owner=0;currency="";
        }
        if(next==null){if(mapper.finish(lease.id(),lease.token(),now)!=1)throw new IllegalStateException("核对任务完成冲突");return Step.DONE;}
        owner=number(next,"owner");currency=(String)next.get("currency");
        OwnerType type=switch(kind){case 0->OwnerType.PERSONAL;case 1->OwnerType.CLUB;case 2->OwnerType.SYSTEM;default->throw new IllegalStateException("核对账户类型无效");};
        Currency denomination=switch(currency){case "card"->Currency.CARD;case "diamond"->Currency.DIAMOND;default->throw new IllegalStateException("核对币种无效");};
        var result=wallets.reconcile(new Account(type,owner,denomination));String status=(String)result.get("status");
        if(!Set.of("MATCH","MISMATCH").contains(status))throw new IllegalStateException("核对结果状态无效");
        if(mapper.detail(lease.id(),type.name(),owner,currency,status,codec.write(result),now)!=1)throw new IllegalStateException("核对结果写入冲突");
        int matched="MATCH".equals(status)?1:0;
        if(mapper.advance(lease.id(),lease.token(),kind,owner,currency,matched,1-matched,now,now.plusSeconds(30))!=1)throw new IllegalStateException("核对游标推进冲突");
        return Step.PROGRESSED;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public boolean failed(Lease lease,String code){
        if(code==null||!code.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("核对错误代码无效");
        var row=mapper.lock(lease.id());if(!owned(row,lease))return false;
        int attempts=Math.max(1,Math.toIntExact(number(row,"attempts")));long delay=Math.min(60,1L<<Math.min(attempts-1,6));
        return mapper.failed(lease.id(),lease.token(),attempts>=5?"FAILED":"READY",attempts,now().plusSeconds(delay),code)==1;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public boolean release(Lease lease){return mapper.release(lease.id(),lease.token(),now())==1;}
    @Transactional(timeout=3)
    public Map<String,Object> retry(String id){
        uuid(id);var row=mapper.lock(id);
        if(row==null||!"FAILED".equals(row.get("status")))throw new IllegalArgumentException("仅失败的未完成核对允许重试");
        if(mapper.retry(id,now())!=1)throw new IllegalStateException("核对重试状态冲突");return mapper.metadata(id);
    }
    private static boolean owned(Map<String,Object> row,Lease lease){return row!=null&&"RUNNING".equals(row.get("status"))&&lease.token().equals(row.get("leaseToken"));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static LocalDateTime date(Object value){return value instanceof LocalDateTime time?time:((java.sql.Timestamp)value).toLocalDateTime();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public static void uuid(String id){if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("核对任务编号无效");}
}
