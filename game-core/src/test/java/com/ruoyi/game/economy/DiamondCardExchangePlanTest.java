package com.ruoyi.game.economy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiamondCardExchangePlanTest {
    @Test void confirmedDirectionUsesExactIntegersAtNormalAndSafeBoundaries(){
        assertEquals(10,DiamondCardExchangePlan.cards(1));assertEquals(20,DiamondCardExchangePlan.cards(2));assertEquals(1000,DiamondCardExchangePlan.cards(100));
        long maximum=WalletPosting.MAX_CLIENT_INTEGER/10;assertEquals(maximum*10,DiamondCardExchangePlan.cards(maximum));
        for(long invalid:new long[]{0,-1,maximum+1,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->DiamondCardExchangePlan.cards(invalid));
    }
    @Test void planHasStableOrderIdentityAndSeparatelyBalancedCurrencyEntries(){
        String order=UUID.randomUUID().toString();var plan=DiamondCardExchangePlan.prepare(100001,3,order);
        assertEquals(plan,DiamondCardExchangePlan.prepare(100001,3,order));assertEquals(WalletPosting.Kind.EXCHANGE,plan.kind());assertEquals(ConfirmedBusinessRules.VERSION,plan.ruleVersion());assertTrue(plan.businessKey().endsWith(order));
        for(var currency:WalletPosting.Currency.values())assertEquals(0,plan.entries().stream().filter(entry->entry.account().currency()==currency).mapToLong(entry->entry.availableDelta()+entry.frozenDelta()).sum());
        assertThrows(IllegalArgumentException.class,()->DiamondCardExchangePlan.prepare(100001,1,"temporary-counter"));assertThrows(IllegalArgumentException.class,()->DiamondCardExchangePlan.prepare(0,1,order));
    }
}
