package com.ruoyi.game.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.ruoyi.game.protocol.GameJsonCodec;

@Configuration
@EnableScheduling

public class GameConfiguration {
    @Bean public GameJsonCodec gameJsonCodec() { return new GameJsonCodec(); }
    @Bean public Clock gameClock() { return Clock.systemUTC(); }
}
