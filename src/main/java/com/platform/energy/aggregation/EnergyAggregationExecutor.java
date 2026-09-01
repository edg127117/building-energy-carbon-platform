package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationModels.AggregationInput;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationResult;
import org.springframework.stereotype.Service;

import static com.platform.energy.aggregation.EnergyAggregationErrors.INPUT_INCOMPLETE;
import static com.platform.energy.aggregation.EnergyAggregationErrors.error;

@Service
/** 从指定输入端口取得一次冻结快照，再交给不访问任何存储的聚合核心。 */
public class EnergyAggregationExecutor {
    private final EnergyAggregationCore core;

    public EnergyAggregationExecutor(EnergyAggregationCore core) {
        this.core = core;
    }

    public AggregationResult execute(AggregationQuery query, EnergyAggregationInputPort inputPort) {
        if (query == null || inputPort == null) {
            throw error(INPUT_INCOMPLETE, "聚合查询或输入端口不能为空");
        }
        AggregationInput input = inputPort.load(query);
        if (input == null || !query.equals(input.query())) {
            throw error(INPUT_INCOMPLETE, "聚合输入快照与请求范围不一致");
        }
        return core.aggregate(input);
    }
}
