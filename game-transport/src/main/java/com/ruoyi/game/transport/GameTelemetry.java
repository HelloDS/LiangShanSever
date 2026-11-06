package com.ruoyi.game.transport;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

@Component
public class GameTelemetry {
    private static final Set<String> APIS=Set.of("user","role","cfg","game","room","club","inform","activity","koi","manager","build");
    private final MeterRegistry metrics;
    public GameTelemetry(MeterRegistry metrics,ConnectionRegistry registry,BusinessExecutor executor){
        this.metrics=metrics;
        Gauge.builder("game.authenticated.connections",registry,r->r.all().size()).register(metrics);
        Gauge.builder("game.push.pending",registry,ConnectionRegistry::pendingPushes).register(metrics);
        Gauge.builder("game.business.queue",executor,BusinessExecutor::queued).register(metrics);
        Gauge.builder("game.business.active",executor,BusinessExecutor::active).register(metrics);
    }
    public void complete(String api,String result,long nanos){
        String boundedApi=APIS.contains(api)?api:"unknown";
        Timer.builder("game.request.duration").tag("api",boundedApi).tag("result",result)
                .serviceLevelObjectives(Duration.ofMillis(50),Duration.ofMillis(100),Duration.ofMillis(300)).register(metrics)
                .record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
    }
}
