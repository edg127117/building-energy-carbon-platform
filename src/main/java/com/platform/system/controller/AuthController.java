package com.platform.system.controller;

import com.platform.audit.SecurityAuditService;
import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.security.JwtService;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.model.dto.LoginRequest;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.dto.RegisterRequest;
import com.platform.system.model.dto.PasswordSetupRequest;
import com.platform.system.service.PasswordSetupTokenService;
import com.platform.system.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 浏览器和外部客户端进入账号认证链路的 HTTP 入口。
 *
 * <p>注册、登录由 {@link SysUserService} 校验 MySQL 账号和正式角色；登录结果携带 JWT，客户端
 * 在后续请求中使用 {@code Authorization: Bearer <token>}。当前用户接口读取认证过滤器建立的
 * {@link JwtUserPrincipal}，退出接口同时撤销 Redis 登录态和个人菜单缓存。</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysUserService sysUserService;
    private final JwtService jwtService;
    private final TokenCacheService tokenCacheService;
    private final MenuCacheService menuCacheService;
    private final SecurityAuditService securityAuditService;
    private final PasswordSetupTokenService passwordSetupTokenService;

    public AuthController(SysUserService sysUserService, JwtService jwtService,
                          TokenCacheService tokenCacheService, MenuCacheService menuCacheService,
                          SecurityAuditService securityAuditService,
                          PasswordSetupTokenService passwordSetupTokenService) {
        this.sysUserService = sysUserService;
        this.jwtService = jwtService;
        this.tokenCacheService = tokenCacheService;
        this.menuCacheService = menuCacheService;
        this.securityAuditService = securityAuditService;
        this.passwordSetupTokenService = passwordSetupTokenService;
    }

    /** 注册普通账号；服务层固定分配 BUILDING_OWNER，但不会授予任何建筑范围。 */
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest request) {
        sysUserService.register(request);
        return Result.success("注册成功");
    }

    /** 校验账号密码和正式角色，返回已写入 Redis 单账号白名单的 JWT。 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        try {
            LoginResponse response = sysUserService.login(request.getUsername(), request.getPassword());
            securityAuditService.recordAuthentication(httpRequest, response.getUser().getId(),
                    "LOGIN_SUCCESS", "SUCCESS", null);
            return Result.success(response);
        } catch (BusinessException failure) {
            securityAuditService.recordAuthentication(httpRequest, null,
                    "LOGIN_FAILED", "DENIED", "LOGIN_FAILED");
            throw failure;
        }
    }

    /** 消费审批执行时返回的一次性令牌；响应和审计都不会回显令牌或密码。 */
    @PostMapping("/password/setup")
    public Result<String> setupPassword(@Valid @RequestBody PasswordSetupRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            passwordSetupTokenService.setupPassword(request.token(), request.password());
            return Result.success("密码设置成功");
        } catch (BusinessException failure) {
            securityAuditService.recordAuthentication(httpRequest, null,
                    "PASSWORD_SETUP_FAILED", "DENIED", PasswordSetupTokenService.INVALID_TOKEN);
            throw failure;
        }
    }

    /**
     * 返回安全上下文中的用户 ID、用户名和角色快照，供前端恢复登录展示状态。
     * 该响应不包含建筑范围，也不是后端授权依据。
     */
    @GetMapping("/me")
    public Result<LoginResponse.UserInfo> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return Result.error(401, "未登录或登录已过期");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof JwtUserPrincipal)) {
            return Result.error(401, "未登录或登录已过期");
        }
        JwtUserPrincipal jwtUserPrincipal = (JwtUserPrincipal) principal;

        // 角色从 SecurityContext 中读取（来源于 JWT claims 的 roles）
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toList());

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(jwtUserPrincipal.getId())
                .username(jwtUserPrincipal.getUsername())
                .roles(roles)
                .build();
        return Result.success(userInfo);
    }

    /**
     * 安全退出当前账号。
     *
     * <p>当前 Token 会按剩余有效期加入黑名单，同时移除用户白名单和菜单缓存，
     * 防止客户端继续使用已退出的 JWT。</p>
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public Result<String> logout(Authentication authentication, HttpServletRequest request) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token != null) {
            long remaining = Math.max(1, (jwtService.parseToken(token).getPayload().getExpiration().getTime()
                    - System.currentTimeMillis()) / 1000);
            tokenCacheService.addToBlacklist(token, remaining);
        }
        tokenCacheService.removeFromWhitelist(principal.getId());
        menuCacheService.evict(principal.getId());
        securityAuditService.recordAuthentication(request, principal.getId(),
                "TOKEN_REVOKED", "SUCCESS", null);
        return Result.success("已安全退出");
    }
}
