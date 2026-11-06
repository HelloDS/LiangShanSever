package com.ruoyi.game.auth;

public record GuestIdentity(long accountId, String credentialId, long uid, long sessionVersion, boolean test) { }
