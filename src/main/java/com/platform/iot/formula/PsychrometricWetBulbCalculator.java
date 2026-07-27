package com.platform.iot.formula;

public final class PsychrometricWetBulbCalculator {

    private static final double MIN_TEMPERATURE_C = -100.0;
    private static final double MAX_TEMPERATURE_C = 200.0;
    private static final double MIN_HUMIDITY_RATIO = 1.0e-7;
    private static final double TOLERANCE_C = 0.001;
    private static final int MAX_ITERATIONS = 100;

    public double calculate(double dryBulbC, double relativeHumidityPercent,
            double pressureKpa) {
        validate(dryBulbC, relativeHumidityPercent, pressureKpa);

        double pressurePa = pressureKpa * 1000.0;
        double relativeHumidity = relativeHumidityPercent / 100.0;
        double saturationPressureAtDryBulb = saturationVaporPressure(dryBulbC);
        double vaporPressure = relativeHumidity * saturationPressureAtDryBulb;
        if (!Double.isFinite(pressurePa) || vaporPressure >= pressurePa) {
            throw new IllegalArgumentException(
                    "Atmospheric pressure must exceed the water vapor pressure");
        }

        double humidityRatio = 0.621945 * vaporPressure / (pressurePa - vaporPressure);
        if (!Double.isFinite(humidityRatio)) {
            throw new IllegalArgumentException("Humidity ratio must be finite");
        }

        double dewPoint = bisectDewPoint(vaporPressure, dryBulbC);
        if (dryBulbC - dewPoint <= TOLERANCE_C) {
            return dryBulbC;
        }
        return bisectWetBulb(humidityRatio, dryBulbC, dewPoint, pressurePa);
    }

    private void validate(double dryBulbC, double relativeHumidityPercent,
            double pressureKpa) {
        if (!Double.isFinite(dryBulbC)
                || !Double.isFinite(relativeHumidityPercent)
                || !Double.isFinite(pressureKpa)) {
            throw new IllegalArgumentException("Psychrometric inputs must be finite");
        }
        if (dryBulbC < MIN_TEMPERATURE_C || dryBulbC > MAX_TEMPERATURE_C) {
            throw new IllegalArgumentException("Dry-bulb temperature is outside ASHRAE range");
        }
        if (relativeHumidityPercent <= 0.0 || relativeHumidityPercent > 100.0) {
            throw new IllegalArgumentException("Relative humidity must be in (0, 100]");
        }
        if (pressureKpa <= 0.0) {
            throw new IllegalArgumentException("Atmospheric pressure must be positive");
        }
    }

    private double bisectDewPoint(double vaporPressure, double dryBulbC) {
        double low = MIN_TEMPERATURE_C;
        double high = dryBulbC;
        double lowPressure = saturationVaporPressure(low);
        double highPressure = saturationVaporPressure(high);
        if (vaporPressure < lowPressure || vaporPressure > highPressure) {
            throw new IllegalArgumentException("Dew point is outside the supported range");
        }

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (high - low <= TOLERANCE_C) {
                return (low + high) / 2.0;
            }
            double middle = (low + high) / 2.0;
            if (saturationVaporPressure(middle) < vaporPressure) {
                low = middle;
            } else {
                high = middle;
            }
        }
        throw new IllegalArgumentException("Dew-point calculation did not converge");
    }

    private double bisectWetBulb(double targetHumidityRatio, double dryBulbC,
            double dewPointC, double pressurePa) {
        double low = dewPointC;
        double high = dryBulbC;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (high - low <= TOLERANCE_C) {
                return (low + high) / 2.0;
            }
            double middle = (low + high) / 2.0;
            double calculatedHumidityRatio =
                    humidityRatioFromWetBulb(dryBulbC, middle, pressurePa);
            if (calculatedHumidityRatio < targetHumidityRatio) {
                low = middle;
            } else {
                high = middle;
            }
        }
        throw new IllegalArgumentException("Wet-bulb calculation did not converge");
    }

    private double humidityRatioFromWetBulb(double dryBulb, double wetBulb, double p) {
        double saturationPressure = saturationVaporPressure(wetBulb);
        if (saturationPressure >= p) {
            throw new IllegalArgumentException(
                    "Atmospheric pressure must exceed saturation vapor pressure");
        }
        double ws = 0.621945 * saturationPressure / (p - saturationPressure);
        double ratio = wetBulb >= 0.0
                ? ((2501.0 - 2.326 * wetBulb) * ws
                        - 1.006 * (dryBulb - wetBulb))
                        / (2501.0 + 1.86 * dryBulb - 4.186 * wetBulb)
                : ((2830.0 - 0.24 * wetBulb) * ws
                        - 1.006 * (dryBulb - wetBulb))
                        / (2830.0 + 1.86 * dryBulb - 2.1 * wetBulb);
        if (!Double.isFinite(ratio)) {
            throw new IllegalArgumentException("Wet-bulb humidity ratio must be finite");
        }
        return Math.max(ratio, MIN_HUMIDITY_RATIO);
    }

    private double saturationVaporPressure(double temperatureC) {
        double t = temperatureC + 273.15;
        double lnPws;
        if (temperatureC <= 0.01) {
            lnPws = -5.6745359e3 / t + 6.3925247
                    - 9.677843e-3 * t
                    + 0.62215701e-6 * t * t
                    + 2.0747825e-9 * t * t * t
                    - 9.484024e-13 * t * t * t * t
                    + 4.1635019 * Math.log(t);
        } else {
            lnPws = -5.8002206e3 / t + 1.3914993
                    - 4.8640239e-2 * t
                    + 4.1764768e-5 * t * t
                    - 1.4452093e-8 * t * t * t
                    + 6.5459673 * Math.log(t);
        }
        return Math.exp(lnPws);
    }
}
