package com.ruoyi.game.lobby;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.*;

@Service
public class ReportService {
    private final ReportMapper mapper;
    private final GameAdminMapper audit;
    private final GameAdminService permissions;
    private final AdminOperations operations;
    private final PiiCodec pii;
    private final Clock clock;
    public ReportService(ReportMapper mapper,GameAdminMapper audit,GameAdminService permissions,AdminOperations operations,PiiCodec pii,Clock clock){
        this.mapper=mapper;this.audit=audit;this.permissions=permissions;this.operations=operations;this.pii=pii;this.clock=clock;
    }
    public record Submission(String type,int contactWay,String telephone,String wechat,String detail){ }
    @Transactional public void submit(long uid,Submission request){
        if(!Set.of("涉嫌赌博","名称违规","游戏作弊").contains(request.type()))throw new GameFailure(-48,"举报类型不存在");
        if(request.contactWay()!=1&&request.contactWay()!=-1)throw new GameFailure(-4,"联系类型无效");
        if(request.detail()==null||request.detail().isBlank())throw new GameFailure(-51,"举报内容为空");
        if(request.detail().length()>2000)throw new GameFailure(-4,"举报内容过长");
        boolean anonymous=request.contactWay()==-1;
        String telephone=anonymous?"":Objects.requireNonNullElse(request.telephone(),"");
        String wechat=anonymous?"":Objects.requireNonNullElse(request.wechat(),"");
        if(!anonymous&&!telephone.matches("1[3-9][0-9]{9}"))throw new GameFailure(-50,"请填写有效手机号");
        if(wechat.length()>80)throw new GameFailure(-4,"微信号过长");
        Map<String,Object> report=new HashMap<>();report.put("id",UUID.randomUUID().toString());report.put("uid",uid);report.put("type",request.type());
        report.put("anonymous",anonymous);report.put("detail",request.detail());report.put("phone",pii.encrypt(telephone));report.put("wechat",pii.encrypt(wechat));
        report.put("mask",anonymous?"匿名":telephone.substring(0,3)+"****"+telephone.substring(7));report.put("now",now());mapper.insert(report);
    }
    public Map<String,Object> list(long actor,String status,int page,int limit){
        permissions.requirePlatform(actor);
        if(status!=null&&!Set.of("PENDING","PROCESSED","REJECTED").contains(status))throw new IllegalArgumentException("举报状态无效");
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        return Map.of("code",200,"rows",mapper.list(status,(page-1)*limit,limit).stream().map(AdminTimes::utc).toList(),"total",mapper.count(status));
    }
    public Map<String,Object> process(long actor,String id,String status,String note,String key){
        permissions.requirePlatform(actor);
        if(!Set.of("PROCESSED","REJECTED").contains(status))throw new IllegalArgumentException("处理状态无效");
        return operations.execute(actor,"report:process",id,note,key,Map.of("status",status),()->{
            var row=mapper.lock(id);if(row==null)throw new IllegalArgumentException("举报不存在");
            if(!"PENDING".equals(row.get("status")))throw new IllegalArgumentException("举报已处理，请刷新列表");
            mapper.process(id,status,note,actor,now());return new AdminOperations.Change(Map.of("status","PENDING"),Map.of("status",status),Map.of("id",id));
        });
    }
    @Transactional public Map<String,Object> contacts(long actor,String id,String reason){
        permissions.requirePlatform(actor);
        if(reason==null||reason.trim().length()<3||reason.length()>512)throw new IllegalArgumentException("请填写查看原因");
        var row=mapper.lock(id);if(row==null)throw new IllegalArgumentException("举报不存在");
        audit.audit(actor,"report:contact",id,reason,"{}","{\"contactViewed\":true}",now());
        Map<String,Object> result=new LinkedHashMap<>();result.put("telephone",pii.decrypt((String)row.get("phone")));result.put("wechat",pii.decrypt((String)row.get("wechat")));return result;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
