package com.platform.security;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysUser;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** HTTP 和 WebSocket 共用的权威登录态校验；Redis 命中不能替代 MySQL 当前状态与版本。 */
@Service
public class AuthenticationStateService {
    private final TokenCacheService tokens;
    private final SysUserMapper users;
    private final TransactionTemplate currentRead;

    public AuthenticationStateService(TokenCacheService tokens, SysUserMapper users,
                                      PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.users = users;
        // 挂起调用方可能存在的旧快照，只从主数据源读取当前已提交状态，不设置只读路由提示。
        currentRead = new TransactionTemplate(transactionManager);
        currentRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        currentRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        currentRead.setTimeout(5);
    }

    public void requireActive(Long userId, String token, Long version) {
        if (userId == null || version == null || version < 1) throw unauthorized();
        TokenValidationResult state = tokens.validateActiveToken(userId, token);
        if (state == TokenValidationResult.CACHE_UNAVAILABLE) throw unavailable();
        if (state != TokenValidationResult.ACTIVE) throw unauthorized();
        try {
            SysUser user = currentRead.execute(status -> users.selectSecurityState(userId));
            if (!isEnabled(user) || !version.equals(user.getSecurityVersion())) throw unauthorized();
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable();
        }
    }

    public static boolean isEnabled(SysUser user) {
        return user != null && Integer.valueOf(1).equals(user.getStatus())
                && Integer.valueOf(0).equals(user.getDelFlag())
                && Integer.valueOf(0).equals(user.getActivationPending());
    }

    public static BusinessException unauthorized() {
        return new BusinessException(401, "AUTH_STATE_REJECTED", "未登录或登录已过期");
    }

    public static BusinessException unavailable() {
        return new BusinessException(503, "AUTH_STATE_UNAVAILABLE", "登录状态服务暂不可用，请稍后重试");
    }
}
