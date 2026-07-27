package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次公式计算使用的不可变语义输入集合。
 *
 * <p>键由 {@link FormulaInputAssembler} 根据标准设备身份生成，同一键只允许
 * 一个测点。构造阶段拒绝重复键，避免公式结果随集合顺序发生变化。</p>
 */
public final class FormulaInputs {

    private final Map<String, FormulaCalculation.Input> values;

    public FormulaInputs(Collection<FormulaCalculation.Input> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        LinkedHashMap<String, FormulaCalculation.Input> copy = new LinkedHashMap<>();
        for (FormulaCalculation.Input input : inputs) {
            Objects.requireNonNull(input, "input");
            if (copy.putIfAbsent(input.key(), input) != null) {
                throw new IllegalArgumentException("Duplicate formula input key: " + input.key());
            }
        }
        values = Collections.unmodifiableMap(copy);
    }

    public FormulaInputs(Map<String, FormulaCalculation.Input> inputs) {
        this(validateMap(inputs));
    }

    private static Collection<FormulaCalculation.Input> validateMap(
            Map<String, FormulaCalculation.Input> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        inputs.forEach((key, input) -> {
            Objects.requireNonNull(input, "input");
            if (!Objects.equals(key, input.key())) {
                throw new IllegalArgumentException("Map key does not match semantic input key: " + key);
            }
        });
        return inputs.values();
    }

    /** 按语义键查找输入；缺失由具体公式转换为可审计的 MISSING_INPUT。 */
    public Optional<FormulaCalculation.Input> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** 返回只读视图，用于记录实际收到的输入和生成计算详情。 */
    public Map<String, FormulaCalculation.Input> asMap() {
        return values;
    }
}
