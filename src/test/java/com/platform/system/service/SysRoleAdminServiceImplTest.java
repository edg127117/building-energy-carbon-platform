package com.platform.system.service;

import com.platform.cache.MenuCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysRoleMenuMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.model.entity.SysRole;
import com.platform.system.service.impl.SysRoleAdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysRoleAdminServiceImplTest {

    @Mock private SysRoleMapper roleMapper;
    @Mock private SysMenuMapper menuMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private MenuCacheService menuCacheService;

    private SysRoleAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysRoleAdminServiceImpl(
                roleMapper, menuMapper, roleMenuMapper, userRoleMapper, menuCacheService);
    }

    @Test
    void rejectsInternalMenuAssignmentForThirdPartyRole() {
        SysRole role = thirdPartyRole();
        when(roleMapper.selectById(3L)).thenReturn(role);

        assertThatThrownBy(() -> service.replaceMenus(3L, List.of(101L)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("不使用后台页面菜单");
                });

        verifyNoInteractions(menuMapper, roleMenuMapper, userRoleMapper, menuCacheService);
    }

    @Test
    void reportsThirdPartyAsBuildingScopedWhenLegacyDatabaseStillSaysAll() {
        SysRole role = thirdPartyRole();
        role.setDataScope("ALL");
        when(roleMapper.selectById(3L)).thenReturn(role);

        assertThat(service.detail(3L))
                .satisfies(view -> {
                    assertThat(view.roleName()).isEqualTo("接口调用方");
                    assertThat(view.dataScope()).isEqualTo("BUILDING");
                });
    }

    private SysRole thirdPartyRole() {
        SysRole role = new SysRole();
        role.setId(3L);
        role.setRoleKey("THIRD_PARTY");
        return role;
    }
}
