# HVAC Core Formula Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all enabled HVAC formulas, consume frozen minute batches, persist traceable minute indicators, publish latest states, recover missing calculations, and expose permission-protected indicator APIs.

**Architecture:** Pure formula classes operate only on immutable minute inputs. `HvacFormulaEngine` listens to the existing post-persistence `HvacMinuteBatchFrozenEvent`, resolves active `BizIndicator` instances, persists successful rows or compact exception rows through a new strong-typed TDengine repository, and only then updates Redis and WebSocket. Recovery events and a low-frequency formula recovery scheduler reload complete frozen minutes before recalculating.

**Tech Stack:** Java 21, Spring Boot 3.2.4, JUnit 5, AssertJ, Mockito, MyBatis-Plus, TDengine 3.x JDBC, Redis/Lettuce, Jackson, Jakarta WebSocket, Maven.

## Global Constraints

- Implement formulas 5-1, 5-2, 5-4, 5-5, 5-6, and 5-7.
- Do not implement or schedule formula 5-3.
- Do not implement system COP, control, interpolation quality 1, or default-value quality 2.
- Use only inputs from the same `buildingId + minuteStart`.
- Never combine values from different minutes and never silently apply `default_value`.
- Resolve formula inputs by internal identity, never by MQTT alias text.
- Distinguish chiller `MAIN/GW` and `MAIN/PPE` from pump `Pc/GW` and `Pc/PPE`.
- Direct chiller PPE and direct tower TWB have priority only when present and valid.
- A present but invalid primary input produces `INVALID_INPUT`; it must not activate a fallback.
- Derived wet-bulb temperature uses the approved ASHRAE psychrometric bisection algorithm at configurable pressure, default `101.325 kPa`.
- Formula quality is the maximum quality value among inputs actually used.
- Store full `double` precision; round only in presentation.
- Successful indicator timestamps must equal the source `minuteStart`.
- Persist TDengine first; update Redis and WebSocket only after successful persistence.
- Keep `st_indicator_minute` narrow; store failed attempts in `st_formula_calc_exception`.
- Formula 5-7 converts `EtaT` from percent to a decimal before division.
- Keep the existing untracked PPT file unchanged.

---

## File Structure

### Formula domain

- Create `src/main/java/com/platform/iot/formula/model/FormulaCalculation.java`: immutable formula result, input, and step records.
- Create `src/main/java/com/platform/iot/formula/FormulaInputs.java`: immutable semantic-key lookup and validation helpers.
- Create `src/main/java/com/platform/iot/formula/IndicatorFormula.java`: common formula strategy interface.
- Create `src/main/java/com/platform/iot/formula/FormulaKeys.java`: internal component/suffix keys.
- Create `src/main/java/com/platform/iot/formula/ChillerCopFormula.java`: formulas 5-1 and 5-2.
- Create `src/main/java/com/platform/iot/formula/PumpEfficiencyFormula.java`: formulas 5-6 and 5-5.
- Create `src/main/java/com/platform/iot/formula/AhuPowerEfficiencyFormula.java`: formula 5-7.
- Create `src/main/java/com/platform/iot/formula/PsychrometricWetBulbCalculator.java`: approved ASHRAE SI wet-bulb calculation.
- Create `src/main/java/com/platform/iot/formula/CoolingTowerEfficiencyFormula.java`: formula 5-4 with direct/derived TWB.

### Indicator storage and configuration

- Create `src/main/java/com/platform/iot/formula/model/IndicatorMinuteResult.java`: successful persisted indicator.
- Create `src/main/java/com/platform/iot/formula/model/FormulaCalculationException.java`: failed calculation audit row.
- Create `src/main/java/com/platform/iot/formula/model/IndicatorMinuteKey.java`: `indicatorId + minuteStart` key.
- Create `src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java`: strong-typed indicator storage boundary.
- Create `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java`: TDengine implementation.
- Create `src/main/java/com/platform/iot/formula/IndicatorConfigProvider.java`: active indicator snapshot boundary.
- Create `src/main/java/com/platform/iot/formula/MySqlIndicatorConfigProvider.java`: MySQL-backed immutable snapshot.
- Create `src/main/java/com/platform/config/FormulaProperties.java`: pressure, refresh, and recovery settings.
- Modify `src/main/java/com/platform/config/TdengineProperties.java`: exception stable name.
- Modify `src/main/java/com/platform/config/TdengineConfig.java`: create and incrementally migrate formula stables.
- Modify `src/env/init/04-init-tdengine-hvac.sql`: fresh-install schema.
- Create `src/env/init/07-migrate-tdengine-formula-engine.sql`: existing-install migration.
- Delete `src/main/java/com/platform/iot/temporal/HvacTimeSeriesRepository.java`: superseded untyped interface.
- Delete `src/main/java/com/platform/iot/temporal/impl/HvacTimeSeriesRepositoryImpl.java`: superseded implementation that writes server time and swallows errors.

### Runtime orchestration

- Create `src/main/java/com/platform/iot/formula/FormulaInputAssembler.java`: maps frozen rows to formula semantic keys.
- Create `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`: event listener and persistence ordering.
- Modify `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`: complete-minute read.
- Modify `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`: complete-minute TDengine query.
- Create `src/main/java/com/platform/iot/formula/HvacFormulaRecoveryService.java`: indicator gap recovery.
- Create `src/main/java/com/platform/iot/formula/HvacFormulaRecoveryScheduler.java`: startup and fixed-delay trigger.

### Cache, realtime, and query

- Create `src/main/java/com/platform/iot/formula/model/IndicatorLatestState.java`: cache/WebSocket state.
- Replace `src/main/java/com/platform/cache/CopValueCacheService.java` with `src/main/java/com/platform/cache/IndicatorLatestCacheService.java`: JSON and monotonic-minute cache.
- Modify `src/main/java/com/platform/cache/CacheConstants.java`: indicator-ID cache key.
- Create `src/main/java/com/platform/iot/websocket/RealtimeMessageGateway.java`: mockable broadcast boundary.
- Create `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`: adapter over existing static server.
- Create `src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java`: stable `HVAC_INDICATOR` JSON.
- Create `src/main/java/com/platform/hvac/model/dto/HvacIndicatorDtos.java`: latest, history, and calculation detail responses.
- Create `src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java`: permissions, cache fallback, TDengine merge, and explain mode.
- Create `src/main/java/com/platform/hvac/controller/HvacIndicatorController.java`: three approved HTTP endpoints.

### Tests and acceptance

- Create focused formula, repository, engine, recovery, cache, publisher, service, and controller tests under matching `src/test/java` packages.
- Modify `src/test/resources/data-test.sql`: add four active indicator instances.
- Modify `src/test/resources/application-test.yml`: disable formula background recovery.
- Create `.scripts/simulate-hvac-19-points.mjs`: deterministic 19-point MQTT smoke publisher.
- Modify `src/env/docker-compose.yml`: add the Redis service already required by application configuration.
- Modify `docs/MQTT-硬件数据对接说明.md`: formula smoke and status contract.

---

### Task 1: Formula Result Contract and Chiller/Pump/AHU Pure Calculators

**Files:**
- Create: `src/main/java/com/platform/iot/formula/model/FormulaCalculation.java`
- Create: `src/main/java/com/platform/iot/formula/FormulaInputs.java`
- Create: `src/main/java/com/platform/iot/formula/IndicatorFormula.java`
- Create: `src/main/java/com/platform/iot/formula/FormulaKeys.java`
- Create: `src/main/java/com/platform/iot/formula/ChillerCopFormula.java`
- Create: `src/main/java/com/platform/iot/formula/PumpEfficiencyFormula.java`
- Create: `src/main/java/com/platform/iot/formula/AhuPowerEfficiencyFormula.java`
- Test: `src/test/java/com/platform/iot/formula/ChillerCopFormulaTest.java`
- Test: `src/test/java/com/platform/iot/formula/PumpEfficiencyFormulaTest.java`
- Test: `src/test/java/com/platform/iot/formula/AhuPowerEfficiencyFormulaTest.java`

**Interfaces:**
- Consumes: immutable `FormulaInputs`.
- Produces:
  - `IndicatorFormula#indicatorCode()`
  - `IndicatorFormula#formulaVersion()`
  - `IndicatorFormula#calculate(FormulaInputs)`
  - `FormulaCalculation` with `SUCCESS`, `MISSING_INPUT`, or `INVALID_INPUT`.

- [ ] **Step 1: Write failing golden-vector and boundary tests**

Use these exact assertions:

```java
@Test
void calculatesChillerCopFromDirectPpe() {
    FormulaCalculation result = formula.calculate(inputs(
            point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
            point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
            point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
            point(FormulaKeys.CHILLER_PPE, 100.0, 1),
            point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 2)));

    assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
    assertThat(result.value()).isCloseTo(5.805555555556, within(1.0e-9));
    assertThat(result.dataQuality()).isEqualTo(1);
    assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
            .containsExactly("5-1", "NI_PPE", "5-2");
}

@Test
void fallsBackOnlyWhenPpeIsAbsent() {
    FormulaCalculation result = formula.calculate(inputs(
            point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
            point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
            point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
            point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 0),
            point(FormulaKeys.CHILLER_CURRENT, 100.0, 1),
            point(FormulaKeys.CHILLER_PF, 0.9, 2)));

    assertThat(result.value()).isCloseTo(9.800699014021, within(1.0e-9));
    assertThat(result.dataQuality()).isEqualTo(2);
}

@Test
void invalidPresentPpeDoesNotUseFallback() {
    FormulaCalculation result = formula.calculate(inputs(
            point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
            point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
            point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
            point(FormulaKeys.CHILLER_PPE, 0.0, 0),
            point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 0),
            point(FormulaKeys.CHILLER_CURRENT, 100.0, 0),
            point(FormulaKeys.CHILLER_PF, 0.9, 0)));

    assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
    assertThat(result.reasonCode()).isEqualTo("CHILLER_POWER_NON_POSITIVE");
}
```

Add pump assertions for `ΔH=20.408163265306` and `η=58.277777777778`, and AHU assertions for `1000 Pa / 60% = 0.462962962963 W/(m³/h)`. Add tests for every missing key, reverse temperature, reverse pressure, zero power, PF outside `(0,1]`, efficiency above 100%, `NaN`, and infinity.

- [ ] **Step 2: Run tests and verify that the formula package does not exist**

Run:

```powershell
mvn "-Dtest=ChillerCopFormulaTest,PumpEfficiencyFormulaTest,AhuPowerEfficiencyFormulaTest" test
```

Expected: test compilation fails because the formula classes are not defined.

- [ ] **Step 3: Implement the immutable formula contract**

Create the following exact public shape:

```java
public record FormulaCalculation(
        Status status,
        String indicatorCode,
        String formulaVersion,
        Double value,
        Integer dataQuality,
        List<Input> inputs,
        List<Step> steps,
        String reasonCode,
        List<String> missingInputs) {

    public enum Status { SUCCESS, MISSING_INPUT, INVALID_INPUT, ENGINE_ERROR }

    public record Input(
            String key, String pointId, String pointCode,
            double value, String unit, int dataQuality) {}

    public record Step(
            String code, String expression, double value, String unit) {}

    public FormulaCalculation {
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
        missingInputs = List.copyOf(missingInputs);
    }
}
```

`FormulaInputs` must copy its input map, expose `Optional<Input> find(String key)`, preserve deterministic key order, and reject duplicate semantic keys instead of silently selecting one.

`IndicatorFormula` must be:

```java
public interface IndicatorFormula {
    String indicatorCode();
    String formulaVersion();
    FormulaCalculation calculate(FormulaInputs inputs);
}
```

Define exact keys:

```java
public static final String CHILLER_T_IN = "MAIN/TWin";
public static final String CHILLER_T_OUT = "MAIN/TWout";
public static final String CHILLER_FLOW = "MAIN/GW";
public static final String CHILLER_PPE = "MAIN/PPE";
public static final String CHILLER_VOLTAGE = "MAIN/Voltage";
public static final String CHILLER_CURRENT = "MAIN/Current";
public static final String CHILLER_PF = "MAIN/PF";
public static final String PUMP_FLOW = "Pc/GW";
public static final String PUMP_P_OUT = "Pc/Pout";
public static final String PUMP_P_IN = "Pc/Pin";
public static final String PUMP_Z = "Pc/Z";
public static final String PUMP_PPE = "Pc/PPE";
public static final String AHU_TOTAL_PRESSURE = "MAIN/TotalPress";
public static final String AHU_ETA_T = "MAIN/EtaT";
```

- [ ] **Step 4: Implement the three calculators**

Use these exact constants and equations:

```java
private static final double WATER_DENSITY = 1000.0;
private static final double WATER_SPECIFIC_HEAT = 4.18;
private static final double GRAVITY = 9.8;
private static final double SQRT_THREE = Math.sqrt(3.0);
```

Chiller core:

```java
double coolingCapacity = flow.value() * WATER_DENSITY * WATER_SPECIFIC_HEAT
        * (tIn.value() - tOut.value()) / 3600.0;
double inputPower = ppe.isPresent()
        ? ppe.orElseThrow().value()
        : voltage.value() * current.value() * powerFactor.value()
                * SQRT_THREE / 1000.0;
double cop = coolingCapacity / inputPower;
```

Pump core:

```java
double head = (pOut.value() - pIn.value()) / (WATER_DENSITY * GRAVITY);
double efficiency = flow.value() * WATER_DENSITY * GRAVITY
        * (head + z.value()) * 1.0e-6
        / (3.6 * power.value()) * 100.0;
```

AHU core:

```java
double normalizedEfficiency = etaPercent.value() / 100.0;
double powerEfficiency = totalPressure.value()
        / (3600.0 * normalizedEfficiency);
```

Every calculator must:

1. return all missing semantic keys in stable order;
2. validate every used value with `Double.isFinite`;
3. return stable reason codes;
4. calculate quality with `max` over only used inputs;
5. return steps with formulas `5-1`, `5-2`, `5-6`, `5-5`, and `5-7`;
6. never clamp out-of-range results.

- [ ] **Step 5: Run formula tests**

Run:

```powershell
mvn "-Dtest=ChillerCopFormulaTest,PumpEfficiencyFormulaTest,AhuPowerEfficiencyFormulaTest" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/platform/iot/formula src/test/java/com/platform/iot/formula
git commit -m "feat: implement core hvac equipment formulas"
```

---

### Task 2: ASHRAE Wet-Bulb Solver and Cooling Tower Formula

**Files:**
- Create: `src/main/java/com/platform/config/FormulaProperties.java`
- Create: `src/main/java/com/platform/iot/formula/PsychrometricWetBulbCalculator.java`
- Create: `src/main/java/com/platform/iot/formula/CoolingTowerEfficiencyFormula.java`
- Modify: `src/main/java/com/platform/iot/formula/FormulaKeys.java`
- Test: `src/test/java/com/platform/iot/formula/PsychrometricWetBulbCalculatorTest.java`
- Test: `src/test/java/com/platform/iot/formula/CoolingTowerEfficiencyFormulaTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: dry-bulb °C, RH percent, and pressure kPa.
- Produces:
  - `PsychrometricWetBulbCalculator#calculate(double, double, double)`
  - `TOWER_EFF` version `TOWER_EFF_V1`.

- [ ] **Step 1: Write failing wet-bulb and tower tests**

```java
@Test
void matchesAshraePsychrometricReferenceAtStandardPressure() {
    assertThat(calculator.calculate(30.0, 50.0, 101.325))
            .isCloseTo(22.0053, within(0.01));
}

@Test
void directWetBulbHasPriority() {
    FormulaCalculation result = formula.calculate(inputs(
            point(FormulaKeys.TOWER_T_IN, 35.0, 0),
            point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
            point(FormulaKeys.TOWER_TWB, 25.0, 1),
            point(FormulaKeys.OUTDOOR_TDB, 30.0, 2),
            point(FormulaKeys.OUTDOOR_RH, 50.0, 2)));

    assertThat(result.value()).isCloseTo(50.0, within(1.0e-9));
    assertThat(result.dataQuality()).isEqualTo(1);
    assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
            .containsExactly("TWB_DIRECT", "5-4");
}

@Test
void derivesWetBulbOnlyWhenDirectPointIsAbsent() {
    FormulaCalculation result = formula.calculate(inputs(
            point(FormulaKeys.TOWER_T_IN, 35.0, 0),
            point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
            point(FormulaKeys.OUTDOOR_TDB, 30.0, 1),
            point(FormulaKeys.OUTDOOR_RH, 50.0, 2)));

    assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
    assertThat(result.dataQuality()).isEqualTo(2);
    assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
            .containsExactly("TWB_DERIVED", "5-4");
}
```

Also test RH `0`, RH `>100`, nonpositive pressure, direct TWB above inlet temperature, direct `NaN`, and missing both direct and derived inputs.

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
mvn "-Dtest=PsychrometricWetBulbCalculatorTest,CoolingTowerEfficiencyFormulaTest" test
```

Expected: test compilation fails because both calculators are absent.

- [ ] **Step 3: Implement the SI psychrometric solver**

Use pressure internally in Pa and RH internally in `[0,1]`. Implement ASHRAE saturation vapor pressure coefficients:

```java
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
```

Use:

```java
private static final double MIN_HUMIDITY_RATIO = 1.0e-7;
private static final double TOLERANCE_C = 0.001;
private static final int MAX_ITERATIONS = 100;

private double humidityRatioFromWetBulb(double dryBulb, double wetBulb, double p) {
    double ws = 0.621945 * saturationVaporPressure(wetBulb)
            / (p - saturationVaporPressure(wetBulb));
    double ratio = wetBulb >= 0.0
            ? ((2501.0 - 2.326 * wetBulb) * ws
                    - 1.006 * (dryBulb - wetBulb))
                    / (2501.0 + 1.86 * dryBulb - 4.186 * wetBulb)
            : ((2830.0 - 0.24 * wetBulb) * ws
                    - 1.006 * (dryBulb - wetBulb))
                    / (2830.0 + 1.86 * dryBulb - 2.1 * wetBulb);
    return Math.max(ratio, MIN_HUMIDITY_RATIO);
}
```

Find dew point by bisection on saturation vapor pressure over `[-100°C, dryBulb]`. Then find wet bulb by bisection over `[dewPoint, dryBulb]` until the interval is at most `0.001°C`. Throw `IllegalArgumentException` if validation fails or convergence exceeds 100 iterations.

- [ ] **Step 4: Implement tower priority and formula 5-4**

Add exact keys:

```java
public static final String TOWER_T_IN = "CT/TWin";
public static final String TOWER_T_OUT = "CT/TWout";
public static final String TOWER_TWB = "CT/TWB";
public static final String OUTDOOR_TDB = "DBO/TDB";
public static final String OUTDOOR_RH = "RHO/RH";
```

Configure:

```yaml
formula:
  enabled: true
  atmospheric-pressure-kpa: ${HVAC_ATMOSPHERIC_PRESSURE_KPA:101.325}
  indicator-config-refresh-ms: 60000
  recovery-enabled: true
  recovery-minutes: 10
  recovery-delay-ms: 600000
```

`FormulaProperties` must bind those exact fields. Tower calculation must reject invalid direct TWB without using the derived path.

Use this concrete configuration class:

```java
@Data
@Component
@ConfigurationProperties(prefix = "formula")
public class FormulaProperties {
    private boolean enabled = true;
    private double atmosphericPressureKpa = 101.325;
    private long indicatorConfigRefreshMs = 60_000L;
    private boolean recoveryEnabled = true;
    private int recoveryMinutes = 10;
    private long recoveryDelayMs = 600_000L;
}
```

- [ ] **Step 5: Run tests**

```powershell
mvn "-Dtest=PsychrometricWetBulbCalculatorTest,CoolingTowerEfficiencyFormulaTest" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/platform/config/FormulaProperties.java src/main/java/com/platform/iot/formula src/main/resources/application.yml src/test/resources/application-test.yml src/test/java/com/platform/iot/formula
git commit -m "feat: calculate cooling tower efficiency"
```

---

### Task 3: Lightweight Indicator and Formula Exception TDengine Storage

**Files:**
- Create: `src/main/java/com/platform/iot/formula/model/IndicatorMinuteResult.java`
- Create: `src/main/java/com/platform/iot/formula/model/FormulaCalculationException.java`
- Create: `src/main/java/com/platform/iot/formula/model/IndicatorMinuteKey.java`
- Create: `src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java`
- Create: `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java`
- Modify: `src/main/java/com/platform/config/TdengineProperties.java`
- Modify: `src/main/java/com/platform/config/TdengineConfig.java`
- Modify: `src/env/init/04-init-tdengine-hvac.sql`
- Create: `src/env/init/07-migrate-tdengine-formula-engine.sql`
- Delete: `src/main/java/com/platform/iot/temporal/HvacTimeSeriesRepository.java`
- Delete: `src/main/java/com/platform/iot/temporal/impl/HvacTimeSeriesRepositoryImpl.java`
- Test: `src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java`
- Modify: `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`

**Interfaces:**
- Consumes: `IndicatorMinuteResult`, `FormulaCalculationException`.
- Produces:

```java
void saveSuccesses(List<IndicatorMinuteResult> results);
void saveExceptions(List<FormulaCalculationException> exceptions);
Optional<IndicatorMinuteResult> findSuccess(String indicatorId, long minuteStart);
Optional<FormulaCalculationException> findException(String indicatorId, long minuteStart);
List<IndicatorMinuteResult> findLatestSuccesses(List<String> indicatorIds);
List<FormulaCalculationException> findLatestExceptions(List<String> indicatorIds);
List<IndicatorMinuteResult> findHistory(
        String indicatorId, long fromInclusive, long toExclusive);
Set<IndicatorMinuteKey> findSuccessfulKeys(
        List<String> indicatorIds, long fromInclusive, long toExclusive);
```

- [ ] **Step 1: Write failing repository and schema tests**

Assert that success SQL:

```java
repository.saveSuccesses(List.of(new IndicatorMinuteResult(
        "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
        "EQUIP_WCR_B1", MINUTE, 5.805555555556, 1,
        "WCR_COP_V1", MINUTE + 90_000L)));

assertThat(insertSql)
        .contains("st_indicator_minute_INDICATOR_WCR_COP_B1")
        .contains("VALUES ('2027-01-15")
        .contains("5.805555555556")
        .contains("WCR_COP_V1")
        .doesNotContain(Long.toString(System.currentTimeMillis()));
```

Assert that `template.execute` exceptions escape `saveSuccesses`. Assert exception SQL writes `MISSING_INPUT`, stable reason code, and joined keys. Assert empty lists do not touch TDengine. Assert latest success and latest exception are separate queries, and success keys are queried in one stable query.

Update schema test to require:

```text
st_indicator_minute
data_quality
formula_version
calculated_at
st_formula_calc_exception
calc_status
reason_code
missing_inputs
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
mvn "-Dtest=TdengineIndicatorMinuteRepositoryTest,TdengineHvacSchemaTest" test
```

Expected: compilation/schema assertions fail.

- [ ] **Step 3: Add strong-typed models and repository interface**

Use exact model shapes:

```java
public record IndicatorMinuteResult(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        double value,
        int dataQuality,
        String formulaVersion,
        long calculatedAt) {}

public record FormulaCalculationException(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        String reasonCode,
        List<String> missingInputs,
        String formulaVersion,
        long calculatedAt) {
    public FormulaCalculationException {
        missingInputs = List.copyOf(missingInputs);
    }
}

public record IndicatorMinuteKey(String indicatorId, long minuteStart) {}
```

- [ ] **Step 4: Implement compact TDengine schemas**

Fresh install:

```sql
CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_indicator_minute` (
    `ts`              TIMESTAMP NOT NULL,
    `val`             DOUBLE NOT NULL,
    `data_quality`    TINYINT NOT NULL,
    `formula_version` NCHAR(32) NOT NULL,
    `calculated_at`   TIMESTAMP NOT NULL
) TAGS (
    `indicator_id`    NCHAR(32) NOT NULL,
    `indicator_code`  NCHAR(100) NOT NULL,
    `building_id`     NCHAR(32) NOT NULL,
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);

CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_calc_exception` (
    `ts`              TIMESTAMP NOT NULL,
    `calc_status`     NCHAR(32) NOT NULL,
    `reason_code`     NCHAR(64) NOT NULL,
    `missing_inputs`  NCHAR(512),
    `formula_version` NCHAR(32) NOT NULL,
    `calculated_at`   TIMESTAMP NOT NULL
) TAGS (
    `indicator_id`    NCHAR(32) NOT NULL,
    `indicator_code`  NCHAR(100) NOT NULL,
    `building_id`     NCHAR(32) NOT NULL,
    `system_group_id` NCHAR(32),
    `equip_id`        NCHAR(32)
);
```

The incremental migration must guard and issue these exact additions for the existing indicator stable:

```sql
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `data_quality` TINYINT;
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `formula_version` NCHAR(32);
ALTER STABLE `iot_telemetry`.`st_indicator_minute`
    ADD COLUMN `calculated_at` TIMESTAMP;
```

Then create `st_formula_calc_exception` with the fresh-install definition above. `TdengineConfig` must call `ensureFields` with those three columns and verify both stables.

- [ ] **Step 5: Implement repository SQL and remove the unsafe legacy path**

Build one multi-child `INSERT` per nonempty success batch and one per nonempty exception batch. Use `new Timestamp(minuteStart)` for `ts`, finite-number validation before SQL, identifier whitelist validation, and SQL-value escaping. Do not catch `DataAccessException`.

Delete the legacy interface and implementation only after:

```powershell
rg -n "HvacTimeSeriesRepository" src/main src/test
```

returns only those two legacy files.

- [ ] **Step 6: Run repository tests**

```powershell
mvn "-Dtest=TdengineIndicatorMinuteRepositoryTest,TdengineHvacSchemaTest" test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/platform/config src/main/java/com/platform/iot/formula/model src/main/java/com/platform/iot/temporal src/env/init src/test/java/com/platform/config src/test/java/com/platform/iot/temporal
git commit -m "feat: store formula indicators and exceptions"
```

---

### Task 4: Monotonic Redis Cache and WebSocket Indicator Publisher

**Files:**
- Create: `src/main/java/com/platform/iot/formula/model/IndicatorLatestState.java`
- Create: `src/main/java/com/platform/cache/IndicatorLatestCacheService.java`
- Delete: `src/main/java/com/platform/cache/CopValueCacheService.java`
- Modify: `src/main/java/com/platform/cache/CacheConstants.java`
- Create: `src/main/java/com/platform/iot/websocket/RealtimeMessageGateway.java`
- Create: `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`
- Create: `src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java`
- Test: `src/test/java/com/platform/cache/IndicatorLatestCacheServiceTest.java`
- Test: `src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java`

**Interfaces:**
- Consumes: `IndicatorLatestState`.
- Produces:

```java
Optional<IndicatorLatestState> get(String indicatorId);
boolean setIfNotOlder(IndicatorLatestState state);
void publish(IndicatorLatestState state);
```

- [ ] **Step 1: Write failing cache and publisher tests**

Test:

- JSON round trip keeps steps, inputs, quality, and missing keys.
- cache key is `iot:indicator:latest:{indicatorId}`.
- newer minute replaces older state.
- older recovery minute does not replace newer state.
- equal minute may replace failure with success.
- Redis `DataAccessException` returns a cache miss/false and does not escape.
- publisher JSON contains `type=HVAC_INDICATOR`.
- publisher uses the injected gateway, not the static WebSocket class directly.

```java
@Test
void olderRecoveryDoesNotOverwriteNewerState() {
    when(valueOps.get(key)).thenReturn(objectMapper.writeValueAsString(
            success("IND001", MINUTE + 60_000L, 5.9)));

    assertThat(cache.setIfNotOlder(
            success("IND001", MINUTE, 5.8))).isFalse();

    verify(valueOps, never()).set(eq(key), anyString(), any(Duration.class));
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
mvn "-Dtest=IndicatorLatestCacheServiceTest,IndicatorRealtimePublisherTest" test
```

Expected: compilation fails because the new services are absent.

- [ ] **Step 3: Implement the state, cache, and gateway**

State:

```java
public record IndicatorLatestState(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        Double value,
        Integer dataQuality,
        String formulaVersion,
        String reasonCode,
        List<String> missingInputs,
        List<FormulaCalculation.Input> inputs,
        List<FormulaCalculation.Step> steps) {
    public IndicatorLatestState {
        missingInputs = List.copyOf(missingInputs);
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
    }
}
```

Use:

```java
public static final String INDICATOR_LATEST =
        PREFIX + "indicator:latest:";
```

The single-instance monotonic write algorithm is:

```java
Optional<IndicatorLatestState> current = get(state.indicatorId());
if (current.isPresent()
        && current.orElseThrow().minuteStart() > state.minuteStart()) {
    return false;
}
redis.opsForValue().set(
        key(state.indicatorId()),
        objectMapper.writeValueAsString(state),
        Duration.ofSeconds(120));
return true;
```

For equal minutes, prefer `SUCCESS` over non-success. Add a comment that multi-instance deployment must replace the compare/set pair with a Lua script or Redisson lock.

Gateway:

```java
public interface RealtimeMessageGateway {
    void broadcast(String message);
}

@Component
public class WebSocketRealtimeMessageGateway implements RealtimeMessageGateway {
    @Override
    public void broadcast(String message) {
        WebSocketServer.broadcastMessage(message);
    }
}
```

Publisher serializes:

```java
Map.of("type", "HVAC_INDICATOR", "data", state)
```

and logs but does not throw on serialization or WebSocket delivery failure.

- [ ] **Step 4: Run tests**

```powershell
mvn "-Dtest=IndicatorLatestCacheServiceTest,IndicatorRealtimePublisherTest" test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/platform/cache src/main/java/com/platform/iot/formula/model/IndicatorLatestState.java src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java src/main/java/com/platform/iot/websocket src/test/java/com/platform/cache src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java
git commit -m "feat: publish latest hvac indicator states"
```

---

### Task 5: Active Indicator Provider, Input Assembly, and Event-Driven Engine

**Files:**
- Create: `src/main/java/com/platform/iot/formula/IndicatorConfigProvider.java`
- Create: `src/main/java/com/platform/iot/formula/MySqlIndicatorConfigProvider.java`
- Create: `src/main/java/com/platform/iot/formula/FormulaInputAssembler.java`
- Create: `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Test: `src/test/java/com/platform/iot/formula/MySqlIndicatorConfigProviderTest.java`
- Test: `src/test/java/com/platform/iot/formula/FormulaInputAssemblerTest.java`
- Test: `src/test/java/com/platform/iot/formula/HvacFormulaEngineTest.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`
- Modify: `src/test/resources/data-test.sql`

**Interfaces:**
- Consumes:
  - `HvacMinuteBatchFrozenEvent`
  - `Collection<BizIndicator> findAllActive()`
  - `List<RawMinuteAggregate> findByMinute(long, Set<String>)`
- Produces: persisted success/exception rows and post-persistence latest notifications.

- [ ] **Step 1: Add four test indicator rows**

Append exact H2 test data:

```sql
INSERT INTO biz_indicator
(indicator_id, building_id, indicator_code, scope_type, scope_id,
 equip_id, system_group_id, status)
VALUES
('INDICATOR_WCR_COP_B1','BLD001','WCR_COP','EQUIPMENT',
 'EQUIP_WCR_B1','EQUIP_WCR_B1','GROUP001',1),
('INDICATOR_TOWER_EFF_B1','BLD001','TOWER_EFF','EQUIPMENT',
 'EQUIP_TOWER_B1','EQUIP_TOWER_B1','GROUP001',1),
('INDICATOR_PUMP_EFF_B1','BLD001','PUMP_EFF','EQUIPMENT',
 'EQUIP_PUMP_B1','EQUIP_PUMP_B1','GROUP001',1),
('INDICATOR_AHU_EFF_B1','BLD001','AHU_POW_EFF','EQUIPMENT',
 'EQUIP_AHU_B1','EQUIP_AHU_B1','GROUP001',1);
```

- [ ] **Step 2: Write failing provider, assembler, repository, and engine tests**

Required engine tests:

```java
@Test
void normalFrozenEventUsesPayloadWithoutMinuteQuery() {
    engine.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
            MINUTE, MINUTE + 90_000L, false, completeAggregates()));

    verify(minuteRepository, never()).findByMinute(anyLong(), anySet());
    verify(indicatorRepository).saveSuccesses(argThat(rows ->
            rows.size() == 4
                    && rows.stream().allMatch(r -> r.minuteStart() == MINUTE)));
}

@Test
void recoveryEventReloadsCompleteMinuteBeforeCalculating() {
    when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
            .thenReturn(completeAggregates());

    engine.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
            MINUTE, MINUTE + 90_000L, true,
            List.of(point("POINT001", "EQUIP_WCR_B1", "MAIN", "TWin", 12.0))));

    verify(minuteRepository).findByMinute(MINUTE, Set.of("BLD001"));
    verify(indicatorRepository).saveSuccesses(anyList());
}
```

Also assert:

- `isForCalc=0` rows are ignored;
- same suffix in different equipment is not mixed;
- same equipment code in two buildings is not mixed;
- all four indicators are attempted independently;
- one formula exception becomes `ENGINE_ERROR` without blocking others;
- success repository call occurs before cache/publisher;
- repository failure prevents cache and WebSocket;
- a failed attempt is saved only to exception storage;
- notification state uses `minuteStart`, not `calculatedAt`.

Repository test must map all fields needed to rebuild `RawMinuteAggregate` from `st_raw_minute`.

- [ ] **Step 3: Run tests and verify failure**

```powershell
mvn "-Dtest=MySqlIndicatorConfigProviderTest,FormulaInputAssemblerTest,HvacFormulaEngineTest,TdengineHvacMinuteRepositoryTest" test
```

Expected: compilation fails on missing provider, assembler, engine, and repository method.

- [ ] **Step 4: Implement active indicator snapshot**

Interface:

```java
public interface IndicatorConfigProvider {
    Collection<BizIndicator> findAllActive();
    Optional<BizIndicator> findActive(String indicatorId);
}
```

`MySqlIndicatorConfigProvider` follows the existing `MySqlDataPointConfigProvider` pattern:

- load `status=1`;
- build immutable ID map;
- atomically replace a volatile snapshot;
- initialize with `@PostConstruct`;
- refresh with `${formula.indicator-config-refresh-ms:60000}`;
- retain the prior complete snapshot after MySQL failure.

- [ ] **Step 5: Implement complete-minute query**

Add:

```java
List<RawMinuteAggregate> findByMinute(
        long minuteStart, Set<String> buildingIds);
```

The TDengine query must:

```sql
SELECT point_id, point_code, building_id, system_group_id, equip_id,
       equip_code, family_code, component_code, suffix_code, is_for_calc,
       ts, avg_val, min_val, max_val, sample_count, data_quality,
       first_received_time, last_received_time, finalized_at
FROM iot_telemetry.st_raw_minute
WHERE ts = ?
  AND building_id IN ('BLD001','BLD002')
ORDER BY building_id, point_id
```

The example literals above are generated from the caller's validated `buildingIds` by the repository's existing `quote` helper; they are not hardcoded. Return an empty list without querying if `buildingIds` is empty.

- [ ] **Step 6: Implement semantic input assembly**

For equipment inputs:

```java
String key = aggregate.componentCode() + "/" + aggregate.suffixCode();
```

For environment inputs:

```java
String key = aggregate.familyCode() + "/" + aggregate.suffixCode();
```

Only include rows where:

```java
aggregate.isForCalc() == 1
&& aggregate.minuteStart() == requestedMinute
&& aggregate.buildingId().equals(indicator.getBuildingId())
```

Equipment formulas additionally require matching `equipId`. Duplicate semantic keys produce `ENGINE_ERROR`; they must not be resolved by list order.

- [ ] **Step 7: Implement engine ordering**

Register calculators by `indicatorCode` and reject duplicate strategies at construction.
Annotate the engine with:

```java
@Component
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
```

```java
@EventListener
public void onMinuteFrozen(HvacMinuteBatchFrozenEvent event) {
    Set<String> buildingIds = event.aggregates().stream()
            .map(RawMinuteAggregate::buildingId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<RawMinuteAggregate> inputs = event.recovery()
            ? minuteRepository.findByMinute(event.minuteStart(), buildingIds)
            : event.aggregates();
    calculateAndPersist(
            event.minuteStart(), event.finalizedAt(), inputs, null);
}
```

`calculateAndPersist` must:

1. select active indicators belonging to affected buildings;
2. calculate each indicator in its own `try/catch`;
3. collect success and exception rows;
4. call `saveSuccesses` once if nonempty;
5. only after success persistence, update cache and publish each success;
6. call `saveExceptions` once if nonempty;
7. only after exception persistence, update cache and publish each failure.

Do not annotate the listener `@Async` in the MVP. Keep deterministic single-instance ordering.

- [ ] **Step 8: Run tests**

```powershell
mvn "-Dtest=MySqlIndicatorConfigProviderTest,FormulaInputAssemblerTest,HvacFormulaEngineTest,TdengineHvacMinuteRepositoryTest" test
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/platform/iot/formula src/main/java/com/platform/iot/temporal src/test/java/com/platform/iot/formula src/test/java/com/platform/iot/temporal src/test/resources/data-test.sql
git commit -m "feat: calculate indicators from frozen minutes"
```

---

### Task 6: Independent Formula Gap Recovery

**Files:**
- Create: `src/main/java/com/platform/iot/formula/HvacFormulaRecoveryService.java`
- Create: `src/main/java/com/platform/iot/formula/HvacFormulaRecoveryScheduler.java`
- Modify: `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`
- Test: `src/test/java/com/platform/iot/formula/HvacFormulaRecoveryServiceTest.java`
- Test: `src/test/java/com/platform/iot/formula/HvacFormulaRecoverySchedulerTest.java`
- Modify: `src/test/resources/application-test.yml`

**Interfaces:**
- Consumes:
  - active indicators;
  - successful indicator keys;
  - complete frozen minute data.
- Produces: idempotent calls into `HvacFormulaEngine#calculateAndPersist`.

- [ ] **Step 1: Write failing recovery tests**

Required tests:

- no background execution when `formula.recovery-enabled=false`;
- startup recovery runs after `ApplicationReadyEvent`;
- fixed-delay recovery delegates once per trigger;
- one batch query loads success keys for the configured window;
- complete indicator minutes are skipped;
- only missing indicator IDs are passed to the engine;
- a minute with no frozen inputs is skipped;
- one failed minute does not stop later minutes;
- source minutes use the same finalization delay boundary as aggregation.

```java
@Test
void recalculatesOnlyMissingIndicatorKeys() {
    when(configProvider.findAllActive()).thenReturn(List.of(wcr(), tower()));
    when(indicatorRepository.findSuccessfulKeys(
            List.of("IND_WCR", "IND_TOWER"), MINUTE, MINUTE + 120_000L))
            .thenReturn(Set.of(new IndicatorMinuteKey("IND_WCR", MINUTE)));
    when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
            .thenReturn(completeAggregates());

    service.recover(MINUTE + 120_000L);

    verify(engine).calculateAndPersist(
            eq(MINUTE), anyLong(), eq(completeAggregates()),
            eq(Set.of("IND_TOWER")));
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
mvn "-Dtest=HvacFormulaRecoveryServiceTest,HvacFormulaRecoverySchedulerTest" test
```

Expected: compilation fails because recovery classes are absent.

- [ ] **Step 3: Implement recovery window and scheduler**

Recovery service:

```java
public void recover(long now) {
    long latestDueMinute = floorMinute(
            now - aggregationFinalizationDelayMillis);
    long firstMinute = latestDueMinute
            - (properties.getRecoveryMinutes() - 1L) * 60_000L;
    // Query active indicators and successful keys once, then process minutes in order.
}
```

Inject the finalization delay as:

```java
@Value("${aggregation.finalization-delay-seconds:30}")
int aggregationFinalizationDelaySeconds
```

and convert it with `Math.multiplyExact(seconds, 1_000L)`. Query successful keys over the half-open range `[firstMinute, latestDueMinute + 60_000L)`.

Expose a package-visible engine method:

```java
void calculateAndPersist(
        long minuteStart,
        long calculatedAt,
        List<RawMinuteAggregate> aggregates,
        Set<String> onlyIndicatorIds)
```

Scheduler:

```java
@EventListener(ApplicationReadyEvent.class)
@ConditionalOnProperty(
        prefix = "formula", name = "recovery-enabled",
        havingValue = "true", matchIfMissing = true)
public void recoverOnStartup() {
    service.recover(System.currentTimeMillis());
}

@Scheduled(
        fixedDelayString = "${formula.recovery-delay-ms:600000}",
        initialDelayString = "${formula.recovery-delay-ms:600000}")
public void recoverPeriodically() {
    if (properties.isRecoveryEnabled()) {
        service.recover(System.currentTimeMillis());
    }
}
```

In `application-test.yml`:

```yaml
formula:
  recovery-enabled: false
  recovery-delay-ms: 3600000
```

- [ ] **Step 4: Run tests**

```powershell
mvn "-Dtest=HvacFormulaRecoveryServiceTest,HvacFormulaRecoverySchedulerTest" test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/platform/iot/formula src/test/java/com/platform/iot/formula src/test/resources/application-test.yml
git commit -m "feat: recover missing hvac indicators"
```

---

### Task 7: Permission-Protected Indicator Query APIs and Explain Mode

**Files:**
- Create: `src/main/java/com/platform/hvac/model/dto/HvacIndicatorDtos.java`
- Create: `src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java`
- Create: `src/main/java/com/platform/hvac/controller/HvacIndicatorController.java`
- Modify: `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`
- Test: `src/test/java/com/platform/hvac/service/HvacIndicatorQueryServiceTest.java`
- Create: `src/test/java/com/platform/HvacIndicatorControllerFlowTest.java`

**Interfaces:**
- Consumes: building permission, active indicator metadata, latest cache, indicator repository, complete minute repository, formula explain mode.
- Produces:
  - `GET /api/hvac/buildings/{buildingId}/indicators/latest`
  - `GET /api/hvac/indicators/{indicatorId}/history?from=&to=`
  - `GET /api/hvac/indicators/{indicatorId}/calculations/{minuteStart}`

- [ ] **Step 1: Write failing service and controller tests**

Service tests must verify:

- latest returns all active building indicators;
- cache hit avoids TDengine latest query;
- cache miss merges latest success and latest exception by source minute;
- equal-minute success wins over historical exception;
- newer exception suppresses older success value;
- history validates `from < to` and maximum 31 days;
- history contains only successful rows;
- detail uses cached steps for the latest minute;
- historical success reloads raw minute and recalculates with recorded formula version;
- historical failure returns audit reason and missing keys;
- unknown/disabled/cross-building indicator does not leak data;
- TDengine errors become sanitized HTTP 503 business errors.

Controller flow tests must verify unauthenticated 401, authorized owner/manager/admin 200, out-of-scope 403, and third-party 403 for all three endpoints.

```java
mockMvc.perform(get(
        "/api/hvac/buildings/BLD001/indicators/latest")
        .header("Authorization", bearer(ownerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.indicators.length()").value(4))
        .andExpect(jsonPath("$.data.indicators[0].status").exists());
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
mvn "-Dtest=HvacIndicatorQueryServiceTest,HvacIndicatorControllerFlowTest" test
```

Expected: compilation fails because DTO, service, and controller are absent.

- [ ] **Step 3: Define stable DTOs**

`HvacIndicatorDtos` must contain:

```java
public record LatestResponse(
        String buildingId, long generatedAt, List<LatestIndicator> indicators) {}

public record LatestIndicator(
        String indicatorId, String indicatorCode, String equipId,
        Long minuteStart, String status, Double value, String unit,
        Integer dataQuality, String formulaVersion,
        String reasonCode, List<String> missingInputs) {}

public record HistoryResponse(
        String indicatorId, String indicatorCode,
        long from, long to, List<HistoryRecord> records) {}

public record HistoryRecord(
        long minuteStart, double value,
        int dataQuality, String formulaVersion) {}

public record CalculationDetail(
        String indicatorId, String indicatorCode, String equipId,
        long minuteStart, String status, Double value, String unit,
        Integer dataQuality, String formulaVersion,
        List<FormulaCalculation.Input> inputs,
        List<FormulaCalculation.Step> steps,
        String reasonCode, List<String> missingInputs) {}
```

Use units:

```text
WCR_COP     → null
TOWER_EFF   → %
PUMP_EFF    → %
AHU_POW_EFF → W/(m³/h)
```

- [ ] **Step 4: Implement query service**

Reuse the existing building check pattern:

```java
if (buildingService.getById(buildingId) == null) {
    throw new BusinessException(404, "建筑不存在");
}
buildingScopeService.checkAccess(userId, roles, buildingId);
```

For indicator paths, load the active indicator first, then check its `buildingId`.

Latest fallback merge:

```java
if (success != null && exception != null
        && success.minuteStart() == exception.minuteStart()) {
    return fromSuccess(success);
}
if (exception != null
        && (success == null
            || exception.minuteStart() > success.minuteStart())) {
    return fromException(exception);
}
return success == null ? noData(indicator) : fromSuccess(success);
```

Historical explain mode must select the calculator by both `indicatorCode` and persisted `formulaVersion`; an unknown historic version returns HTTP 409 with `"公式版本不受当前服务支持"`, not a silently different calculation.

Expose this engine method for the query service:

```java
FormulaCalculation explain(
        BizIndicator indicator,
        long minuteStart,
        List<RawMinuteAggregate> aggregates,
        String formulaVersion)
```

It must require the persisted version to equal the registered calculator version before assembling inputs and recalculating.

- [ ] **Step 5: Implement controllers and security annotations**

Use:

```java
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
```

on all three methods. Keep controller methods limited to parameter extraction, `SecurityUser` identity, service delegation, and `Result.success`.

- [ ] **Step 6: Run tests**

```powershell
mvn "-Dtest=HvacIndicatorQueryServiceTest,HvacIndicatorControllerFlowTest" test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/platform/hvac src/main/java/com/platform/iot/formula/HvacFormulaEngine.java src/test/java/com/platform/hvac src/test/java/com/platform/HvacIndicatorControllerFlowTest.java
git commit -m "feat: expose hvac indicator query APIs"
```

---

### Task 8: Full Regression and Real 19-Point Formula Smoke

**Files:**
- Create: `.scripts/simulate-hvac-19-points.mjs`
- Modify: `src/env/docker-compose.yml`
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Test: all backend tests and Docker Compose smoke.

**Interfaces:**
- Consumes: MQTT topic `device/data/up`.
- Produces: one complete frozen minute for all 19 external aliases and four indicator results.

- [ ] **Step 1: Add deterministic 19-point publisher**

Publish each external alias every 10 seconds for 70 seconds with one shared timestamp per round:

```javascript
const POINTS = [
  ['WCR1', 'WCR1_TWin', 12.0],
  ['WCR1', 'WCR1_TWout', 7.0],
  ['WCR1', 'WCR1_Flow', 100.0],
  ['WCR1', 'WCR1_PPE', 100.0],
  ['WCR1', 'WCR1_Voltage', 380.0],
  ['WCR1', 'WCR1_Current', 100.0],
  ['WCR1', 'WCR1_PF', 0.9],
  ['TOWER1', 'TOWER1_TCWin', 35.0],
  ['TOWER1', 'TOWER1_TCWout', 30.0],
  ['TOWER1', 'TOWER1_TWB', 25.0],
  ['PUMP1', 'PUMP1_Flow', 100.0],
  ['PUMP1', 'PUMP1_Pout', 300000.0],
  ['PUMP1', 'PUMP1_Pin', 100000.0],
  ['PUMP1', 'PUMP1_Z', 1.0],
  ['PUMP1', 'PUMP1_Power', 10.0],
  ['AHU1', 'AHU1_TotalPress', 1000.0],
  ['AHU1', 'AHU1_EtaT', 60.0],
  ['WEATHER_GATEWAY', 'DBO_TDB', 30.0],
  ['WEATHER_GATEWAY', 'DBO_RH', 50.0],
];

const payload = (deviceId, pointCode, val, timestamp) => JSON.stringify({
  buildingId: 'BLD001',
  deviceId,
  pointCode,
  val,
  timestamp,
});
```

Use the same MQTT credentials/environment-variable pattern as `.scripts/simulate-devices.mjs`. Exit nonzero on connection or publish failure.

- [ ] **Step 2: Add Redis to the local infrastructure**

Add:

```yaml
  redis:
    image: redis:7.2-alpine
    container_name: iot-redis
    restart: always
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - ./redis-data:/data
```

Do not change the existing MySQL, EMQX, or TDengine service definitions.

- [ ] **Step 3: Document the formula smoke procedure**

Add exact commands:

```powershell
docker compose -f src/env/docker-compose.yml up -d
mvn spring-boot:run
node .scripts/simulate-hvac-19-points.mjs
```

Document expected successful values:

```text
WCR_COP    ≈ 5.805556
TOWER_EFF  = 50.000000
PUMP_EFF   ≈ 58.277778
AHU_POW_EFF≈ 0.462963
```

Document the three query endpoints and explain that the result appears after the source minute closes plus the configured 30-second finalization delay.

- [ ] **Step 4: Run all targeted tests**

```powershell
mvn "-Dtest=*FormulaTest,*Indicator*Test,HvacFormula*Test,TdengineHvacSchemaTest,TdengineHvacMinuteRepositoryTest" test
```

Expected: all targeted tests pass.

- [ ] **Step 5: Run the complete regression suite**

```powershell
mvn test
```

Expected:

```text
BUILD SUCCESS
Failures: 0
Errors: 0
```

The existing 77 tests and all newly added tests must pass.

- [ ] **Step 6: Run the real infrastructure smoke**

Start services, run the backend, publish the 19 points, and verify:

```sql
SELECT indicator_code, ts, val, data_quality, formula_version
FROM iot_telemetry.st_indicator_minute
ORDER BY ts DESC
LIMIT 4;
```

Then verify Redis keys:

```powershell
docker exec iot-redis redis-cli --scan --pattern "iot:indicator:latest:*"
```

Call:

```text
GET /api/hvac/buildings/BLD001/indicators/latest
GET /api/hvac/indicators/INDICATOR_WCR_COP_B1/history?from={minuteStart}&to={minuteStartPlus60000}
GET /api/hvac/indicators/INDICATOR_WCR_COP_B1/calculations/{minuteStart}
```

Expected: four successful indicators, correct quality, correct formula versions, and explain steps matching the golden values.

- [ ] **Step 7: Verify failure behavior**

Run a second minute omitting `PUMP1_Power`. Verify:

- no `PUMP_EFF` success row for that minute;
- `st_formula_calc_exception` contains `MISSING_INPUT`;
- Redis latest pump state has `value=null`;
- WebSocket publishes the missing-input state;
- the other three indicators still succeed.

- [ ] **Step 8: Check repository cleanliness**

```powershell
git diff --check
git status --short
```

Expected: only intentional formula-engine files are modified. The existing `outputs/能效碳效智慧管控平台-Demo项目总结汇报.pptx` remains untouched and untracked.

- [ ] **Step 9: Commit**

```powershell
git add .scripts/simulate-hvac-19-points.mjs src/env/docker-compose.yml docs/MQTT-硬件数据对接说明.md
git commit -m "test: verify 19 point formula pipeline"
```

---

## Plan Self-Review Checklist

- Every enabled formula maps to an implementation task and a golden-vector test.
- Formula 5-3, quality generation 1/2, system COP, and control remain excluded.
- Primary-invalid behavior is distinct from primary-missing fallback behavior.
- Chiller and pump `GW/PPE` identities remain equipment/component scoped.
- Normal events perform no redundant minute query.
- Recovery events and formula gap recovery reload complete frozen minutes.
- TDengine writes use source minutes and propagate failures.
- The main indicator stable remains compact.
- Redis and WebSocket occur only after persistence.
- Older recovery results cannot replace a newer latest cache state.
- Historical explain mode rejects unknown formula versions.
- Permissions and building scope are enforced in service code.
- Every task has a focused failing test, passing command, and commit.
