package com.ruoyi.game.room;

public final class RoomRejected extends RuntimeException {
    private final String code;
    public RoomRejected(String code){
        super(code);
        if(code==null||!code.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("房间错误代码无效");
        this.code=code;
    }
    public String code(){return code;}
}
