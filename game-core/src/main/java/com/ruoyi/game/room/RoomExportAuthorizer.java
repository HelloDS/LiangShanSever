package com.ruoyi.game.room;

/** 异步任务没有 HTTP 身份上下文，由后台适配器重读同一套若依权限。 */
@FunctionalInterface
public interface RoomExportAuthorizer {boolean allowed(long actor);}
