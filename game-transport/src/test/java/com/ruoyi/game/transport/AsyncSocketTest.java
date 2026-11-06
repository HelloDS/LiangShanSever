package com.ruoyi.game.transport;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.club.ClubService;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncSocketTest {
    private static final String REQUEST="{\"path\":\"logic\",\"reqData\":{\"api\":\"room\",\"c\":\"gameMessage\"}}";
    private static GameDispatcher.Result result(int sequence,ConnectionRegistry.Binding binding){return new GameDispatcher.Result(Map.of("state",0,"sequence",sequence),binding);}
    private static final class Harness implements AutoCloseable {
        final BusinessExecutor business=new BusinessExecutor();
        final ReplyExecutor replies;
        final InboundBudget inbound;
        final ConnectionRegistry registry=new ConnectionRegistry(Clock.systemUTC());
        final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        final OnlinePresence presence=mock(OnlinePresence.class);
        final GameJsonCodec codec=new GameJsonCodec();
        final EmbeddedChannel channel;
        volatile GameRequestDispatcher route=(request,binding,connection)->CompletableFuture.completedFuture(result(0,binding));
        Harness(){this(new ReplyExecutor(16),new InboundBudget(262144));}
        Harness(ReplyExecutor replies,InboundBudget inbound){
            this.replies=replies;this.inbound=inbound;
            var telemetry=new GameTelemetry(metrics,registry,business,new ConnectionAdmission(16),inbound,replies);
            channel=new EmbeddedChannel(new GameSocketHandler(business,(request,binding,connection)->route.dispatchAsync(request,binding,connection),registry,presence,codec,Clock.systemUTC(),telemetry,inbound,replies));
        }
        void send(){channel.writeInbound(new TextWebSocketFrame(REQUEST));}
        void await(BooleanSupplier condition) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            do{channel.runPendingTasks();if(condition.getAsBoolean())return;Thread.sleep(1);}while(System.nanoTime()<until);
            fail("异步条件未在期限内完成");
        }
        Map<String,Object> packet(){
            Object frame=channel.readOutbound();assertInstanceOf(TextWebSocketFrame.class,frame);
            try{var json=codec.read(((TextWebSocketFrame)frame).text());Map<String,Object> values=new HashMap<>();for(String key:List.of("state","sequence","cmd")){var value=json.get(key);if(value!=null)values.put(key,value.isIntegralNumber()?value.asInt():value.asString());}return values;}
            finally{ReferenceCountUtil.release(frame);}
        }
        ConnectionRegistry.Binding login() throws Exception {
            AtomicReference<ConnectionRegistry.Binding> binding=new AtomicReference<>();
            route=(request,old,connection)->{var fresh=registry.bind(new GuestIdentity(1,"async-fixture",100001,0,true),connection,"fixture");binding.set(fresh);return CompletableFuture.completedFuture(result(0,fresh));};
            send();await(()->!channel.outboundMessages().isEmpty());assertEquals(0,packet().get("state"));return binding.get();
        }
        @Override public void close(){channel.finishAndReleaseAll();business.close();replies.close();metrics.close();}
    }
    @Test void delayedResultReleasesBusinessWorkerAndPreservesConnectionFifo() throws Exception {
        try(var h=new Harness()){
            CompletableFuture<GameDispatcher.Result> gate=new CompletableFuture<>();AtomicInteger calls=new AtomicInteger();
            h.route=(request,binding,channel)->calls.incrementAndGet()==1?gate:CompletableFuture.completedFuture(result(2,binding));
            h.send();h.send();h.await(()->calls.get()==1&&h.business.active()==0);
            assertTrue(h.inbound.used()>REQUEST.length());assertEquals(1,h.replies.pending());assertNull(h.channel.readOutbound());
            gate.complete(result(1,null));h.await(()->h.channel.outboundMessages().size()==2&&h.replies.pending()==0);
            assertEquals(1,h.packet().get("sequence"));assertEquals(2,h.packet().get("sequence"));assertEquals(2,calls.get());assertEquals(0,h.inbound.used());
        }
    }
    @Test void disconnectDoesNotReleaseUnknownRequestTicketAndGlobalCapacityRecoversOnResult() throws Exception {
        var replies=new ReplyExecutor(1);var inbound=new InboundBudget(262144);
        try(var first=new Harness(replies,inbound);var second=new Harness(replies,inbound)){
            var gate=new CompletableFuture<GameDispatcher.Result>();AtomicInteger calls=new AtomicInteger();
            first.route=(request,binding,channel)->gate;second.route=(request,binding,channel)->{calls.incrementAndGet();return CompletableFuture.completedFuture(result(1,binding));};
            first.send();first.await(()->first.business.active()==0&&replies.pending()==1);first.channel.close();assertEquals(1,replies.pending());
            second.send();assertEquals(-5,second.packet().get("state"));assertEquals(0,calls.get());assertEquals(REQUEST.length(),inbound.used());
            gate.complete(result(1,null));first.await(()->replies.pending()==0);assertEquals(0,inbound.used());
            second.send();second.await(()->!second.channel.outboundMessages().isEmpty());assertEquals(0,second.packet().get("state"));assertEquals(1,calls.get());
        }
    }
    @Test void bothDeferredAndActionPushesFollowTheSingleOrdinaryAck() throws Exception {
        try(var h=new Harness()){
            var binding=h.login();var gate=new CompletableFuture<GameDispatcher.Result>();AtomicBoolean invoked=new AtomicBoolean();
            h.route=(request,old,channel)->{invoked.set(true);return gate;};h.send();h.await(invoked::get);
            h.registry.push(List.of(100001L),"{\"cmd\":\"before-result\"}");h.channel.runPendingTasks();assertNull(h.channel.readOutbound());
            gate.complete(new GameDispatcher.Result(Map.of("state",0),binding,List.of(new ClubService.Notification(List.of(100001L),Map.of("cmd","action-push")))));
            h.await(()->h.channel.outboundMessages().size()==3&&h.registry.pendingPushes()==0);
            assertEquals(0,h.packet().get("state"));assertEquals("before-result",h.packet().get("cmd"));assertEquals("action-push",h.packet().get("cmd"));assertNull(h.channel.readOutbound());
        }
    }
    @Test void exceptionalStageUnwrapsKnownBusinessFailureExactlyOnce() throws Exception {
        try(var h=new Harness()){
            h.route=(request,binding,channel)->CompletableFuture.failedFuture(new CompletionException(new GameFailure(-18,"规则未确认")));
            h.send();h.await(()->!h.channel.outboundMessages().isEmpty()&&h.replies.pending()==0);
            assertEquals(-18,h.packet().get("state"));assertNull(h.channel.readOutbound());assertEquals(0,h.inbound.used());
        }
    }
    @Test void unknownCommitClosesWithoutFalseFailureAckOrDispatchingNextRequest() throws Exception {
        try(var h=new Harness()){
            var gate=new CompletableFuture<GameDispatcher.Result>();AtomicInteger calls=new AtomicInteger();
            h.route=(request,binding,channel)->{calls.incrementAndGet();return gate;};h.send();h.send();
            h.await(()->calls.get()==1);gate.completeExceptionally(new GameRequestDispatcher.OutcomeUnknown());h.await(()->!h.channel.isActive()&&h.replies.pending()==0);
            Object frame=h.channel.readOutbound();assertInstanceOf(CloseWebSocketFrame.class,frame);try{assertEquals(1012,((CloseWebSocketFrame)frame).statusCode());}finally{ReferenceCountUtil.release(frame);}
            assertNull(h.channel.readOutbound());assertEquals(1,calls.get());assertEquals(0,h.inbound.used());
        }
    }
    @Test void admittedResultHasReservedReplyCapacityEvenWhenBusinessPoolIsFull() throws Exception {
        try(var h=new Harness()){
            var gate=new CompletableFuture<GameDispatcher.Result>();AtomicBoolean invoked=new AtomicBoolean();
            h.route=(request,binding,channel)->{invoked.set(true);return gate;};h.send();h.await(()->invoked.get()&&h.business.active()==0);
            CountDownLatch running=new CountDownLatch(16),release=new CountDownLatch(1);
            Runnable blocked=()->{running.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}};
            try{
                for(int i=0;i<272;i++)h.business.execute(blocked);assertTrue(running.await(3,TimeUnit.SECONDS));assertEquals(256,h.business.queued());
                gate.complete(result(7,null));h.await(()->!h.channel.outboundMessages().isEmpty()&&h.replies.pending()==0);
                assertEquals(7,h.packet().get("sequence"));assertEquals(0,h.inbound.used());
            }finally{release.countDown();}
        }
    }
    @Test void cancellationTimeoutAndUnclassifiedStorageFailureDoNotClaimRollback() throws Exception {
        for(Throwable failure:List.of(new CancellationException(),new TimeoutException(),new java.sql.SQLTransientConnectionException())){
            try(var h=new Harness()){
                h.route=(request,binding,channel)->CompletableFuture.failedFuture(failure);h.send();h.await(()->!h.channel.isActive()&&h.replies.pending()==0);
                Object frame=h.channel.readOutbound();assertInstanceOf(CloseWebSocketFrame.class,frame);ReferenceCountUtil.release(frame);assertNull(h.channel.readOutbound());assertEquals(0,h.inbound.used());
            }
        }
    }
    @Test void stoppedReplyExecutorClosesInsteadOfReportingCommittedActionAsFailed() throws Exception {
        try(var h=new Harness()){
            var gate=new CompletableFuture<GameDispatcher.Result>();AtomicBoolean invoked=new AtomicBoolean();
            h.route=(request,binding,channel)->{invoked.set(true);return gate;};h.send();h.await(invoked::get);h.replies.close();gate.complete(result(1,null));
            h.await(()->!h.channel.isActive()&&h.replies.pending()==0);assertNull(h.channel.readOutbound());assertEquals(0,h.inbound.used());
        }
    }
    public static final class BrokenPayload {public String getValue(){throw new IllegalStateException("encoding fixture");}}
    @Test void responseEncodingFailureAfterConfirmedActionDoesNotProduceFalseFailureAck() throws Exception {
        try(var h=new Harness()){
            h.route=(request,binding,channel)->CompletableFuture.completedFuture(new GameDispatcher.Result(Map.of("state",0,"value",new BrokenPayload()),binding));
            h.send();h.await(()->!h.channel.isActive()&&h.replies.pending()==0);
            Object frame=h.channel.readOutbound();assertInstanceOf(CloseWebSocketFrame.class,frame);ReferenceCountUtil.release(frame);assertNull(h.channel.readOutbound());assertEquals(0,h.inbound.used());
        }
    }
}
