package com.platform.system.controller;

import com.platform.framework.common.Result;
import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.security.JwtService;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.model.dto.LoginRequest;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.dto.RegisterRequest;
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
 * 登录/注册相关接口
 * 约定：登录成功后前端保存 token，并在后续请求头携带 Authorization: Bearer <token>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysUserService sysUserService;
    private final JwtService jwtService;
    private final TokenCacheService tokenCacheService;
    private final MenuCacheService menuCacheService;

    public AuthController(SysUserService sysUserService, JwtService jwtService,
                          TokenCacheService tokenCacheService, MenuCacheService menuCacheService) {
        this.sysUserService = sysUserService;
        this.jwtService = jwtService;
        this.tokenCacheService = tokenCacheService;
        this.menuCacheService = menuCacheService;
    }

    /**
     * 用户注册（默认授予 BUILDING_OWNER，不授予建筑）
     */
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest request) {
        sysUserService.register(request);
        return Result.success("注册成功");
    }

    /**
     * 用户登录（返回 JWT）
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = sysUserService.login(request.getUsername(), request.getPassword());
        return Result.success(response);
    }

    /**
     * 获取当前登录用户信息（用于前端判断“是谁 + 有什么角色”）
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
        return Result.success("已安全退出");
    }
}
