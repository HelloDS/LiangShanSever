package com.ruoyi.common.enums;

/**
 * 数据源
 * 
 * @author ruoyi
 */
public enum DataSourceType
{
    /**
     * 主库
     */
    MASTER,

    /**
     * 从库
     */
    SLAVE,

    /** 房间提交专用连接池，仍连接同一 MySQL 数据库。 */
    ROOM
}
