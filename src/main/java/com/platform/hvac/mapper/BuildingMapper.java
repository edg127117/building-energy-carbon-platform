package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.Building;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 建筑档案的 MySQL 持久化入口。
 *
 * <p>除 MyBatis-Plus 提供的基础档案访问外，人工数据质量重算受理会通过本接口
 * 锁定建筑父记录，使同一建筑内“检查重叠范围并创建批次”形成串行边界。该接口
 * 不执行重算业务，也不访问 TDengine，任务编排仍由数据质量 Service 负责。</p>
 */
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
