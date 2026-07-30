package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.cache.TokenCacheService;
import com.platform.cache.MenuCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.security.JwtService;
import com.platform.security.FormalRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.dto.RegisterRequest;
import com.platform.system.model.entity.SysRole;
import com.platform.system.model.entity.SysUser;
import com.platform.system.model.entity.SysUserRole;
import com.platform.system.service.SysRoleService;
import com.platform.system.service.BuildingScopeService;
import com.platform.system.service.SysUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 面向公开认证接口的注册与登录服务。
 *
 * <p>注册账号固定获得最低权限 {@code BUILDING_OWNER}，但不会自动获得任何建筑。
 * 登录时只把四类正式角色写入 JWT；历史普通角色账号必须由平台管理员重新分配正式角色。</p>
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenCacheService tokenCacheService;
    private final SysRoleService sysRoleService;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final MenuCacheService menuCacheService;
    private final BuildingScopeService buildingScopeService;

    public SysUserServiceImpl(
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenCacheService tokenCacheService,
            SysRoleService sysRoleService,
            SysRoleMapper sysRoleMapper,
            SysUserRoleMapper sysUserRoleMapper,
            MenuCacheService menuCacheService,
            BuildingScopeService buildingScopeService
    ) {
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenCacheService = tokenCacheService;
        this.sysRoleService = sysRoleService;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.menuCacheService = menuCacheService;
        this.buildingScopeService = buildingScopeService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        // 用户名对逻辑删除账号仍保持唯一，避免旧账号恢复后产生身份冲突。
        SysUser exists = baseMapper.selectAnyByUsername(request.getUsername());
        if (exists != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        // 2) 保存用户基础信息（密码使用 BCrypt）
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setStatus(1);
        user.setDelFlag(0);

        this.save(user);

        // 注册用户固定绑定最低权限的建筑业主角色，不自动授予建筑范围。
        SysRole role = sysRoleService.ensureRole(FormalRole.BUILDING_OWNER.name(), "建筑业主");
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        sysUserRoleMapper.insert(userRole);
        // 防止数据库重建、数据恢复等场景中复用用户 ID 后命中旧缓存。
        menuCacheService.evict(user.getId());
        buildingScopeService.evict(user.getId());
    }

    @Override
    public LoginResponse login(String username, String password) {
        // 1) 查用户
        SysUser user = this.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 2) 校验密码（兼容初始化脚本里“明文密码”的历史数据，首次登录成功后自动升级为 BCrypt）
        boolean ok;
        String stored = user.getPassword();
        if (stored != null && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"))) {
            ok = passwordEncoder.matches(password, stored);
        } else {
            ok = stored != null && stored.equals(password);
            if (ok) {
                user.setPassword(passwordEncoder.encode(password));
                this.updateById(user);
            }
        }

        if (!ok) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (user.getDelFlag() != null && user.getDelFlag() == 1) {
            throw new BusinessException(403, "账号已被删除");
        }

        // 初始化脚本直接给内置管理员绑定正式角色；登录不再修复旧角色数据。
        List<String> roleKeys = sysRoleMapper.selectRoleKeysByUserId(user.getId()).stream()
                .map(String::toUpperCase)
                .filter(FormalRole::isFormal)
                .distinct()
                .toList();
        if (roleKeys.isEmpty()) {
            throw new BusinessException(403, "账号尚未分配正式角色，请联系平台管理员");
        }

        // 正式角色写入 JWT claims，供 @PreAuthorize 使用；建筑权限不写入 JWT，可动态生效。
        String token = jwtService.generateToken(user.getId(), user.getUsername(), roleKeys);

        // 旁路写入 Redis 白名单（冻结书 D-010：Token 缓存，Redis 不可用时不影响登录）
        tokenCacheService.addToWhitelist(user.getId(), token);

        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpireSeconds())
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .roles(roleKeys)
                        .build())
                .build();
    }

}
