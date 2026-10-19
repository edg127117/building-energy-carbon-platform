package com.platform.security;

import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountSessionCommitFailureTest {
    @Test
    void commitExceptionAfterRedisWriteCannotReturnJwtOrExposeDriverDetails() {
        SysUserMapper users = mock(SysUserMapper.class);
        SysRoleMapper roles = mock(SysRoleMapper.class);
        TokenCacheService tokens = mock(TokenCacheService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus tx = new SimpleTransactionStatus();
        when(tm.getTransaction(any())).thenReturn(tx);
        doThrow(new TransactionSystemException("private connection detail")).when(tm).commit(tx);
        SysUser user = new SysUser();
        user.setId(7L); user.setUsername("operator"); user.setPassword("hash");
        user.setStatus(1); user.setDelFlag(0); user.setActivationPending(0); user.setSecurityVersion(1L);
        when(users.lockById(7L)).thenReturn(user);
        when(users.incrementSecurityVersion(7L)).thenReturn(1);
        when(roles.selectRoleKeysByUserId(7L)).thenReturn(List.of("BUILDING_OWNER"));
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-jwt-secret-with-more-than-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt, "expireSeconds", 3600L);
        AccountSessionService sessions = new AccountSessionService(users, roles, jwt, tokens,
                tm, mock(AccountCleanupService.class));
        assertThatThrownBy(() -> sessions.login(7L, "hash", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).doesNotContain("private");
                });
        verify(tokens).addToWhitelist(eq(7L), anyString());
        verify(tm).commit(tx);
    }
}
