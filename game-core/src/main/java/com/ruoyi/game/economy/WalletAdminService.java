package com.ruoyi.game.economy;

import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import static com.ruoyi.game.economy.WalletPosting.*;
import com.ruoyi.game.economy.WalletPosting.Currency;

@Service
public class WalletAdminService {
    private final WalletMapper balances;
    private final WalletAdminMapper mapper;
    private final WalletPostingService posting;
    private final GameAdminService permissions;
    private final AdminOperations operations;
    private final GameJsonCodec codec;
    private final Clock clock;
    private final Semaphore checks=new Semaphore(2);
    public WalletAdminService(WalletMapper balances,WalletAdminMapper mapper,WalletPostingService posting,GameAdminService permissions,AdminOperations operations,GameJsonCodec codec,Clock clock){this.balances=balances;this.mapper=mapper;this.posting=posting;this.permissions=permissions;this.operations=operations;this.codec=codec;this.clock=clock;}
    public Account account(String type,long owner,String currency){
        OwnerType ownerType;Currency denomination;
        try{ownerType=OwnerType.valueOf(type);denomination=switch(currency){case "card"->Currency.CARD;case "diamond"->Currency.DIAMOND;default->throw new IllegalArgumentException();};}catch(RuntimeException e){throw new IllegalArgumentException("账户类型或币种无效");}
        if(ownerType==OwnerType.SYSTEM)throw new IllegalArgumentException("清算账户不提供玩家账本入口");return new Account(ownerType,owner,denomination);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> ledger(long actor,Account account,LocalDate start,LocalDate end,int page,int limit){
        authorize(actor,account);
        if(start==null||end==null||end.isBefore(start)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>30)throw new IllegalArgumentException("请选择最多 31 天的查询范围");
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        var row=account.type()==OwnerType.PERSONAL?balances.personalView(account.ownerId(),account.currency().code()):balances.clubView(account.ownerId(),account.currency().code());
        if(row==null)throw new IllegalArgumentException("资金账户不存在");
        LocalDateTime from=utc(start),until=utc(end.plusDays(1));
        return Map.of("code",200,"account",Map.of("available",row.get("balance").toString(),"frozen",row.get("frozen").toString(),"version",row.get("version").toString()),
                "rows",mapper.ledger(account.type().name(),account.ownerId(),account.currency().code(),from,until,(page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),
                "total",mapper.count(account.type().name(),account.ownerId(),account.currency().code(),from,until),"asOf",clock.instant().toString());
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> reconcile(long actor,Account account,String reason,String key){
        authorize(actor,account);if(!checks.tryAcquire())throw new GameDataUnavailable("余额核对正在处理，请稍后重试");
        try{
            String target=account.type().name()+":"+account.ownerId()+":"+account.currency().code();
            return operations.execute(actor,"wallet:reconcile",target,reason,key,Map.of(),()->{
                var report=posting.reconcile(account);String id=UUID.randomUUID().toString();String status=(String)report.get("status");
                mapper.reconcile(Map.of("id",id,"type",account.type().name(),"owner",account.ownerId(),"currency",account.currency().code(),"status",status,"result",codec.write(report),"actor",actor,"now",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
                return new AdminOperations.Change(Map.of(),Map.of("reportId",id,"status",status),Map.of("reportId",id,"report",report));
            });
        }finally{checks.release();}
    }
    private void authorize(long actor,Account account){if(account.type()==OwnerType.PERSONAL)permissions.requirePlatform(actor);else permissions.requireClub(actor,account.ownerId());}
    private static LocalDateTime utc(LocalDate date){return LocalDateTime.ofInstant(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC);}
}
