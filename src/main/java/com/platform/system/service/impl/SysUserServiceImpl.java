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
 * <p>上游是公开认证 Controller，下游写入 MySQL 用户/角色关系并调用 JWT、Redis 登录态服务。
 * 注册账号固定获得 {@code BUILDING_OWNER}，但不会自动获得任何建筑。登录只把四类正式角色
 * 写入 JWT；建筑范围保持在 MySQL/Redis 范围服务中，避免每次授权变化都要求重新签发 Token。</p>
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

    /**
     * 在一个 MySQL 事务中创建账号并绑定默认角色。
     *
     * <p>用户名对逻辑删除记录仍保持唯一；成功后清理同 ID 菜单和建筑范围缓存，防止数据库
     * 恢复或主键复用时继承旧权限。注册不接受客户端角色和建筑字段。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        // 用户名对逻辑删除账号仍保持唯一，避免旧账号恢复后产生身份冲突。
        SysUser exists = baseMapper.selectAnyByUsername(request.getUsername());
        if (exists != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        // 密码只以 BCrypt 哈希写入 MySQL，响应和日志均不回传密码字段。
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

    /**
     * 校验 MySQL 账号状态、密码和正式角色并签发登录结果。
     *
     * <p>初始化数据中的历史明文密码仅在本次校验成功后升级为 BCrypt。角色列表写入 JWT，
     * Token 随后覆盖 Redis 白名单，从而使同账号上一枚 Token 失效。Redis 写入失败不阻断
     * 登录，但会进入仅校验 JWT 的降级状态。</p>
     */
    @Override
    public LoginResponse login(String username, String password) {
        // 默认查询会排除逻辑删除账号，避免向外区分“用户不存在”和“密码错误”。
        SysUser user = this.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 兼容初始化脚本中的历史明文；只有匹配成功才原地升级，失败时不改数据库。
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

        // 写入单账号白名单；Redis 故障由缓存服务记录并按可用性优先策略降级。
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
