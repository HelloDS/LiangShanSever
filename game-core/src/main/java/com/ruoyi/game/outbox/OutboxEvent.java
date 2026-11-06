package com.ruoyi.game.outbox;

import java.time.LocalDateTime;

public record OutboxEvent(String id,String businessKey,String eventType,int schemaVersion,String ruleVersion,
        String payload,int attempts,String leaseToken,LocalDateTime occurredAt) { }
