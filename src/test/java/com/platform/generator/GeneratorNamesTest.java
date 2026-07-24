package com.platform.generator;

import com.platform.framework.exception.BusinessException;
import com.platform.generator.metadata.GeneratorNames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证命名转换结果以及 Java/包名注入防护。 */
class GeneratorNamesTest {
    /** 合法下划线名称应正确转换，危险包名和 Java 关键字必须被拒绝。 */
    @Test
    void should_convert_and_validate_names() {
        assertThat(GeneratorNames.toCamelCase("rated_power")).isEqualTo("ratedPower");
        assertThat(GeneratorNames.toPascalCase("biz_equipment")).isEqualTo("BizEquipment");
        assertThat(GeneratorNames.packagePath("com.platform")).isEqualTo("com/platform");
        assertThatThrownBy(() -> GeneratorNames.requirePackageName("com.platform;drop"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> GeneratorNames.requireJavaIdentifier("class", "类名"))
                .isInstanceOf(BusinessException.class);
    }
}
