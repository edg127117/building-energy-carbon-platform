package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/**
 * 用户数据访问接口。
 *
 * <p>除 MyBatis-Plus 常规能力外，提供可绕过逻辑删除过滤的安全管理查询，
 * 用于用户名保留、删除账号详情和恢复流程。</p>
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    /** 同账号登录和安全变更共用行锁；必须在事务内调用，读取包括已删除账号。 */
    @Select("SELECT * FROM sys_user WHERE id=#{id} FOR UPDATE")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    SysUser lockById(Long id);

    /** 只读取鉴权所需字段，不把密码等资料带入每次请求的权威校验。 */
    @Select("SELECT id,status,del_flag,activation_pending,security_version FROM sys_user WHERE id=#{id}")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    SysUser selectSecurityState(Long id);

    @Update("UPDATE sys_user SET security_version=security_version+1, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND security_version < 9223372036854775807")
    int incrementSecurityVersion(Long id);

    @Update("UPDATE sys_user SET nickname=#{nickname}, phone=#{phone}, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND del_flag=0")
    int updateProfile(@Param("id") Long id, @Param("nickname") String nickname, @Param("phone") String phone);

    @Update("UPDATE sys_user SET password=#{password}, update_time=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 按用户名查询账号，包含逻辑删除数据。 */
    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectAnyByUsername(String username);

    /** 按主键查询账号，包含逻辑删除数据。 */
    @Select("SELECT * FROM sys_user WHERE id = #{id}")
    SysUser selectAnyById(Long id);

    /** 恢复逻辑删除账号；尚未设置初始密码的账号继续保持停用。 */
    @Update("UPDATE sys_user SET del_flag=0, status=CASE WHEN activation_pending=1 THEN 0 ELSE 1 END, "
            + "update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND del_flag=1")
    int restore(Long id);

    /** 执行逻辑删除并同时禁用账号。 */
    @Update("UPDATE sys_user SET del_flag=1, status=0, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND del_flag=0")
    int logicalDelete(Long id);

    /** 统计有效 PLATFORM_ADMIN，用于保护系统最后一个平台管理员。 */
    @Select("""
            SELECT COUNT(DISTINCT u.id) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.status=1 AND u.del_flag=0 AND r.status=1 AND r.role_key='PLATFORM_ADMIN'
            """)
    long countActivePlatformAdmins();

    /** 查询全部未删除用户 ID，用于批量清理动态菜单缓存。 */
    @Select("SELECT id FROM sys_user WHERE del_flag=0")
    List<Long> selectActiveUserIds();

    /** 查询包含逻辑删除账号的人员列表，供管理端特殊分页使用。 */
    @Select("SELECT * FROM sys_user ORDER BY create_time DESC")
    List<SysUser> selectAllIncludingDeleted();
}
