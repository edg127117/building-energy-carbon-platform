package com.platform.alarm;

import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.alarm.AlarmModels.*;

/** 前后端共用的模板能力目录；自定义算法只能由受控Java provider注册。 */
@Component
public class AlarmTemplateCatalog {
    private static final List<String> MONTH_YEAR = List.of("MONTH", "YEAR");
    private static final List<Descriptor> BUILT_INS = List.of(
            descriptor("QUOTA_PROGRESS", "配额进度", MONTH_YEAR, "%", "QUOTA",
                    List.of("period", "threshold", "recoveryThreshold"), List.of("待专业确认：计划配额及排放边界")),
            descriptor("INTENSITY", "排放强度", MONTH_YEAR, "正式来源单位", "INTENSITY",
                    List.of("period", "threshold", "recoveryThreshold"), List.of("待专业确认：强度公式、排放单位及面积版本")),
            descriptor("MONTH_ON_MONTH", "环比变化", MONTH_YEAR, "tCO2e或%", "MOM",
                    List.of("period", "changeMode", "threshold", "recoveryThreshold"), List.of("待专业确认：月度边界和可比批次")),
            descriptor("YEAR_ON_YEAR", "同比变化", MONTH_YEAR, "tCO2e或%", "YOY",
                    List.of("period", "changeMode", "threshold", "recoveryThreshold"), List.of("待专业确认：年度边界和可比批次")),
            descriptor("BASELINE", "基线偏差", MONTH_YEAR, "tCO2e或%", "BASELINE",
                    List.of("period", "changeMode", "baselineModelId", "threshold", "recoveryThreshold"), List.of("待专业确认：基线模型审批及生效状态")),
            descriptor("COMPLETENESS", "核算完整性", MONTH_YEAR, "%", "COMPLETENESS",
                    List.of("period", "threshold", "recoveryThreshold"), List.of("待专业确认：完整性规则及缺失因子事实")),
            descriptor("YEAR_END_FORECAST", "年末预测", List.of("YEAR"), "%", "FORECAST",
                    List.of("period", "forecastMethod", "threshold", "recoveryThreshold"), List.of("仅在专业方法确认后使用LINEAR_DAY和年度配额"))
    );

    private static final Set<String> BUILT_IN_CODES = BUILT_INS.stream().map(Descriptor::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EVENT_TYPES = Set.of("QUOTA", "INTENSITY", "MOM", "YOY", "BASELINE", "COMPLETENESS", "FORECAST");
    private static final Set<String> RESERVED_FIELDS = Set.of("template", "period", "threshold", "recoveryThreshold", "changeMode", "baselineModelId", "forecastMethod");
    private static final java.math.BigDecimal MAX_PARAMETER = java.math.BigDecimal.TEN.pow(30);
    private final List<AlarmTemplateProvider> providers;

    public AlarmTemplateCatalog(List<AlarmTemplateProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public List<Descriptor> list() {
        List<Descriptor> descriptors = new ArrayList<>(BUILT_INS);
        Set<String> codes = new HashSet<>(BUILT_IN_CODES);
        for (AlarmTemplateProvider provider : providers) {
            Descriptor descriptor = provider == null ? null : provider.descriptor();
            validateDescriptor(descriptor);
            if (!codes.add(descriptor.code())) throw new IllegalStateException("重复报警模板编码：" + descriptor.code());
            descriptors.add(descriptor);
        }
        return List.copyOf(descriptors);
    }

    /** 主Service在保存EMISSION规则时使用，阻止客户端绕过目录提交未注册算法。 */
    public void validate(RuleRequest request) {
        if (request == null || !"EMISSION".equals(request.type())) throw new BusinessException("规则类型无效");
        EmissionSettings settings = request.advanced() == null ? null : request.advanced().emission();
        if (settings == null || settings.template() == null) throw new BusinessException("排放规则模板无效");
        if (BUILT_IN_CODES.contains(settings.template())) {
            AlarmEmissionEvaluator.validate(request);
            return;
        }
        List<AlarmTemplateProvider> registered = providers.stream().filter(provider -> provider.descriptor() != null
                && settings.template().equals(provider.descriptor().code())).toList();
        if (registered.size() != 1) throw new BusinessException("排放规则模板未注册或存在重复注册");
        Descriptor descriptor = registered.getFirst().descriptor();
        try { validateDescriptor(descriptor); }
        catch (IllegalStateException invalid) { throw new BusinessException("排放模板provider描述无效"); }
        if (settings.period() == null || !descriptor.periods().contains(settings.period())) throw new BusinessException("排放规则周期不受此模板支持");
        if (settings.threshold() == null || settings.recoveryThreshold() == null || settings.threshold().signum() < 0
                || settings.recoveryThreshold().signum() < 0 || settings.recoveryThreshold().compareTo(settings.threshold()) > 0) {
            throw new BusinessException("排放阈值或恢复阈值无效");
        }
        for (String field : descriptor.requiredFields()) {
            if (!hasRequiredValue(settings, field)) throw new BusinessException("缺少模板必填项：" + field);
        }
        Map<String, java.math.BigDecimal> parameters = settings.parameters() == null ? Map.of() : settings.parameters();
        Set<String> declaredParameters = descriptor.requiredFields().stream().filter(field -> field.startsWith("parameters."))
                .map(field -> field.substring("parameters.".length())).collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, java.math.BigDecimal> entry : parameters.entrySet()) {
            try { validateParameterKey(entry.getKey()); }
            catch (IllegalArgumentException invalid) { throw new BusinessException("模板数值参数名无效"); }
            java.math.BigDecimal value = entry.getValue();
            if (value == null || value.precision() > 38 || value.scale() > 12 || value.abs().compareTo(MAX_PARAMETER) > 0) {
                throw new BusinessException("模板数值参数超出允许范围：" + entry.getKey());
            }
            if (!declaredParameters.contains(entry.getKey())) throw new BusinessException("模板包含未声明的数值参数：" + entry.getKey());
        }
    }

    AlarmTemplateProvider provider(String code) {
        List<AlarmTemplateProvider> registered = providers.stream().filter(provider -> provider.descriptor() != null
                && code.equals(provider.descriptor().code())).toList();
        return registered.size() == 1 ? registered.getFirst() : null;
    }

    static boolean isBuiltIn(String code) { return BUILT_IN_CODES.contains(code); }

    private static Descriptor descriptor(String code, String label, List<String> periods, String unit, String eventType,
            List<String> fields, List<String> professionalInputs) {
        return new Descriptor(code, label, periods, unit, fields, professionalInputs, eventType, java.util.Map.of());
    }

    private static void validateDescriptor(Descriptor descriptor) {
        if (descriptor == null || blank(descriptor.code()) || !descriptor.code().matches("[A-Z][A-Z0-9_]{0,31}")
                || BUILT_IN_CODES.contains(descriptor.code()) || blank(descriptor.label()) || descriptor.label().length() > 100
                || blank(descriptor.unit()) || descriptor.unit().length() > 40 || descriptor.periods() == null || descriptor.periods().isEmpty()
                || descriptor.requiredFields() == null || descriptor.professionalInputs() == null
                || descriptor.eventType() == null || !EVENT_TYPES.contains(descriptor.eventType()) || descriptor.periods().stream().anyMatch(p -> p == null || !Set.of("MONTH", "YEAR").contains(p))
                || new HashSet<>(descriptor.periods()).size() != descriptor.periods().size()
                || descriptor.requiredFields().stream().anyMatch(field -> !validRequiredField(field))
                || new HashSet<>(descriptor.requiredFields()).size() != descriptor.requiredFields().size()
                || descriptor.professionalInputs().stream().anyMatch(input -> blank(input) || input.length() > 200)
                || descriptor.parameterLabels().keySet().stream().anyMatch(key -> !declaredParameterKeys(descriptor).contains(key))
                || !declaredParameterKeys(descriptor).equals(descriptor.parameterLabels().keySet())
                || descriptor.parameterLabels().entrySet().stream().anyMatch(entry -> blank(entry.getValue())
                || entry.getValue().length() > 40 || !entry.getValue().matches(".*[\\u4e00-\\u9fff].*"))) {
            throw new IllegalStateException("报警模板provider描述无效");
        }
    }

    private static Set<String> declaredParameterKeys(Descriptor descriptor) {
        return descriptor.requiredFields().stream().filter(field -> field != null && field.startsWith("parameters."))
                .map(field -> field.substring("parameters.".length())).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean validRequiredField(String field) {
        if (field == null) return false;
        if (RESERVED_FIELDS.contains(field)) return true;
        if (!field.startsWith("parameters.")) return false;
        try { validateParameterKey(field.substring("parameters.".length())); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static void validateParameterKey(String key) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
                || Set.of("__proto__", "prototype", "constructor").contains(key) || RESERVED_FIELDS.contains(key)) {
            throw new IllegalArgumentException("危险或保留模板参数名");
        }
    }

    private static boolean hasRequiredValue(EmissionSettings settings, String field) {
        if (field == null || field.isBlank()) return false;
        if (field.startsWith("parameters.")) {
            return settings.parameters() != null && settings.parameters().get(field.substring("parameters.".length())) != null;
        }
        return switch (field) {
            case "period" -> settings.period() != null && !settings.period().isBlank();
            case "threshold" -> settings.threshold() != null;
            case "recoveryThreshold" -> settings.recoveryThreshold() != null;
            case "changeMode" -> settings.changeMode() != null && !settings.changeMode().isBlank();
            case "baselineModelId" -> settings.baselineModelId() != null && !settings.baselineModelId().isBlank();
            case "forecastMethod" -> settings.forecastMethod() != null && !settings.forecastMethod().isBlank();
            default -> false;
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    /** eventType沿用平台现有事件分类，避免provider增加不可识别的事件状态或HTTP枚举。 */
    public record Descriptor(String code, String label, List<String> periods, String unit,
            List<String> requiredFields, List<String> professionalInputs, String eventType, Map<String, String> parameterLabels) {
        public Descriptor(String code, String label, List<String> periods, String unit, List<String> requiredFields,
                List<String> professionalInputs, String eventType) {
            this(code, label, periods, unit, requiredFields, professionalInputs, eventType, Map.of());
        }
        public Descriptor {
            periods = periods == null ? List.of() : List.copyOf(periods);
            requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
            professionalInputs = professionalInputs == null ? List.of() : List.copyOf(professionalInputs);
            parameterLabels = parameterLabels == null ? Map.of() : Map.copyOf(parameterLabels);
        }
    }
}
