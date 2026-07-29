package com.platform.iot.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
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

    /**
     * 在 MySQL 中完成典型值配置的建筑范围、状态和有效期过滤后再分页。
     *
     * <p>{@code allBuildings} 只允许平台管理员传入 true；普通角色没有建筑授权时显式追加
     * {@code 1 = 0}，不能省略条件后误查全量。有效期按半开区间重叠语义过滤。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM biz_point_typical_value_config
            WHERE 1 = 1
            <if test="allBuildings == false">
              <choose>
                <when test="buildingIds != null and !buildingIds.isEmpty()">
                  AND building_id IN
                  <foreach collection="buildingIds" item="buildingId"
                           open="(" separator="," close=")">
                    #{buildingId}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </if>
            <if test="buildingId != null and buildingId != ''">
              AND building_id = #{buildingId}
            </if>
            <if test="pointId != null and pointId != ''">
              AND point_id = #{pointId}
            </if>
            <if test="status != null">
              AND status = #{status}
            </if>
            <if test="validFrom != null">
              AND (valid_to IS NULL OR valid_to &gt; #{validFrom})
            </if>
            <if test="validTo != null">
              AND valid_from &lt; #{validTo}
            </if>
            ORDER BY create_time DESC, config_id DESC
            </script>
            """)
    IPage<BizPointTypicalValueConfig> selectPageFiltered(
            IPage<BizPointTypicalValueConfig> page,
            @Param("allBuildings") boolean allBuildings,
            @Param("buildingIds") Collection<String> buildingIds,
            @Param("buildingId") String buildingId,
            @Param("pointId") String pointId,
            @Param("status") String status,
            @Param("validFrom") LocalDateTime validFrom,
            @Param("validTo") LocalDateTime validTo);

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
