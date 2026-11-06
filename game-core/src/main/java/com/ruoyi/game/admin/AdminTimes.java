package com.ruoyi.game.admin;

import java.time.*;
import java.util.*;

/** 数据库的无时区 UTC 列在后台边界明确转换为带 Z 的时间，避免浏览器误当北京时间。 */
public final class AdminTimes {
    private AdminTimes(){ }
    public static Map<String,Object> utc(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value)->{
            if(value instanceof LocalDateTime time)result.put(key,time.toInstant(ZoneOffset.UTC).toString());
            else if(value instanceof java.sql.Timestamp time)result.put(key,time.toInstant().toString());
            else if(value instanceof java.sql.Date date)result.put(key,date.toLocalDate().toString());
            else result.put(key,value);
        });
        return result;
    }
}
