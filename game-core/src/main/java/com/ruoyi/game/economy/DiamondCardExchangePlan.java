package com.ruoyi.game.economy;

import java.util.*;
import static com.ruoyi.game.economy.WalletPosting.*;
import com.ruoyi.game.economy.WalletPosting.Currency;

/** 精确整数兑换计划；持久订单与已认证 UID 由业务适配器提供，不自行生成重试键。 */
public final class DiamondCardExchangePlan {
    private DiamondCardExchangePlan(){ }
    public static long cards(long diamonds){
        if(diamonds<1||diamonds>MAX_CLIENT_INTEGER/ConfirmedBusinessRules.CARDS_PER_DIAMOND)throw new IllegalArgumentException("兑换数量须为可精确表示的正整数");
        return Math.multiplyExact(diamonds,ConfirmedBusinessRules.CARDS_PER_DIAMOND);
    }
    public static WalletPosting prepare(long authenticatedUid,long diamonds,String persistedOrderId){
        if(persistedOrderId==null||!persistedOrderId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("缺少持久兑换订单编号");
        long cards=cards(diamonds);
        return new WalletPosting(Kind.EXCHANGE,"exchange:personal:"+authenticatedUid+":"+persistedOrderId,ConfirmedBusinessRules.VERSION,"exchange-order:"+persistedOrderId,List.of(
            new Entry(new Account(OwnerType.PERSONAL,authenticatedUid,Currency.DIAMOND),-diamonds,0),
            new Entry(new Account(OwnerType.SYSTEM,0,Currency.DIAMOND),diamonds,0),
            new Entry(new Account(OwnerType.PERSONAL,authenticatedUid,Currency.CARD),cards,0),
            new Entry(new Account(OwnerType.SYSTEM,0,Currency.CARD),-cards,0)));
    }
}
