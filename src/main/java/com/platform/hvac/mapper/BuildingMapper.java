package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.Building;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BuildingMapper extends BaseMapper<Building> {

    /**
     * 锁定未删除的建筑行，使同一建筑的重叠重算检查和任务创建串行执行。
     */
    @Select("""
            SELECT *
            FROM building
            WHERE building_id=#{buildingId}
              AND del_flag=0
            FOR UPDATE
            """)
    Building selectExistingForUpdate(@Param("buildingId") String buildingId);
}
