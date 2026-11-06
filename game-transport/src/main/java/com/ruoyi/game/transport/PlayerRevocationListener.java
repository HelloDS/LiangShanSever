package com.ruoyi.game.transport;

import com.ruoyi.game.admin.PlayerRevoked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PlayerRevocationListener {
    private final ConnectionRegistry registry;
    public PlayerRevocationListener(ConnectionRegistry registry){this.registry=registry;}
    @TransactionalEventListener public void revoke(PlayerRevoked event){registry.disconnectBeforeVersion(event.uid(),event.sessionVersion());}
}
