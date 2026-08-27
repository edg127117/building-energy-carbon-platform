package com.platform.support;

import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/** 为非账号治理测试直接准备隔离用户数据，不调用已经关闭的生产直改接口。 */
@Component
@RequiredArgsConstructor
public class TestUserFixture {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final MenuCacheService menuCacheService;
    private final TokenCacheService tokenCacheService;
    private final BuildingScopeService buildingScopeService;

    public long createActiveUser(String username, String password, String roleKey, String buildingId) {
        remove(username);
        jdbcTemplate.update("""
                INSERT INTO sys_user
                  (username,password,nickname,status,del_flag,activation_pending)
                VALUES (?,?,?,1,0,0)
                """, username, passwordEncoder.encode(password), "隔离测试账号");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username=?", Long.class, username);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE role_key=? AND status=1", Long.class, roleKey);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id,role_id) VALUES (?,?)", userId, roleId);
        if (buildingId != null) {
            jdbcTemplate.update("INSERT INTO sys_user_building (user_id,building_id) VALUES (?,?)",
                    userId, buildingId);
        }
        evict(userId);
        return userId;
    }

    public void remove(String username) {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username=?", Long.class, username);
        for (Long userId : userIds) {
            evict(userId);
            jdbcTemplate.update("DELETE FROM sys_password_setup_token WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user_backend_duty WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user_building WHERE user_id=?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", userId);
        }
    }

    /** 测试库可能复用自增 ID；直接准备数据后必须清除该 ID 的既有权限和登录缓存。 */
    private void evict(Long userId) {
        tokenCacheService.revokeActiveToken(userId);
        menuCacheService.evict(userId);
        buildingScopeService.evict(userId);
    }
}
