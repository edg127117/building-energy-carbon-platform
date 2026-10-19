package com.platform.security;

import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.entity.SysUser;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 在账号行锁内完成登录/退出与版本递增；Redis 失败回滚 MySQL，提交成功才向客户端返回 JWT。 */
@Service
public class AccountSessionService {
    private final SysUserMapper users;
    private final SysRoleMapper roles;
    private final JwtService jwt;
    private final TokenCacheService tokens;
    private final MenuCacheService menus;
    private final TransactionTemplate transaction;

    public AccountSessionService(SysUserMapper users, SysRoleMapper roles, JwtService jwt,
                                 TokenCacheService tokens, MenuCacheService menus,
                                 PlatformTransactionManager transactionManager) {
        this.users = users;
        this.roles = roles;
        this.jwt = jwt;
        this.tokens = tokens;
        this.menus = menus;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** 锁前仅做密码计算；锁后重查哈希，避免重置密码期间用旧验证结果签发新会话。 */
    public LoginResponse login(Long userId, String checkedPasswordHash, String upgradedHash) {
        return execute(() -> {
            SysUser user = users.lockById(userId);
            if (!AuthenticationStateService.isEnabled(user)) {
                throw new BusinessException(403, "账号不可登录");
            }
            if (!Objects.equals(checkedPasswordHash, user.getPassword())) {
                throw AuthenticationStateService.unauthorized();
            }
            List<String> roleKeys = roles.selectRoleKeysByUserId(userId).stream()
                    .map(String::toUpperCase).filter(FormalRole::isFormal).distinct().toList();
            if (roleKeys.isEmpty()) throw new BusinessException(403, "账号尚未分配正式角色，请联系平台管理员");
            if (upgradedHash != null) users.updatePassword(userId, upgradedHash);
            long version = incrementVersion(users, user);
            String token = jwt.generateToken(userId, user.getUsername(), roleKeys, version);
            tokens.addToWhitelist(userId, token);
            return LoginResponse.builder().token(token).tokenType("Bearer").expiresIn(jwt.getExpireSeconds())
                    .user(LoginResponse.UserInfo.builder().id(userId).username(user.getUsername())
                            .nickname(user.getNickname()).roles(roleKeys).build()).build();
        });
    }

    /** 过滤器之后可能发生新登录，必须在账号锁内重查版本，旧退出不能删除新会话。 */
    public void logout(Long userId, String token) {
        var claims = jwt.parseToken(token).getPayload();
        Long version = jwt.getSecurityVersion(claims);
        if (!Objects.equals(userId, jwt.getUserId(claims)) || version == null) {
            throw AuthenticationStateService.unauthorized();
        }
        execute(() -> {
            SysUser user = users.lockById(userId);
            if (!AuthenticationStateService.isEnabled(user) || !version.equals(user.getSecurityVersion())) {
                throw AuthenticationStateService.unauthorized();
            }
            TokenValidationResult state = tokens.validateActiveToken(userId, token);
            if (state == TokenValidationResult.CACHE_UNAVAILABLE) throw AuthenticationStateService.unavailable();
            if (state != TokenValidationResult.ACTIVE) throw AuthenticationStateService.unauthorized();
            incrementVersion(users, user);
            long remaining = Math.max(1, (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000);
            tokens.addToBlacklist(token, remaining);
            tokens.removeFromWhitelist(userId);
            menus.evict(userId);
            return null;
        });
    }

    /** 仅在持有账号锁的事务内使用；溢出或更新未命中必须失败，不能让版本回绕。 */
    public static long incrementVersion(SysUserMapper users, SysUser lockedUser) {
        if (lockedUser.getSecurityVersion() == null || lockedUser.getSecurityVersion() < 1
                || lockedUser.getSecurityVersion() == Long.MAX_VALUE
                || users.incrementSecurityVersion(lockedUser.getId()) != 1) {
            throw AuthenticationStateService.unavailable();
        }
        return lockedUser.getSecurityVersion() + 1;
    }

    private <T> T execute(Supplier<T> action) {
        try {
            return transaction.execute(status -> action.get());
        } catch (DataAccessException | TransactionException failure) {
            // 包括提交失败；不能在事务代理提交前就把 JWT 当作成功结果返回。
            throw AuthenticationStateService.unavailable();
        }
    }
}
