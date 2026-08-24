package com.platform.iot.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.collection.model.entity.BizCollectionPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
/** 采集策略稳定身份及活动/草稿指针的 MySQL 持久化入口。 */
public interface BizCollectionPolicyMapper extends BaseMapper<BizCollectionPolicy> {
    @Select("SELECT * FROM biz_collection_policy WHERE policy_id=#{policyId} FOR UPDATE")
    BizCollectionPolicy selectByIdForUpdate(@Param("policyId") String policyId);

    @Select("SELECT * FROM biz_collection_policy WHERE alias_id=#{aliasId} FOR UPDATE")
    BizCollectionPolicy selectByAliasIdForUpdate(@Param("aliasId") String aliasId);

    /** 显式写入两个指针，允许把草稿指针原子清空；MyBatis 默认会忽略普通 null 字段。 */
    @Update("""
            UPDATE biz_collection_policy
            SET active_version_id=#{activeVersionId}, draft_version_id=#{draftVersionId},
                update_time=#{updateTime}
            WHERE policy_id=#{policyId}
            """)
    int updatePointers(@Param("policyId") String policyId,
                       @Param("activeVersionId") String activeVersionId,
                       @Param("draftVersionId") String draftVersionId,
                       @Param("updateTime") LocalDateTime updateTime);
}
