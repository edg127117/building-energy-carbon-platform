package com.platform.generator;

import com.platform.framework.exception.BusinessException;
import com.platform.generator.metadata.JavaTypeMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 V1 数据库类型白名单及未知类型的失败策略。 */
class JavaTypeMapperTest {
    private final JavaTypeMapper mapper = new JavaTypeMapper();

    /** 常用 MySQL/H2 类型应稳定映射，未设计支持的 geometry 不得静默生成。 */
    @Test
    void should_map_supported_mysql_types_and_reject_unknown_type() {
        assertThat(mapper.map("decimal(12,4)")).isEqualTo("java.math.BigDecimal");
        assertThat(mapper.map("BIGINT unsigned")).isEqualTo("java.lang.Long");
        assertThat(mapper.map("varchar(255)")).isEqualTo("java.lang.String");
        assertThat(mapper.map("timestamp")).isEqualTo("java.util.Date");
        assertThatThrownBy(() -> mapper.map("geometry"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("geometry");
    }
}
