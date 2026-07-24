package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.BuildingAccessRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 建筑访问申请的数据访问接口。 */
@Mapper
public interface BuildingAccessRequestMapper extends BaseMapper<BuildingAccessRequest> {
    /**
     * 在审核事务中锁定一条申请，防止多个管理员并发重复处理同一申请。
     * 调用方必须在 {@code @Transactional} 方法内使用。
     */
    @Select("SELECT * FROM sys_building_access_request WHERE id = #{id} FOR UPDATE")
    BuildingAccessRequest selectByIdForUpdate(Long id);
}
