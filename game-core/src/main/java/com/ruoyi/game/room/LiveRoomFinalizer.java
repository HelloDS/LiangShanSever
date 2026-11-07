package com.ruoyi.game.room;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import com.ruoyi.game.persistence.mapper.ClubRecordMapper;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 与关闭事件共用房间事务。只写已真正结束的局，进行中的一局不计入战绩。 */
@Service
public class LiveRoomFinalizer {
    private final ClubRecordMapper records;
    private final GameJsonCodec codec;
    public LiveRoomFinalizer(ClubRecordMapper records,GameJsonCodec codec){this.records=records;this.codec=codec;}
    public void closing(RoomJournalCommand command,LocalDateTime now) {
        if(!LiveRoomEngine.VERSION.equals(command.engineVersion()) || !"CLOSED".equals(command.candidate().phase()))return;
        var state=codec.value(command.candidate().state(),LiveRoomState.class);
        var round=state.round();
        int completed=round.number()-(round.number()>0 && (round.play()==null || !round.play().finished())?1:0);
        if(state.details().club()==null || completed==0 || records.roomRecord(command.candidate().roomId())!=null)return;
        if(state.totals().values().stream().mapToLong(Long::longValue).sum()!=0)throw new IllegalStateException("正式积分不守恒");
        var roles=new LinkedHashMap<String,Object>();
        for(long uid:state.seats().ordered()) {
            var role=new LinkedHashMap<>(state.seats().roles().get(uid));
            role.put("score",state.totals().get(uid));roles.put(Long.toString(uid),role);
        }
        long initiator=state.vote()!=null?state.vote().initiator():"CLOSE".equals(command.eventKind()) && command.actor()!=null?command.actor().uid():0;
        var source=state.seats().roles().get(initiator);
        var detail=new LinkedHashMap<String,Object>();
        detail.put("game_record",roles);detail.put("movie_id",command.candidate().roomId());
        detail.put("sq_uid",initiator);detail.put("sq_name",source==null?"":source.get("name"));
        detail.put("total_round",completed);detail.put("at_info",Map.of("zhuang",round.play()==null?0:round.play().landlord()));
        var values=new HashMap<String,Object>();
        values.put("room",command.candidate().roomId());values.put("club",state.details().club());
        values.put("code",state.details().code());values.put("rounds",completed);values.put("record",codec.write(detail));values.put("now",now);
        records.insert(values);
        long id=((Number)values.get("id")).longValue();
        for(long uid:state.seats().ordered())records.score(id,uid,state.partners().getOrDefault(uid,state.details().owner()),state.totals().get(uid));
    }
}
