package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizEquipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * HVAC 设备台账的 MySQL 持久化入口。
 *
 * <p>设备服务使用 MyBatis-Plus 完成台账读写，并通过自定义历史编码查询为新设备
 * 分配不复用的建筑内编号。该接口不校验设备与空间、系统分组的关系，
 * 也不访问设备测点或 TDengine 时序数据。</p>
 */
@Mapper
public interface BizEquipmentMapper extends BaseMapper<BizEquipment> {

    /**
     * 编号分配必须包含逻辑删除记录，确保已退役设备编码永不复用。
     * 自定义SQL不会被MyBatis-Plus逻辑删除条件过滤。
     */
    @Select("""
            SELECT equip_code
            FROM biz_equipment
            WHERE building_id = #{buildingId}
              AND type_code = #{typeCode}
            """)
    List<String> selectHistoricalCodes(
            @Param("buildingId") String buildingId,
            @Param("typeCode") String typeCode);
}
