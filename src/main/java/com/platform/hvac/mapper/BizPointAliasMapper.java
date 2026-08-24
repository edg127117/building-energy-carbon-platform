package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizPointAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/**
 * 外部测点别名的 MySQL 持久化入口。
 *
 * <p>测点配置提供者从这里加载启用的“来源系统 + 来源编码”映射，供 MQTT 接入链
 * 将设备报文中的地址解析为平台标准测点。Mapper 不解析 MQTT 报文、不校验时序值，
 * 也不写入 TDengine。</p>
 */
public interface BizPointAliasMapper extends BaseMapper<BizPointAlias> {
    @Select("SELECT * FROM biz_point_alias WHERE alias_id=#{aliasId} FOR UPDATE")
    BizPointAlias selectByIdForUpdate(@Param("aliasId") String aliasId);

    @Update("""
            UPDATE biz_point_alias
            SET source_point_code=#{sourcePointCode}, point_id=#{pointId}, revision=revision+1,
                update_by=#{operatorId}, update_time=CURRENT_TIMESTAMP(3)
            WHERE alias_id=#{aliasId} AND revision=#{expectedRevision} AND status=2
            """)
    int updateDraft(@Param("aliasId") String aliasId,
                    @Param("sourcePointCode") String sourcePointCode,
                    @Param("pointId") String pointId,
                    @Param("operatorId") Long operatorId,
                    @Param("expectedRevision") Integer expectedRevision);
}
