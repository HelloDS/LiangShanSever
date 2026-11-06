package com.ruoyi.game.transport;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 覆盖排队和在途请求的全局原始字节预算；JSON 仍受 codec 的深度/大小限制。 */
@Component
public class InboundBudget {
    private final long maximum;
    private final AtomicLong used=new AtomicLong();
    public InboundBudget(@Value("${game.ws.inbound-budget-bytes:33554432}") long maximum){if(maximum<262144||maximum>1073741824)throw new IllegalArgumentException("入站预算配置无效");this.maximum=maximum;}
    public boolean acquire(int bytes){
        if(bytes<0)return false;
        long current;do{current=used.get();if(bytes>maximum-current)return false;}while(!used.compareAndSet(current,current+bytes));return true;
    }
    public void release(long bytes){
        if(bytes<0)throw new IllegalArgumentException("释放字节数无效");
        long current;do{current=used.get();if(bytes>current)throw new IllegalStateException("入站预算重复释放");}while(!used.compareAndSet(current,current-bytes));
    }
    public long used(){return used.get();}
}
