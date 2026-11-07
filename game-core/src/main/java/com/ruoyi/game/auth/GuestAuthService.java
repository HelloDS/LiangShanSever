package com.ruoyi.game.auth;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.persistence.mapper.IdentityMapper;
import com.ruoyi.game.persistence.mapper.LobbyMapper;
import com.ruoyi.game.economy.ConfirmedBusinessRules;
import com.ruoyi.game.economy.WalletPosting;
import com.ruoyi.game.economy.WalletPostingService;
import static com.ruoyi.game.economy.WalletPosting.OwnerType.PERSONAL;
import static com.ruoyi.game.economy.WalletPosting.OwnerType.SYSTEM;
import static com.ruoyi.game.economy.WalletPosting.Currency.CARD;
import static com.ruoyi.game.economy.WalletPosting.Currency.DIAMOND;

@Service
public class GuestAuthService {
    private final IdentityMapper mapper;
    private final Clock clock;
    private final long credentialDays;
    private final LobbyMapper lobby;
    private final WalletPostingService wallets;
    private final boolean starterWalletEnabled;

    public GuestAuthService(IdentityMapper mapper, LobbyMapper lobby, Clock clock, WalletPostingService wallets,
                            @Value("${game.guest-credential-days:365}") long credentialDays,
                            @Value("${game.guest-starter-wallet-enabled:true}") boolean starterWalletEnabled) {
        this.mapper = mapper;
        this.lobby = lobby;
        this.clock = clock;
        this.wallets = wallets;
        this.credentialDays = credentialDays;
        this.starterWalletEnabled = starterWalletEnabled;
    }

    @Transactional
    public String createGuest() {
        LocalDateTime now = now();
        Map<String,Object> account = new HashMap<>(Map.of("externalId", UUID.randomUUID().toString(), "now", now));
        mapper.insertAccount(account);
        long accountId = ((Number)account.get("id")).longValue();
        String token = SecureTokens.create();
        mapper.insertCredential(UUID.randomUUID().toString(), accountId, SecureTokens.hash(token), now.plusDays(credentialDays), now);
        Map<String,Object> role = new HashMap<>(Map.of("accountId", accountId, "name", "游客" + accountId, "now", now));
        mapper.insertRole(role);
        long uid = ((Number)role.get("uid")).longValue();
        mapper.insertWallets(uid);
        if (starterWalletEnabled) {
            long card = ConfirmedBusinessRules.STARTER_CARDS, diamond = ConfirmedBusinessRules.STARTER_DIAMONDS;
            wallets.post(new WalletPosting(WalletPosting.Kind.REWARD, "guest_starter:" + uid,
                    ConfirmedBusinessRules.CLUB_LIFECYCLE_VERSION, "player/" + uid, List.of(
                    new WalletPosting.Entry(new WalletPosting.Account(PERSONAL, uid, CARD), card, 0),
                    new WalletPosting.Entry(new WalletPosting.Account(SYSTEM, 0, CARD), -card, 0),
                    new WalletPosting.Entry(new WalletPosting.Account(PERSONAL, uid, DIAMOND), diamond, 0),
                    new WalletPosting.Entry(new WalletPosting.Account(SYSTEM, 0, DIAMOND), -diamond, 0))));
        }
        lobby.createKoi(uid, now);
        return token;
    }

    @Transactional
    public GuestIdentity verify(String token) {
        Map<String,Object> row = mapper.credential(SecureTokens.hash(token));
        verifyStatus(row);
        long accountId = ((Number)row.get("accountId")).longValue();
        Map<String,Object> role = mapper.roleForAccount(accountId);
        if (role == null) throw new IllegalStateException("账号缺少角色");
        String id = (String)row.get("credentialId");
        mapper.touchCredential(id, now());
        return new GuestIdentity(accountId, id, ((Number)role.get("uid")).longValue(),
                ((Number)row.get("sessionVersion")).longValue(), Boolean.TRUE.equals(row.get("isTest")));
    }

    public void verifySession(GuestIdentity identity) {
        Map<String,Object> row = mapper.sessionIdentity(identity.credentialId(), identity.accountId());
        verifyStatus(row);
        if (((Number)row.get("sessionVersion")).longValue() != identity.sessionVersion()) throw new GameFailure(-5, "会话已失效，请重新登录");
    }

    @Transactional
    public GuestIdentity logout(String token) {
        Map<String,Object> row = mapper.credential(SecureTokens.hash(token));
        if (row == null) return null;
        long accountId = ((Number)row.get("accountId")).longValue();
        mapper.revokeCredential((String)row.get("credentialId"), now());
        Map<String,Object> role = mapper.roleForAccount(accountId);
        return new GuestIdentity(accountId, (String)row.get("credentialId"), ((Number)role.get("uid")).longValue(), 0, false);
    }

    @Transactional
    public void recordLogin(GuestIdentity identity, String kind) {
        verifySession(identity);
        LocalDateTime now = now();
        LocalDate date = clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        mapper.loginEvent(UUID.randomUUID().toString(), identity.uid(), now, date, kind, identity.test());
        mapper.loginDaily(identity.uid(), now, date, identity.test());
        mapper.touchRole(identity.uid(), now);
    }

    private void verifyStatus(Map<String,Object> row) {
        if (row == null || !"ACTIVE".equals(row.get("credentialStatus")) || !expiry(row.get("expiresAt")).isAfter(now())) {
            throw new GameFailure(-5, "登录凭据已失效");
        }
        if (!"ACTIVE".equals(row.get("status"))) throw new GameFailure(-134, "账号已停用");
    }

    private static LocalDateTime expiry(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        throw new IllegalStateException("凭据有效期类型异常");
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
