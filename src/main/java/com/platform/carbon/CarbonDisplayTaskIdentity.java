package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import com.platform.security.AuthenticationStateService;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** 后台任务只保存账号标识，执行/登记时重读启用状态和角色；不保存Token或自动赋权。 */
@Component
class CarbonDisplayTaskIdentity {
    private final SysUserMapper users;
    private final SysRoleMapper roles;
    CarbonDisplayTaskIdentity(SysUserMapper users,SysRoleMapper roles){this.users=users;this.roles=roles;}
    List<String> currentRoles(long actor){
        if(actor<=0||!AuthenticationStateService.isEnabled(users.selectSecurityState(actor)))
            throw new BusinessException(403,"CARBON_DISPLAY_ACTOR_DISABLED","后台读取账号不可用");
        var result=roles.selectRoleKeysByUserId(actor).stream().map(r->r.toUpperCase(Locale.ROOT))
                .filter(Set.of("BUILDING_OWNER","ENERGY_MANAGER","PLATFORM_ADMIN")::contains).toList();
        if(result.isEmpty())throw new BusinessException(403,"CARBON_DISPLAY_ACTOR_FORBIDDEN","后台读取职责不可用");
        return result;
    }
}
