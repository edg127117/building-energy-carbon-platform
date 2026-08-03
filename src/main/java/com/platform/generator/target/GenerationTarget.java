package com.platform.generator.target;

import com.platform.generator.model.meta.GeneratorMetadata.GenerationContext;

import java.util.Map;

/**
 * 可插拔的代码输出目标。
 *
 * <p>输入经过服务层完整校验的 {@link GenerationContext}，输出“安全相对路径到文本内容”
 * 的内存集合。输出目标只负责选择模板和组装文件，不读取数据库，也不得覆盖工作区源码。</p>
 */
public interface GenerationTarget {
    /** 输出模式的稳定标识，用于持久化配置和目标选择。 */
    String mode();

    /** 在内存中生成全部文件；实现不得直接覆盖工作区源码。 */
    Map<String, String> generate(GenerationContext context);
}
