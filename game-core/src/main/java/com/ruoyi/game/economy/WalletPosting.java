package com.ruoyi.game.economy;

import java.util.*;

/** 内部账本命令；不直接作为旧客户端请求 DTO，规则和授权由业务服务负责。 */
public record WalletPosting(Kind kind,String businessKey,String ruleVersion,String reference,List<Entry> entries) {
    public static final long MAX_CLIENT_INTEGER=9_007_199_254_740_991L;
    public enum OwnerType { PERSONAL,CLUB,SYSTEM }
    public enum Currency {
        CARD("card"),DIAMOND("diamond");
        private final String code;
        Currency(String code){this.code=code;}
        public String code(){return code;}
    }
    public enum Kind { TRANSFER,FREEZE,RELEASE,CONSUMPTION,EXCHANGE,COMPENSATION,REWARD }
    public record Account(OwnerType type,long ownerId,Currency currency) implements Comparable<Account> {
        public Account{
            Objects.requireNonNull(type);Objects.requireNonNull(currency);
            if(type==OwnerType.SYSTEM?ownerId!=0:ownerId<1||ownerId>999999999)throw new IllegalArgumentException("账户主体无效");
        }
        @Override public int compareTo(Account other){int compared=type.compareTo(other.type);if(compared==0)compared=Long.compare(ownerId,other.ownerId);return compared==0?currency.compareTo(other.currency):compared;}
    }
    public record Entry(Account account,long availableDelta,long frozenDelta){
        public Entry{Objects.requireNonNull(account);if(availableDelta < -MAX_CLIENT_INTEGER||availableDelta>MAX_CLIENT_INTEGER||frozenDelta < -MAX_CLIENT_INTEGER||frozenDelta>MAX_CLIENT_INTEGER)throw new IllegalArgumentException("资产变动超出整数范围");}
    }
    public WalletPosting{
        Objects.requireNonNull(kind);
        if(businessKey==null||businessKey.length()>160||!businessKey.matches("[a-z][a-z0-9_]*(?::[A-Za-z0-9_.-]+){1,6}"))throw new IllegalArgumentException("缺少稳定的业务防重键");
        if(ruleVersion==null||!ruleVersion.matches("[A-Za-z0-9_.:-]{1,64}"))throw new IllegalArgumentException("缺少规则版本");
        if(reference==null||!reference.matches("[A-Za-z0-9_.:/-]{1,160}"))throw new IllegalArgumentException("缺少来源依据");
        if(entries==null||entries.isEmpty()||entries.size()>200)throw new IllegalArgumentException("账本分录数量无效");
        TreeMap<Account,Entry> grouped=new TreeMap<>();
        for(Entry entry:entries)grouped.merge(entry.account(),entry,(left,right)->new Entry(left.account(),Math.addExact(left.availableDelta(),right.availableDelta()),Math.addExact(left.frozenDelta(),right.frozenDelta())));
        entries=grouped.values().stream().filter(e->e.availableDelta()!=0||e.frozenDelta()!=0).toList();
        if(entries.isEmpty())throw new IllegalArgumentException("账本命令没有实际变动");
        EnumMap<Currency,Long> totals=new EnumMap<>(Currency.class);
        for(Entry entry:entries){
            totals.merge(entry.account().currency(),Math.addExact(entry.availableDelta(),entry.frozenDelta()),Math::addExact);
            if(entry.account().type()==OwnerType.SYSTEM&&(entry.frozenDelta()!=0||Set.of(Kind.TRANSFER,Kind.FREEZE,Kind.RELEASE).contains(kind)))throw new IllegalArgumentException("此操作不能使用系统清算账户");
        }
        if(totals.values().stream().anyMatch(total->total!=0))throw new IllegalArgumentException("每种币种必须分别借贷平衡");
        if(kind==Kind.TRANSFER&&(entries.size()!=2||entries.getFirst().account().currency()!=entries.getLast().account().currency()||entries.stream().anyMatch(e->e.frozenDelta()!=0)))throw new IllegalArgumentException("划转必须是同币种双边可用资产变动");
        if(kind==Kind.FREEZE||kind==Kind.RELEASE){
            if(entries.size()!=1)throw new IllegalArgumentException("冻结操作必须作用于单一账户");
            Entry entry=entries.getFirst();
            if(kind==Kind.FREEZE?entry.availableDelta()>=0||entry.frozenDelta()<=0:entry.availableDelta()<=0||entry.frozenDelta()>=0)throw new IllegalArgumentException("冻结变动方向无效");
        }
    }
}
