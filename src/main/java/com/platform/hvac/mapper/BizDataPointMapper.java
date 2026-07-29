package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BizDataPointMapper extends BaseMapper<BizDataPoint> {

    /**
     * 锁定典型值版本共同所属的父测点。
     *
     * <p>创建版本和批准配置都先通过父测点串行化同一测点的操作，否则仅锁不同配置行仍可能
     * 并发产生重复版本或有效期重叠。</p>
     */
    @Select("""
            SELECT *
            FROM biz_data_point
            WHERE point_id = #{pointId}
              AND del_flag = 0
            FOR UPDATE
            """)
    BizDataPoint selectByIdForUpdate(@Param("pointId") String pointId);
}
