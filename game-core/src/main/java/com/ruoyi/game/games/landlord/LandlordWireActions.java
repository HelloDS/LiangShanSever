package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import tools.jackson.databind.JsonNode;

/** 6003 原 room/gameMessage 的平铺 tag 请求适配；不会注册或开放网络路由。 */
public final class LandlordWireActions {
    private LandlordWireActions(){ }
    public static LandlordAction parse(JsonNode data){
        if(data==null||!data.isObject())throw new IllegalArgumentException("动作数据必须为对象");
        return switch(GameJsonCodec.text(data,"tag",24)){
            case "ready"->new LandlordAction.Ready();
            case "score"->new LandlordAction.Bid(integer(data,"score",0,3));
            case "jiabei"->new LandlordAction.DoubleChoice(integer(data,"jiabei",0,1)==1);
            case "buchu"->new LandlordAction.Pass();
            case "auto"->new LandlordAction.ToggleAuto();
            case "chupai"->{
                List<LandlordCard> cards=cards(data.get("outCards"));int claimed=integer(data,"opCardType",1,13);
                var pattern=LandlordPatterns.classifyAs(cards,claimed).orElseThrow(()->new IllegalArgumentException("出牌与声明牌型不一致"));
                yield new LandlordAction.Play(cards,pattern);
            }
            default->throw new IllegalArgumentException("6003 不支持此动作");
        };
    }
    private static int integer(JsonNode data,String key,int min,int max){
        var value=data.get(key);
        if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw new IllegalArgumentException("动作整数字段无效");
        return value.asInt();
    }
    private static List<LandlordCard> cards(JsonNode values){
        if(values==null||!values.isArray()||values.isEmpty()||values.size()>20)throw new IllegalArgumentException("出牌列表无效");
        List<LandlordCard> cards=new ArrayList<>();
        for(var value:values){
            if(!value.isArray()||value.size()!=2||!value.get(0).isIntegralNumber()||!value.get(1).isIntegralNumber()
                    ||!value.get(0).canConvertToInt()||!value.get(1).canConvertToInt())throw new IllegalArgumentException("牌必须为花色与点数的整数二元组");
            cards.add(new LandlordCard(value.get(0).asInt(),value.get(1).asInt()));
        }
        return List.copyOf(cards);
    }
}
