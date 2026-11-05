package com.platform.alarm;

/** 注册的模板提供者复用只读来源契约，只返回已确认并完成自身指标计算的metric。 */
public interface AlarmTemplateProvider extends AlarmEmissionSource {
    AlarmTemplateCatalog.Descriptor descriptor();

    @Override
    default boolean supports(AlarmModels.EmissionSettings settings) {
        return settings != null && descriptor() != null && descriptor().code().equals(settings.template());
    }
}
