package com.platform.energy.classification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.platform.energy.classification.ClassificationContracts.*;

/** 对已经过建筑授权和查询数量限制的人工读数附加只读提示，不参与原生量、折标或封账。 */
final class UnchangedReadingDiagnostics {
    private UnchangedReadingDiagnostics() {}

    static List<ReadingView> annotate(List<ReadingView> readings) {
        var superseded = new HashSet<String>();
        for (var row : readings) {
            if ("APPROVED".equals(row.batchStatus()) && row.reading().supersedesFactId() != null)
                superseded.add(row.reading().supersedesFactId());
        }
        Map<Key, List<ReadingView>> groups = new LinkedHashMap<>();
        for (var row : readings) {
            var r = row.reading();
            if (!"APPROVED".equals(row.batchStatus()) || superseded.contains(row.factId())) continue;
            groups.computeIfAbsent(new Key(r.pointId(), r.energyItemCode(), r.unit()), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, UnchangedReadingDiagnostic> hints = new HashMap<>();
        for (var group : groups.values()) {
            group.sort(Comparator.comparing((ReadingView v) -> v.reading().startInclusive()).thenComparing(ReadingView::factId));
            List<ReadingView> run = new ArrayList<>();
            for (var row : group) {
                if (!eligible(row.reading()) || (!run.isEmpty() && !continues(run.getLast().reading(), row.reading()))) {
                    finish(run, hints);
                    run.clear();
                }
                if (eligible(row.reading())) run.add(row);
            }
            finish(run, hints);
        }
        // 每段只挂到首条事实，来源列表总体线性增长，避免长连续区间在每行重复整个证据链。
        return readings.stream().map(r -> new ReadingView(r.factId(), r.batchId(), r.reading(),
                r.normalizedQuantity(), r.createdBy(), r.batchStatus(), hints.get(r.factId()))).toList();
    }

    private static boolean eligible(Reading r) {
        return "CONFIRMED".equals(r.status()) && "EXACT".equals(r.timePrecision())
                && "CUMULATIVE".equals(r.semantics()) && r.startReading() != null && r.endReading() != null
                && r.startReading().compareTo(r.endReading()) == 0;
    }

    private static boolean continues(Reading previous, Reading next) {
        // 缺测不补齐，重叠不拼接；同一数值也不能跨倍率/已换算口径合并。
        return previous.endExclusive().equals(next.startInclusive())
                && sameNumber(previous.endReading(), next.startReading())
                && sameNumber(previous.multiplier(), next.multiplier())
                && previous.alreadyScaled() == next.alreadyScaled();
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static void finish(List<ReadingView> run, Map<String, UnchangedReadingDiagnostic> hints) {
        if (run.isEmpty()) return;
        var first = run.getFirst();
        var reading = first.reading();
        hints.put(first.factId(), new UnchangedReadingDiagnostic(reading.pointId(), reading.energyItemCode(), reading.unit(),
                reading.startInclusive(), run.getLast().reading().endExclusive(), run.size(), reading.startReading(),
                run.stream().map(r -> new ReadingDiagnosticSource(r.factId(), r.batchId(), r.reading().sourceReference())).toList()));
    }

    private record Key(String pointId, String energyItemCode, String unit) {}
}
