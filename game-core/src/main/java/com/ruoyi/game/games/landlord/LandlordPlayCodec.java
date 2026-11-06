package com.ruoyi.game.games.landlord;

import java.util.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.room.RoomReplay;
import tools.jackson.databind.JsonNode;

/** 私有持久化编码，明确版本和字段白名单，不能当作玩家报文。 */
public final class LandlordPlayCodec implements RoomReplay.Codec<LandlordPlayState,LandlordPlayEvent> {
    private final GameJsonCodec codec;
    public LandlordPlayCodec(GameJsonCodec codec){this.codec=Objects.requireNonNull(codec);}
    @Override public boolean supports(String game,int schema,String engine){return "6003".equals(game)&&schema==1&&LandlordPlayEngine.ENGINE_VERSION.equals(engine);}
    @Override public String json(LandlordPlayState state){
        Map<String,Object> root=new LinkedHashMap<>(),hands=new TreeMap<>(),plays=new TreeMap<>();
        state.hands().forEach((uid,cards)->hands.put(uid.toString(),wire(cards)));
        state.plays().forEach((uid,count)->plays.put(uid.toString(),count));
        root.put("players",state.players());root.put("hands",hands);root.put("bottom",wire(state.bottom()));
        root.put("landlord",state.landlord());root.put("currentUid",state.currentUid());root.put("passes",state.passes());
        root.put("target",state.target()==null?null:Map.of("uid",state.target().uid(),"cards",wire(state.target().cards()),"type",state.target().pattern().type().wire()));
        root.put("plays",plays);root.put("discarded",wire(state.discarded()));root.put("winner",state.winner());
        root.put("auto",state.auto().stream().sorted().toList());root.put("bombs",state.bombs());root.put("rockets",state.rockets());
        return codec.canonicalObject(codec.write(root));
    }
    @Override public LandlordPlayState state(String json){
        var root=codec.read(json);
        exact(root,"players","hands","bottom","landlord","currentUid","passes","target","plays","discarded","winner","auto","bombs","rockets");
        var players=uids(root.get("players"),3);if(players.size()!=3)throw invalid();
        var handRoot=root.get("hands");var playsRoot=root.get("plays");
        exact(handRoot,players.stream().map(Object::toString).toArray(String[]::new));exact(playsRoot,players.stream().map(Object::toString).toArray(String[]::new));
        Map<Long,List<LandlordCard>> hands=new LinkedHashMap<>();Map<Long,Integer> plays=new LinkedHashMap<>();
        for(long uid:players){hands.put(uid,cards(handRoot.get(Long.toString(uid)),20,true));plays.put(uid,integer(playsRoot.get(Long.toString(uid)),0,54));}
        var targetRoot=root.get("target");LandlordPlayState.Target target=null;
        if(!targetRoot.isNull()){
            exact(targetRoot,"uid","cards","type");var cards=cards(targetRoot.get("cards"),20,false);
            int type=integer(targetRoot.get("type"),1,13);
            target=new LandlordPlayState.Target(uid(targetRoot.get("uid")),cards,LandlordPatterns.classifyAs(cards,type).orElseThrow(LandlordPlayCodec::invalid));
        }
        var auto=uids(root.get("auto"),3);if(new HashSet<>(auto).size()!=auto.size())throw invalid();
        return new LandlordPlayState(players,hands,cards(root.get("bottom"),3,false),uid(root.get("landlord")),number(root.get("currentUid"),0,999999999),target,
                integer(root.get("passes"),0,1),plays,cards(root.get("discarded"),54,true),number(root.get("winner"),0,999999999),Set.copyOf(auto),integer(root.get("bombs"),0,13),integer(root.get("rockets"),0,1));
    }
    public String kind(LandlordPlayEvent event){
        if(event instanceof LandlordPlayEvent.Played)return "CARD_PLAYED";
        if(event instanceof LandlordPlayEvent.Passed)return "TURN_PASSED";
        if(event instanceof LandlordPlayEvent.AutoChanged)return "AUTO_CHANGED";
        throw invalid();
    }
    public String eventJson(LandlordPlayEvent event){
        Object root;
        if(event instanceof LandlordPlayEvent.Played played)root=Map.of("uid",played.uid(),"cards",wire(played.cards()),"type",played.type());
        else if(event instanceof LandlordPlayEvent.Passed passed)root=Map.of("uid",passed.uid());
        else if(event instanceof LandlordPlayEvent.AutoChanged auto)root=Map.of("uid",auto.uid(),"enabled",auto.enabled());
        else throw invalid();
        return codec.canonicalObject(codec.write(root));
    }
    @Override public LandlordPlayEvent event(String kind,String json){
        var root=codec.read(json);
        return switch(kind){
            case "CARD_PLAYED"->{exact(root,"uid","cards","type");yield new LandlordPlayEvent.Played(uid(root.get("uid")),cards(root.get("cards"),20,false),integer(root.get("type"),1,13));}
            case "TURN_PASSED"->{exact(root,"uid");yield new LandlordPlayEvent.Passed(uid(root.get("uid")));}
            case "AUTO_CHANGED"->{exact(root,"uid","enabled");if(!root.get("enabled").isBoolean())throw invalid();yield new LandlordPlayEvent.AutoChanged(uid(root.get("uid")),root.get("enabled").asBoolean());}
            default->throw invalid();
        };
    }
    private static List<List<Integer>> wire(List<LandlordCard> cards){return cards.stream().map(LandlordCard::wire).toList();}
    private static void exact(JsonNode node,String... keys){if(node==null||!node.isObject()||node.size()!=keys.length||Arrays.stream(keys).anyMatch(key->!node.has(key)))throw invalid();}
    private static long number(JsonNode node,long min,long max){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<min||node.asLong()>max)throw invalid();return node.asLong();}
    private static int integer(JsonNode node,int min,int max){return Math.toIntExact(number(node,min,max));}
    private static long uid(JsonNode node){return number(node,1,999999999);}
    private static List<Long> uids(JsonNode node,int max){
        if(node==null||!node.isArray()||node.size()>max)throw invalid();List<Long> result=new ArrayList<>();for(var value:node)result.add(uid(value));return List.copyOf(result);
    }
    private static List<LandlordCard> cards(JsonNode node,int max,boolean empty){
        if(node==null||!node.isArray()||node.size()>max||!empty&&node.isEmpty())throw invalid();List<LandlordCard> result=new ArrayList<>();
        for(var value:node){if(!value.isArray()||value.size()!=2)throw invalid();result.add(new LandlordCard(integer(value.get(0),0,4),integer(value.get(1),3,17)));}
        return List.copyOf(result);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("6003 私有恢复材料无效或版本不受支持");}
}
