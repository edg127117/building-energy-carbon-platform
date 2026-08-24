package com.platform.iot.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.collection.model.entity.BizDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/** 数据源档案、修订控制与行锁查询的 MySQL 持久化入口。 */
public interface BizDataSourceMapper extends BaseMapper<BizDataSource> {
    @Select("SELECT * FROM biz_data_source WHERE source_id=#{sourceId} FOR UPDATE")
    BizDataSource selectByIdForUpdate(@Param("sourceId") String sourceId);

    @Update("""
            UPDATE biz_data_source
            SET source_name=#{sourceName}, description=#{description},
                config_revision=config_revision+1, update_by=#{operatorId}, update_time=CURRENT_TIMESTAMP(3)
            WHERE source_id=#{sourceId} AND config_revision=#{expectedRevision} AND status='DRAFT'
            """)
    int updateDraft(@Param("sourceId") String sourceId,
                    @Param("sourceName") String sourceName,
                    @Param("description") String description,
                    @Param("operatorId") Long operatorId,
                    @Param("expectedRevision") Integer expectedRevision);
}
