package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    public Optional<FormulaCalculation.Input> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<String, FormulaCalculation.Input> asMap() {
        return values;
    }
}
