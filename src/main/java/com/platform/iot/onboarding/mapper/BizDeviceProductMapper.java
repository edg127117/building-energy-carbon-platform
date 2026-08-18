package com.platform.iot.onboarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
/** 可复用设备产品接入模板的 MySQL 持久化入口。 */
public interface BizDeviceProductMapper extends BaseMapper<BizDeviceProduct> {

    /** 锁定产品版本，避免模板更新、复制和启停并发覆盖状态。 */
    @Select("SELECT * FROM biz_device_product WHERE product_id = #{productId} FOR UPDATE")
    BizDeviceProduct selectByIdForUpdate(@Param("productId") String productId);
}
