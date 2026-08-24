package com.platform.iot.collection.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_data_source")
/** MySQL 中可信北向来源的业务档案；它不保存 Broker 凭据或南向协议参数。 */
public class BizDataSource {
    @TableId(type = IdType.INPUT)
    private String sourceId;
    private String sourceCode;
    private String sourceName;
    private String buildingId;
    private String sourceCategory;
    private String transportType;
    private String status;
    private String description;
    private Integer configRevision;
    private Long runtimeRevision;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
