package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysUserBuilding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户建筑授权表的数据访问接口。 */
@Mapper
public interface SysUserBuildingMapper extends BaseMapper<SysUserBuilding> {
    /** 查询用户当前拥有的全部建筑 ID，供建筑范围服务加载权限。 */
    @Select("SELECT building_id FROM sys_user_building WHERE user_id = #{userId}")
    List<String> selectBuildingIdsByUserId(Long userId);
}
