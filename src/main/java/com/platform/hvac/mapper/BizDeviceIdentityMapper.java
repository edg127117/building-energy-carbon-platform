package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
/** 设备外部身份预注册关系的 MySQL 持久化入口。 */
public interface BizDeviceIdentityMapper extends BaseMapper<BizDeviceIdentity> {

    /** 锁定身份启停状态，避免并发操作覆盖最终结果。 */
    @Select("SELECT * FROM biz_device_identity WHERE identity_id = #{identityId} FOR UPDATE")
    BizDeviceIdentity selectByIdForUpdate(@Param("identityId") String identityId);
}
