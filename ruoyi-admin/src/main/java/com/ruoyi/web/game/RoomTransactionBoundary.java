package com.ruoyi.web.game;

import java.util.function.Supplier;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.framework.datasource.DynamicDataSourceContextHolder;
import com.ruoyi.game.room.RoomTransactions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** 房间池与普通池共用同一个路由 DataSource/事务管理器，业务 Mapper 必须加入该连接。 */
@Component
public class RoomTransactionBoundary implements RoomTransactions {
    private final TransactionTemplate transactions;
    public RoomTransactionBoundary(PlatformTransactionManager manager){transactions=new TransactionTemplate(manager);transactions.setTimeout(5);}
    @Override public <T> T execute(Supplier<T> work){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("房间提交必须从独立 I/O 入口开启事务");
        String previous=DynamicDataSourceContextHolder.getDataSourceType();
        DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.ROOM.name());
        try{return transactions.execute(status->work.get());}
        finally{if(previous==null)DynamicDataSourceContextHolder.clearDataSourceType();else DynamicDataSourceContextHolder.setDataSourceType(previous);}
    }
}
