package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.room.*;

/** 未注册的出牌日志适配器。规则须由调用方冻结；没有临时价格、积分或收费默认值。 */
public final class LandlordPlayPlans {
    private final LandlordPlayCodec codec;
    private final String ruleVersion;
    private final LandlordPlayEngine engine=new LandlordPlayEngine();
    public LandlordPlayPlans(LandlordPlayCodec codec,String ruleVersion){
        this.codec=Objects.requireNonNull(codec);
        if(ruleVersion==null||!ruleVersion.matches("[A-Za-z0-9_.:-]{1,64}"))throw new IllegalArgumentException("缺少冻结规则版本");this.ruleVersion=ruleVersion;
    }
    public RoomJournalCommand command(RoomPersistence.Write<LandlordPlayState,LandlordPlayCommand,LandlordPlayEvent> write,GuestIdentity actor){
        if(actor==null||actor.uid()!=write.command().uid())throw new IllegalArgumentException("持久化身份与房间命令不一致");
        var expected=engine.apply(write.previous(),write.command());
        if(!expected.state().equals(write.candidate().state())||!expected.event().equals(write.event())||expected.round()!=write.candidate().round()||!expected.phase().equals(write.candidate().phase()))
            throw new IllegalArgumentException("出牌候选和事件不能由同一动作产生");
        boolean checkpoint="ROUND_FINISHED".equals(write.candidate().phase());
        return new RoomJournalCommand(encoded(write.previous()),encoded(write.candidate()),codec.kind(write.event()),codec.eventJson(write.event()),ruleVersion,1,
                LandlordPlayEngine.ENGINE_VERSION,actor,List.of(),null,checkpoint,write.occurredAt());
    }
    private RoomSnapshot<String> encoded(RoomSnapshot<LandlordPlayState> snapshot){
        return new RoomSnapshot<>(snapshot.roomId(),snapshot.ownerEpoch(),snapshot.version(),snapshot.round(),snapshot.phase(),codec.json(snapshot.state()));
    }
}
