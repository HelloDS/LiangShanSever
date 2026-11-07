package com.ruoyi.game.economy;

/** 2026-11-07 用户明确给定的本项目规则，不是原站反推值。 */
public final class ConfirmedBusinessRules {
    private ConfirmedBusinessRules(){ }
    public static final String VERSION="user-rules-20261107-v1";
    public static final long CARDS_PER_DIAMOND=10;
    public static final int CLUB_MEMBER_CAP=1000;
    public static final int CLUB_MANAGER_CAP=3;
    public static final int CLUB_INITIAL_MEMBERS=100;
    public static final int CLUB_UPGRADE_MEMBERS=100;
    public static final long CLUB_UPGRADE_CARDS=100;
    public static final long CLUB_CREATION_CARDS=100;
    public static final long STARTER_CARDS=1000;
    public static final long STARTER_DIAMONDS=100;
    public static final String CLUB_LIFECYCLE_VERSION="club-rules-20261107-v1";
    public static final boolean CLUB_REFUND_SUPPORTED=false;
}
