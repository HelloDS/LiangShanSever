package com.ruoyi.game.auth;

public final class GameFailure extends RuntimeException {
    private final int state;
    public GameFailure(int state, String message) { super(message); this.state = state; }
    public int state() { return state; }
}
