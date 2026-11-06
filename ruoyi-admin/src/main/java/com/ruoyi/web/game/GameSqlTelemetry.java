package com.ruoyi.web.game;

import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GameSqlTelemetry {
    public GameSqlTelemetry(MeterRegistry metrics,@Qualifier("masterDataSource") DataSource general,@Qualifier("roomDataSource") DataSource room){
        register(metrics,general,"general");register(metrics,room,"room");
    }
    private void register(MeterRegistry metrics,DataSource source,String name){
        if(!(source instanceof DruidDataSource pool))throw new IllegalStateException("游戏 SQL 连接池必须为 Druid");
        Gauge.builder("game.sql.active",pool,DruidDataSource::getActiveCount).tag("pool",name).register(metrics);
        Gauge.builder("game.sql.idle",pool,DruidDataSource::getPoolingCount).tag("pool",name).register(metrics);
        Gauge.builder("game.sql.waiters",pool,DruidDataSource::getWaitThreadCount).tag("pool",name).register(metrics);
        Gauge.builder("game.sql.maximum",pool,DruidDataSource::getMaxActive).tag("pool",name).register(metrics);
    }
}
