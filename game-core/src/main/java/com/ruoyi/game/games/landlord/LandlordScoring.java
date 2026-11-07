package com.ruoyi.game.games.landlord;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.ruoyi.game.economy.WalletPosting;

/** 用户允许先定的本地积分规则；不是房卡/钻石扣费，也不会自行宣称已结算入库。 */
public final class LandlordScoring {
    public record Policy(String version,long base,long unitCap){
        public Policy{
            if(version==null||!version.matches("[A-Za-z0-9_.-]{1,32}")||base<1||base>1_000_000||unitCap<1||unitCap>WalletPosting.MAX_CLIENT_INTEGER/2)throw new IllegalArgumentException("斗地主计分配置无效");
        }
        /** 参数本身参与版本摘要，改配置不能仍冒充原来的结算规则。 */
        public String ruleVersion(){
            try{return version+"-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((base+":"+unitCap+":bid-bomb-rocket-spring-v1").getBytes(StandardCharsets.UTF_8))).substring(0,12);}
            catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    public record Result(String ruleVersion,long base,int winningBid,long multiplier,boolean spring,long unitScore,boolean capped,Map<Long,Long> deltas){
        public Result{deltas=Map.copyOf(deltas);}
    }
    private final Policy policy;
    public LandlordScoring(Policy policy){this.policy=Objects.requireNonNull(policy);}
    public Result score(LandlordPlayState state,int winningBid){
        Objects.requireNonNull(state);if(!state.finished())throw new IllegalArgumentException("仅可计算真实结束的牌局");
        if(winningBid<1||winningBid>3)throw new IllegalArgumentException("缺少实际确定地主的叫分");
        boolean landlordWon=state.winner()==state.landlord();
        boolean spring=landlordWon?state.players().stream().filter(uid->uid!=state.landlord()).allMatch(uid->state.plays().get(uid)==0):state.plays().get(state.landlord())==1;
        int doubles=state.bombs()+state.rockets()+(spring?1:0);long multiplier=1L<<doubles;
        long raw=Math.multiplyExact(Math.multiplyExact(policy.base(),winningBid),multiplier),unit=Math.min(raw,policy.unitCap());
        long landlordDelta=Math.multiplyExact(unit,landlordWon?2:-2),farmerDelta=landlordWon?-unit:unit;
        Map<Long,Long> deltas=new LinkedHashMap<>();for(long uid:state.players())deltas.put(uid,uid==state.landlord()?landlordDelta:farmerDelta);
        if(deltas.values().stream().mapToLong(Long::longValue).sum()!=0)throw new IllegalStateException("积分必须保持零和");
        return new Result(policy.ruleVersion(),policy.base(),winningBid,multiplier,spring,unit,unit<raw,deltas);
    }
}
