package com.ruoyi.game.room;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.*;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 已关闭牌桌的只读录像，由已提交事件投影，不重新执行牌局或写入资产。 */
@Service
public class LiveRoomMovies {
    private final LiveRoomMapper rooms;
    private final ClubMapper clubs;
    private final PartnerMapper partners;
    private final ClubRecordMapper records;
    private final GameJsonCodec codec;
    public LiveRoomMovies(LiveRoomMapper rooms,ClubMapper clubs,PartnerMapper partners,ClubRecordMapper records,GameJsonCodec codec) {
        this.rooms=rooms;this.clubs=clubs;this.partners=partners;this.records=records;this.codec=codec;
    }
    @Transactional(readOnly=true,timeout=5)
    public Map<String,Object> movie(long actor,String id) {
        if(id==null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new GameFailure(-4,"录像编号无效");
        var row=rooms.byId(id);
        if(row==null || !LiveRoomEngine.VERSION.equals(row.get("engine")) || !"CLOSED".equals(row.get("status")))throw new GameFailure(-19,"录像尚未生成或不存在");
        var finalState=codec.value((String)row.get("snapshot"),LiveRoomState.class);
        boolean allowed=finalState.seats().roles().containsKey(actor);
        Long club=finalState.details().club();
        if(!allowed && club!=null) {
            String role=clubs.memberRole(club,actor);
            allowed=Set.of("OWNER","MANAGER").contains(Objects.toString(role,""));
            Long record=records.roomRecord(id);
            if(!allowed && role!=null && partners.exists(club,actor) && record!=null)allowed=records.containsPartner(record,actor);
        }
        if(!allowed)throw new GameFailure(-90,"没有此录像的读取权限");
        int completed=finalState.round().number()-(finalState.round().number()>0 && (finalState.round().play()==null || !finalState.round().play().finished())?1:0);
        if(completed==0)throw new GameFailure(-19,"此房间没有完成的牌局");
        var events=rooms.movieEvents(id,completed);
        if(events.size()>2048)throw new GameFailure(-18,"录像超过当前读取范围");
        var rounds=new ArrayList<Map<String,Object>>();
        Map<String,Object> current=null;
        for(var event:events) {
            var payload=codec.read((String)event.get("payload"));
            var state=codec.value(codec.write(payload.get("state")),LiveRoomState.class);
            String tag=payload.path("tag").asString();long sender=payload.path("sender").asLong();
            if("startGame".equals(tag)) {
                current=new LinkedHashMap<>();
                var cards=new LinkedHashMap<String,Object>();
                var players=state.seats().ordered();
                for(int seat=0;seat<3;seat++)cards.put(Long.toString(players.get(seat)),state.round().deal().hands().get(seat).stream().map(card->card.wire()).toList());
                current.put("cards",cards);current.put("opreate",new ArrayList<Map<String,Object>>());rounds.add(current);
            }
            if(current==null)throw new IllegalStateException("录像缺少已提交发牌");
            @SuppressWarnings("unchecked") var actions=(List<Map<String,Object>>)current.get("opreate");
            if("score".equals(tag)) {
                var action=new LinkedHashMap<String,Object>();action.put("type",1);action.put("uid",sender);action.put("score",state.round().bids());
                if(state.round().play()!=null && sender==state.round().play().landlord())action.put("dizhu",state.round().deal().bottom().stream().map(card->card.wire()).toList());
                actions.add(action);
                if(state.round().play()!=null && sender!=state.round().play().landlord())actions.add(Map.of("type",1,"uid",state.round().play().landlord(),"score",state.round().bids(),"dizhu",state.round().deal().bottom().stream().map(card->card.wire()).toList()));
            }
            if("chupai".equals(tag)) {
                var play=state.round().play();
                actions.add(Map.of("type",2,"uid",sender,"cards",play.target().cards().stream().map(card->card.wire()).toList(),"cardType",play.target().pattern().type().wire()));
                if(play.finished())current.put("game",LiveRoomProjection.game(state,state.seats().ordered().getFirst()));
            }
            if("buchu".equals(tag))actions.add(Map.of("type",3,"uid",sender));
        }
        if(rounds.size()!=completed || rounds.stream().anyMatch(round->!round.containsKey("game")))throw new IllegalStateException("录像终局事件不完整");
        return Map.of("state",0,"movie",Map.of("room",LiveRoomProjection.room(finalState),"data",rounds,"roles",finalState.seats().roles()));
    }
}
