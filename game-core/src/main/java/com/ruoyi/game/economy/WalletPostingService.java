package com.ruoyi.game.economy;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.WalletMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import static com.ruoyi.game.economy.WalletPosting.*;
import com.ruoyi.game.economy.WalletPosting.Currency;

/** 仅内部事务基础设施。不得以本服务生成的 UUID 代替旧请求缺失的重试标识。 */
@Service
public class WalletPostingService {
    private final WalletMapper mapper;
    private final GameJsonCodec codec;
    private final Clock clock;
    public WalletPostingService(WalletMapper mapper,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.codec=codec;this.clock=clock;}
    public record Balance(Account account,long available,long frozen,long version){ }
    public record Receipt(String transactionId,boolean replayed,List<Balance> balances){public Receipt{balances=List.copyOf(balances);}}

    @Transactional(timeout=5)
    public Receipt post(WalletPosting command){return postAllInternal(List.of(command)).getFirst();}

    /** 同一业务事务的多笔资金命令一次传入，避免逐笔锁账户形成跨命令反序。 */
    @Transactional(timeout=5)
    public List<Receipt> postAll(List<WalletPosting> commands){return postAllInternal(commands);}

    private record Prepared(String id,Receipt replay){ }
    private List<Receipt> postAllInternal(List<WalletPosting> commands){
        if(commands==null||commands.isEmpty()||commands.size()>16)throw new IllegalArgumentException("资金批次命令数无效");
        List<WalletPosting> intent=List.copyOf(commands);TreeMap<String,WalletPosting> sorted=new TreeMap<>();
        for(var command:intent)if(sorted.putIfAbsent(command.businessKey(),command)!=null)throw new IllegalArgumentException("资金批次含重复业务键");
        LocalDateTime now=now();Map<String,Prepared> prepared=new HashMap<>();
        // 先统一锁业务键，再锁账户；不能先持有账户再等待其他命令的业务键。
        for(var command:sorted.values()){
            String digest=hash(codec.write(canonical(payload(command)))),proposed=UUID.randomUUID().toString();
            mapper.begin(Map.of("id",proposed,"key",command.businessKey(),"kind",command.kind().name(),"hash",digest,"rule",command.ruleVersion(),"reference",command.reference(),"now",now));
            var transaction=mapper.transaction(command.businessKey());
            if(!digest.equals(transaction.get("requestHash")))throw new IllegalArgumentException("业务键已用于不同的资金命令");
            Receipt replay=null;
            if(!proposed.equals(transaction.get("id"))){
                if(!"POSTED".equals(transaction.get("status")))throw new IllegalStateException("资金命令状态不完整");
                replay=receipt((String)transaction.get("receipt"));
            }
            prepared.put(command.businessKey(),new Prepared(proposed,replay));
        }
        SortedSet<Account> keys=new TreeSet<>();
        for(var command:intent)if(prepared.get(command.businessKey()).replay()==null)for(var entry:command.entries())keys.add(entry.account());
        Map<Account,Map<String,Object>> accounts=new TreeMap<>();
        for(Account key:keys){var row=lock(key);if(row==null)throw new IllegalArgumentException("资金账户不存在");accounts.put(key,row);}
        List<Receipt> results=new ArrayList<>();
        // 只改变锁的顺序，不改变先冻结/后释放等业务效果的输入顺序。
        for(var command:intent){var item=prepared.get(command.businessKey());results.add(item.replay()!=null?item.replay():apply(command,item.id(),now,accounts));}
        return List.copyOf(results);
    }
    private Receipt apply(WalletPosting command,String proposed,LocalDateTime now,Map<Account,Map<String,Object>> accounts){
        List<Balance> balances=new ArrayList<>();
        for(Entry entry:command.entries()){
            Account account=entry.account();var before=accounts.get(account);
            long available=Math.addExact(number(before,"balance"),entry.availableDelta());
            long frozen=Math.addExact(number(before,"frozen"),entry.frozenDelta());
            if(account.type()!=OwnerType.SYSTEM){
                if(available<0||frozen<0)throw insufficient(account);
                if(available>MAX_CLIENT_INTEGER||frozen>MAX_CLIENT_INTEGER||Math.addExact(available,frozen)>MAX_CLIENT_INTEGER)throw new IllegalArgumentException("账户资产超出客户端整数范围");
            }else if(frozen!=0)throw new IllegalArgumentException("系统清算账户不支持冻结");
            long version=number(before,"version");
            Map<String,Object> row=new HashMap<>();row.put("owner",account.ownerId());row.put("currency",account.currency().code());row.put("balance",available);row.put("frozen",frozen);row.put("version",version);
            int changed=switch(account.type()){case PERSONAL->mapper.personalBalance(row);case CLUB->mapper.clubBalance(row);case SYSTEM->mapper.systemBalance(row);};
            if(changed!=1)throw new IllegalStateException("资金账户版本发生变化");
            row.put("transaction",proposed);row.put("type",account.type().name());row.put("availableDelta",entry.availableDelta());row.put("frozenDelta",entry.frozenDelta());row.put("accountVersion",Math.addExact(version,1));row.put("now",now);mapper.entry(row);
            before.put("balance",available);before.put("frozen",frozen);before.put("version",Math.addExact(version,1));
            balances.add(new Balance(account,available,frozen,version+1));
        }
        Receipt result=new Receipt(proposed,false,balances);
        if(mapper.posted(proposed,codec.write(receiptMap(result)),now)!=1)throw new IllegalStateException("资金命令提交状态无效");
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> reconcile(Account account){
        var current=view(account);if(current==null)throw new IllegalArgumentException("资金账户不存在");
        var opening=mapper.opening(account.type().name(),account.ownerId(),account.currency().code());
        var total=mapper.totals(account.type().name(),account.ownerId(),account.currency().code());
        BigDecimal expected=(opening==null?BigDecimal.ZERO:decimal(opening.get("balance"))).add(decimal(total.get("availableDelta")));
        BigDecimal expectedFrozen=(opening==null?BigDecimal.ZERO:decimal(opening.get("frozen"))).add(decimal(total.get("frozenDelta")));
        BigDecimal actual=decimal(current.get("balance")),actualFrozen=decimal(current.get("frozen"));
        return Map.of("status",expected.compareTo(actual)==0&&expectedFrozen.compareTo(actualFrozen)==0?"MATCH":"MISMATCH",
                "expectedAvailable",expected.toPlainString(),"actualAvailable",actual.toPlainString(),"expectedFrozen",expectedFrozen.toPlainString(),"actualFrozen",actualFrozen.toPlainString(),
                "ledgerEntries",total.get("entries"),"throughLedgerId",total.get("throughId").toString(),"accountVersion",current.get("version"),"asOf",clock.instant().toString());
    }
    private Map<String,Object> lock(Account account){return switch(account.type()){case PERSONAL->mapper.personal(account.ownerId(),account.currency().code());case CLUB->mapper.club(account.ownerId(),account.currency().code());case SYSTEM->mapper.system(account.currency().code());};}
    private Map<String,Object> view(Account account){return switch(account.type()){case PERSONAL->mapper.personalView(account.ownerId(),account.currency().code());case CLUB->mapper.clubView(account.ownerId(),account.currency().code());case SYSTEM->mapper.systemView(account.currency().code());};}
    private Map<String,Object> payload(WalletPosting command){
        var entries=command.entries().stream().map(e->Map.of("type",e.account().type().name(),"owner",e.account().ownerId(),"currency",e.account().currency().name(),"available",e.availableDelta(),"frozen",e.frozenDelta())).toList();
        return Map.of("kind",command.kind().name(),"rule",command.ruleVersion(),"reference",command.reference(),"entries",entries);
    }
    private Map<String,Object> receiptMap(Receipt receipt){
        return Map.of("transactionId",receipt.transactionId(),"balances",receipt.balances().stream().map(balance->Map.of("type",balance.account().type().name(),"owner",balance.account().ownerId(),"currency",balance.account().currency().name(),"available",balance.available(),"frozen",balance.frozen(),"version",balance.version())).toList());
    }
    private Receipt receipt(String json){
        var root=codec.read(json);List<Balance> balances=new ArrayList<>();
        for(var row:root.path("balances"))balances.add(new Balance(new Account(OwnerType.valueOf(row.path("type").asString()),row.path("owner").asLong(),Currency.valueOf(row.path("currency").asString())),row.path("available").asLong(),row.path("frozen").asLong(),row.path("version").asLong()));
        return new Receipt(root.path("transactionId").asString(),true,balances);
    }
    private static GameFailure insufficient(Account account){
        int state=account.currency()==Currency.CARD?(account.type()==OwnerType.CLUB?-40:-8):(account.type()==OwnerType.CLUB?-110:-109);
        return new GameFailure(state,"可用资产或冻结额不足");
    }
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((key,item)->sorted.put(key.toString(),canonical(item)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(WalletPostingService::canonical).toList();return value;
    }
    private static BigDecimal decimal(Object number){return number instanceof BigDecimal decimal?decimal:new BigDecimal(number.toString());}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String hash(String json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
