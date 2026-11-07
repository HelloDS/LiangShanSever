package com.ruoyi.game.economy;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.lobby.PlayerService;

@Service
public class PersonalEconomyService {
    private final WalletPostingService wallets;
    private final PlayerService players;
    private final PlayerOperations operations;

    public PersonalEconomyService(WalletPostingService wallets, PlayerService players,PlayerOperations operations) {
        this.wallets=wallets;
        this.players=players;
        this.operations=operations;
    }

    public Map<String,Object> rate() {
        return Map.of("state",0,"exchange_ratio",0.1,"cards_per_diamond",ConfirmedBusinessRules.CARDS_PER_DIAMOND,
                "exchange_diamond_enabled",true);
    }

    /** 旧客户端 number 是收到的房卡数量，operationId 跨连接重试保持一致。 */
    @Transactional(timeout=5)
    public Map<String,Object> exchangeCards(long uid,long cards,String operationId) {
        if(cards<ConfirmedBusinessRules.CARDS_PER_DIAMOND
                ||cards>WalletPosting.MAX_CLIENT_INTEGER
                ||cards%ConfirmedBusinessRules.CARDS_PER_DIAMOND!=0) {
            throw new GameFailure(-4,"房卡数量须为 10 的正整数倍");
        }
        if(operationId==null || !operationId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new GameFailure(-4,"缺少兑换操作编号");
        }
        long diamonds=cards/ConfirmedBusinessRules.CARDS_PER_DIAMOND;
        var result=operations.execute(uid,operationId,Map.of("action","role/exchangeCard","output",cards),()->{
            var receipt=wallets.post(DiamondCardExchangePlan.prepare(uid,diamonds,operationId));
            return Map.of("state",0,"transactionId",receipt.transactionId());
        });
        var role=players.ownRole(uid);
        return Map.of("state",0,"roleCard",role.get("card"),"roleDiamond",role.get("diamond"),
                "operationId",operationId,"transactionId",result.get("transactionId"));
    }

    @Transactional(timeout=5)
    public Map<String,Object> exchangeDiamonds(long uid,long diamonds,String operationId) {
        long cards;
        try {
            cards=DiamondCardExchangePlan.cards(diamonds);
        } catch (IllegalArgumentException invalid) {
            throw new GameFailure(-4,"钻石数量无效");
        }
        var result=operations.execute(uid,operationId,Map.of("action","role/exchangeDiamond","output",diamonds),()->{
            var receipt=wallets.post(new WalletPosting(WalletPosting.Kind.EXCHANGE,"exchange:personal:"+uid+":"+operationId,
                    ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION,"exchange-order:"+operationId,List.of(
                    new WalletPosting.Entry(new WalletPosting.Account(WalletPosting.OwnerType.PERSONAL,uid,WalletPosting.Currency.CARD),-cards,0),
                    new WalletPosting.Entry(new WalletPosting.Account(WalletPosting.OwnerType.SYSTEM,0,WalletPosting.Currency.CARD),cards,0),
                    new WalletPosting.Entry(new WalletPosting.Account(WalletPosting.OwnerType.PERSONAL,uid,WalletPosting.Currency.DIAMOND),diamonds,0),
                    new WalletPosting.Entry(new WalletPosting.Account(WalletPosting.OwnerType.SYSTEM,0,WalletPosting.Currency.DIAMOND),-diamonds,0))));
            return Map.of("state",0,"transactionId",receipt.transactionId());
        });
        var role=players.ownRole(uid);
        return Map.of("state",0,"roleCard",role.get("card"),"roleDiamond",role.get("diamond"),
                "operationId",operationId,"transactionId",result.get("transactionId"));
    }
}
