package com.ruoyi.web.game;

import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.game.room.RoomExportAuthorizer;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Component;

@Component
public class RoomExportAuthorizerAdapter implements RoomExportAuthorizer {
    private final ISysUserService users;private final SysPermissionService permissions;
    public RoomExportAuthorizerAdapter(ISysUserService users,SysPermissionService permissions){this.users=users;this.permissions=permissions;}
    @Override public boolean allowed(long actor){
        var user=users.selectUserById(actor);if(user==null||!"0".equals(user.getStatus())||!"0".equals(user.getDelFlag()))return false;
        var current=permissions.getMenuPermission(user);
        return current.contains("*:*:*")||(current.contains("game:room:query")&&current.contains("game:room:export"));
    }
}
