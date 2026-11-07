package com.ruoyi.game.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.games.landlord.LandlordScoring;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableScheduling

public class GameConfiguration {
    @Bean public GameJsonCodec gameJsonCodec() { return new GameJsonCodec(); }
    @Bean public Clock gameClock() { return Clock.systemUTC(); }
    @Bean public LandlordScoring.Policy landlordScoringPolicy(@Value("${game.landlord-scoring.version:local-6003-score-v1}") String version,
            @Value("${game.landlord-scoring.base:1}") long base,@Value("${game.landlord-scoring.unit-cap:64}") long unitCap){return new LandlordScoring.Policy(version,base,unitCap);}
}
