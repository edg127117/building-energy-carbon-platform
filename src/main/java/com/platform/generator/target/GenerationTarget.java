package com.platform.generator.target;

import com.platform.generator.model.meta.GeneratorMetadata.GenerationContext;

import java.util.Map;

/**
 * 可插拔的代码输出目标。
 *
 * <p>输入统一的 {@link GenerationContext}，输出相对路径与文本内容。V1 实现 Java ZIP，
 * 后续可以增加前端代码、SQL 或其他语言目标，而不改变配置导入服务。</p>
 */
public interface GenerationTarget {
    /** 输出模式的稳定标识，用于持久化配置和目标选择。 */
    String mode();

    /** 在内存中生成全部文件；实现不得直接覆盖工作区源码。 */
    Map<String, String> generate(GenerationContext context);
}
