package com.platform.iot.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 测点典型值配置的 MySQL 访问入口。
 *
 * <p>审批与版本创建需要使用这里的显式行锁查询，避免 Service 自行拼接 SQL，也避免两个并发事务
 * 分别批准重叠有效期或分配出相同版本号。</p>
 */
@Mapper
public interface BizPointTypicalValueConfigMapper extends BaseMapper<BizPointTypicalValueConfig> {

    /** 锁定配置行，保证一次审批状态流转只能由一个事务完成。 */
    @Select("""
            SELECT *
            FROM biz_point_typical_value_config
            WHERE config_id = #{configId}
            FOR UPDATE
            """)
    Optional<BizPointTypicalValueConfig> selectByIdForUpdate(
            @Param("configId") String configId);

    /** 在父测点已经加锁后读取当前最大版本号。 */
    @Select("""
            SELECT COALESCE(MAX(version), 0)
            FROM biz_point_typical_value_config
            WHERE point_id = #{pointId}
            """)
    int selectMaxVersion(@Param("pointId") String pointId);

    /** 只加载批准配置，供运行时快照整体替换。 */
    @Select("""
            SELECT *
            FROM biz_point_typical_value_config
            WHERE status = 'APPROVED'
            ORDER BY point_id, valid_from, version DESC
            """)
    List<BizPointTypicalValueConfig> selectApprovedSnapshot();

    /**
     * 判断同一测点是否已有批准配置与目标半开区间重叠。
     *
     * <p>{@code valid_to IS NULL} 表示无限远；排除当前配置是为了审批自身时不产生误判。</p>
     */
    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
            FROM biz_point_typical_value_config
            WHERE point_id = #{pointId}
              AND status = 'APPROVED'
              AND (#{validTo} IS NULL OR valid_from < #{validTo})
              AND (valid_to IS NULL OR valid_to > #{validFrom})
              AND (#{excludedConfigId} IS NULL OR config_id <> #{excludedConfigId})
            """)
    boolean existsApprovedOverlap(
            @Param("pointId") String pointId,
            @Param("validFrom") LocalDateTime validFrom,
            @Param("validTo") LocalDateTime validTo,
            @Param("excludedConfigId") String excludedConfigId);
}
