package com.ruoyi.game.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录事件与日统计在同一事务内，Redis 未能建立/恢复会话时整体回滚。 */
@Service
public class GameLoginService {
    private final GuestAuthService auth;
    private final GameSessionService sessions;
    public GameLoginService(GuestAuthService auth,GameSessionService sessions){this.auth=auth;this.sessions=sessions;}
    @Transactional public String login(GuestIdentity identity,String kind,String existingToken){
        auth.recordLogin(identity,kind);
        if(existingToken==null)return sessions.create(identity);
        sessions.verify(existingToken);
        return existingToken;
    }
}
