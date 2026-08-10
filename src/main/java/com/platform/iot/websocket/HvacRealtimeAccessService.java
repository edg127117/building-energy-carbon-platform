package com.platform.iot.websocket;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.security.FormalRole;
import com.platform.security.JwtService;
import com.platform.system.service.BuildingScopeService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
/**
 * 将 WebSocket 首帧和心跳复用为与 HTTP 一致的登录态及建筑范围校验。
 *
 * <p>JWT 只提供已签名的用户与正式角色快照；Redis 明确拒绝时必须关闭连接，Redis 不可用时
 * 才沿用 HTTP 的可用性优先降级。建筑范围仍通过 {@link BuildingScopeService} 实时判断，权限
 * 事实来源不可用时保持失败关闭，不能把暂时故障伪装成用户越权或直接放行。</p>
 */
public class HvacRealtimeAccessService {

    private static final String UNAUTHORIZED_MESSAGE = "实时登录状态无效或已过期";
    private static final String FORBIDDEN_MESSAGE = "无权订阅该建筑";
    private static final String AUTH_UNAVAILABLE_MESSAGE = "实时权限校验暂不可用";

    private final JwtService jwtService;
    private final TokenCacheService tokenCacheService;
    private final BuildingScopeService buildingScopeService;

    public HvacRealtimeAccessService(
            JwtService jwtService,
            TokenCacheService tokenCacheService,
            BuildingScopeService buildingScopeService) {
        this.jwtService = jwtService;
        this.tokenCacheService = tokenCacheService;
        this.buildingScopeService = buildingScopeService;
    }

    /**
     * 在会话加入建筑路由前完成 JWT、Redis 单账号登录态与 MySQL 建筑范围三层复核。
     *
     * <p>任何未能证明目标建筑范围的外部资源故障都返回 1011；只有已验证 JWT 且 Redis 明确
     * 报告不可用时，才允许继续，以保持现有 HTTP 登录态降级语义一致。</p>
     */
    public HvacRealtimeSubscription authenticate(String token, String buildingId) {
        if (isBlank(token) || isBlank(buildingId)) {
            throw unauthorized();
        }
        try {
            Claims claims = jwtService.parseToken(token).getPayload();
            Long userId = jwtService.getUserId(claims);
            Set<String> roles = formalRoles(jwtService.getRoles(claims));
            Date expiration = claims.getExpiration();
            if (userId == null || roles.isEmpty() || expiration == null
                    || !expiration.after(new Date())) {
                throw unauthorized();
            }

            TokenValidationResult tokenState =
                    tokenCacheService.validateActiveToken(userId, token);
            if (tokenState != TokenValidationResult.ACTIVE
                    && tokenState != TokenValidationResult.CACHE_UNAVAILABLE) {
                throw unauthorized();
            }

            buildingScopeService.checkAccess(userId, roles, buildingId);
            return new HvacRealtimeSubscription(
                    userId, roles, buildingId, token, expiration.getTime());
        } catch (HvacRealtimeAccessException exception) {
            throw exception;
        } catch (BusinessException exception) {
            if (Integer.valueOf(403).equals(exception.getCode())) {
                throw forbidden();
            }
            throw unavailable();
        } catch (DataAccessException exception) {
            throw unavailable();
        } catch (JwtException | IllegalArgumentException exception) {
            throw unauthorized();
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 心跳以原 Token 和原建筑重新走完整校验，确保权限撤销、账号重新登录与 JWT 自然过期不会
     * 让已建立连接持续接收指标。
     */
    public HvacRealtimeSubscription revalidate(HvacRealtimeSubscription current) {
        if (current == null) {
            throw unauthorized();
        }
        HvacRealtimeSubscription refreshed =
                authenticate(current.token(), current.buildingId());
        if (!Objects.equals(refreshed.userId(), current.userId())) {
            throw unauthorized();
        }
        return refreshed;
    }

    private Set<String> formalRoles(List<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(role -> role.toUpperCase(Locale.ROOT))
                .filter(FormalRole::isFormal)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static HvacRealtimeAccessException unauthorized() {
        return new HvacRealtimeAccessException(
                "UNAUTHORIZED", HvacRealtimeProtocol.CLOSE_UNAUTHORIZED,
                UNAUTHORIZED_MESSAGE);
    }

    private static HvacRealtimeAccessException forbidden() {
        return new HvacRealtimeAccessException(
                "FORBIDDEN_BUILDING", HvacRealtimeProtocol.CLOSE_FORBIDDEN,
                FORBIDDEN_MESSAGE);
    }

    private static HvacRealtimeAccessException unavailable() {
        return new HvacRealtimeAccessException(
                "REALTIME_AUTH_UNAVAILABLE", 1011, AUTH_UNAVAILABLE_MESSAGE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
