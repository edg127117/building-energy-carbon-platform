package com.platform.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.MenuCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMenuMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.dto.MenuAdminDtos;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.impl.SysMenuServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysMenuAdminServiceTest {

    @Mock private SysMenuMapper menuMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private MenuCacheService menuCacheService;

    private SysMenuServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysMenuServiceImpl(roleMenuMapper, userMapper, menuCacheService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", menuMapper);
    }

    @Test
    void adminTreeIncludesDisabledAndHiddenMenus() {
        SysMenu root = menu(1L, 0L, 0, 0);
        when(menuMapper.selectList(any())).thenReturn(List.of(root));

        assertThat(service.adminTree().getData()).containsExactly(root);
    }

    @Test
    void addRejectsMissingParent() {
        when(menuMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(create(99L, "C")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("父菜单不存在");
        verify(menuMapper, never()).insert(any());
    }

    @Test
    void updateRejectsSelfParent() {
        SysMenu existing = menu(7L, 0L, 1, 1);
        when(menuMapper.selectById(7L)).thenReturn(existing);

        assertThatThrownBy(() -> service.update(update(7L, 7L, "C")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单不能选择自身作为父级");
    }

    @Test
    void updateRejectsAncestorCycle() {
        when(menuMapper.selectById(7L)).thenReturn(menu(7L, 0L, 1, 1));
        when(menuMapper.selectById(9L)).thenReturn(menu(9L, 7L, 1, 1));

        assertThatThrownBy(() -> service.update(update(7L, 9L, "C")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("父菜单层级存在循环");
    }

    @Test
    void rejectsUnsupportedMenuType() {
        assertThatThrownBy(() -> service.add(create(0L, "X")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单类型不合法");
    }

    @Test
    void rejectsInvalidVisibleOrStatus() {
        MenuAdminDtos.CreateRequest request = new MenuAdminDtos.CreateRequest(
                0L, "测试", "C", "/test", null, null, null, 2, -1, 0);

        assertThatThrownBy(() -> service.add(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单可见性或状态不合法");
    }

    @Test
    void updateEvictsAllActiveUserMenuCaches() {
        SysMenu existing = menu(7L, 0L, 1, 1);
        when(menuMapper.selectById(7L)).thenReturn(existing);
        when(menuMapper.updateById(any())).thenReturn(1);
        when(userMapper.selectActiveUserIds()).thenReturn(List.of(1L, 2L));

        service.update(update(7L, 0L, "C"));

        verify(menuCacheService).evict(null);
        verify(menuCacheService).evict(1L);
        verify(menuCacheService).evict(2L);
    }

    private MenuAdminDtos.CreateRequest create(Long parentId, String type) {
        return new MenuAdminDtos.CreateRequest(parentId, "测试菜单", type, "/test", null,
                null, null, 1, 1, 0);
    }

    private MenuAdminDtos.UpdateRequest update(Long id, Long parentId, String type) {
        return new MenuAdminDtos.UpdateRequest(id, parentId, "测试菜单", type, "/test", null,
                null, null, 1, 1, 0);
    }

    private SysMenu menu(Long id, Long parentId, Integer visible, Integer status) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuName("菜单" + id);
        menu.setMenuType("C");
        menu.setVisible(visible);
        menu.setStatus(status);
        menu.setSortOrder(0);
        return menu;
    }
}
