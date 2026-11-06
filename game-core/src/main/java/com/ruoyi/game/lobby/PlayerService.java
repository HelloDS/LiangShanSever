package com.ruoyi.game.lobby;

import java.util.*;
import org.springframework.stereotype.Service;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.IdentityMapper;

@Service
public class PlayerService {
    private final IdentityMapper mapper;
    public PlayerService(IdentityMapper mapper) { this.mapper = mapper; }
    public Map<String,Object> ownRole(long uid) {
        Map<String,Object> row = mapper.ownRole(uid);
        if (row == null) throw new GameFailure(-1, "玩家不存在");
        Map<String,Object> role = new LinkedHashMap<>(row);
        // 下列字段来自大厅默认分支；不赋予代理、圈或支付权限。
        role.put("phone", ""); role.put("phoneNum", ""); role.put("isProxy", 0);
        role.put("isIdentify", 0); role.put("isManager", 0); role.put("koi", 0);
        return role;
    }
    public Map<String,Object> publicRole(long uid) {
        Map<String,Object> role = mapper.publicRole(uid);
        if (role == null) throw new GameFailure(-1, "玩家不存在");
        return role;
    }
    public List<Map<String,Object>> notices() { return mapper.notices(); }
    public void location(long uid, double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180) {
            throw new GameFailure(-2, "位置参数无效");
        }
        mapper.location(uid,latitude,longitude);
    }
}
