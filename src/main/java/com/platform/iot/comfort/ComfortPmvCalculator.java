package com.platform.iot.comfort;

import java.util.OptionalDouble;

/** ISO 7730 热平衡方程。输入为 °C、m/s、百分数 RH、met 和 clo。 */
final class ComfortPmvCalculator {
    private static final double WATTS_PER_MET = 58.15;
    private static final double INSULATION_PER_CLO = 0.155;

    private ComfortPmvCalculator() {}

    static OptionalDouble calculate(double airC, double radiantC, double speed, double humidity,
                                    double met, double clo) {
        if (!between(airC, 10, 30) || !between(radiantC, 10, 40) || !between(speed, 0, 1)
                || !between(humidity, 0, 100) || !between(met, 0.8, 4) || !between(clo, 0, 2)) {
            return OptionalDouble.empty();
        }
        double vaporPressure = humidity * 10 * Math.exp(16.6536 - 4030.183 / (airC + 235));
        if (vaporPressure > 2700) return OptionalDouble.empty();

        // 办公场景的外部机械功 W 取 0；配置的风速作为模型的相对风速输入。
        double metabolism = met * WATTS_PER_MET;
        double insulation = clo * INSULATION_PER_CLO;
        double area = insulation <= 0.078 ? 1 + 1.29 * insulation : 1.05 + 0.645 * insulation;
        double forcedConvection = 12.1 * Math.sqrt(speed);
        double skinC = 35.7 - 0.028 * metabolism;
        double low = Math.min(skinC, Math.min(airC, radiantC));
        double high = Math.max(skinC, Math.max(airC, radiantC));

        // 衣表温度的热平衡残差单调，根在皮肤、空气和平均辐射温度的最小值与最大值之间。
        for (int step = 0; step < 40; step++) {
            double clothingC = (low + high) / 2;
            double residual = clothingC - skinC + insulation * dryLoss(clothingC, airC, radiantC, area, forcedConvection);
            if (residual > 0) high = clothingC;
            else low = clothingC;
        }
        double skinDiffusion = 0.00305 * (5733 - 6.99 * metabolism - vaporPressure);
        double sweating = 0.42 * Math.max(0, metabolism - WATTS_PER_MET);
        double latentRespiration = 0.000017 * metabolism * (5867 - vaporPressure);
        double dryRespiration = 0.0014 * metabolism * (34 - airC);
        double heatLoad = metabolism - skinDiffusion - sweating - latentRespiration - dryRespiration
                - dryLoss((low + high) / 2, airC, radiantC, area, forcedConvection);
        double pmv = (0.303 * Math.exp(-0.036 * metabolism) + 0.028) * heatLoad;
        return between(pmv, -2, 2) ? OptionalDouble.of(pmv) : OptionalDouble.empty();
    }

    private static boolean between(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private static double dryLoss(double clothingC, double airC, double radiantC, double area, double forcedConvection) {
        double convection = Math.max(forcedConvection, 2.38 * Math.pow(Math.abs(clothingC - airC), 0.25));
        double radiation = 3.96e-8 * (Math.pow(clothingC + 273, 4) - Math.pow(radiantC + 273, 4));
        return area * (radiation + convection * (clothingC - airC));
    }
}
