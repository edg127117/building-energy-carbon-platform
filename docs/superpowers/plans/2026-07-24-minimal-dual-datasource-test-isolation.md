# Minimal Dual Datasource and Test Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make MySQL and TDengine `JdbcTemplate` selection explicit and prevent ordinary tests from initializing or connecting to TDengine.

**Architecture:** Keep the existing monolith and both existing data sources. Add an explicit primary `mysqlJdbcTemplate`, qualify the charset fixer to it, and gate startup-only database actions with properties that are disabled in the test profile.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring JDBC, HikariCP, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Do not modify MQTT, ingestion, aggregation, COP, security, controller, database schema, or frontend behavior.
- Production defaults must preserve current behavior: MySQL charset repair and TDengine initialization remain enabled.
- The `test` profile must not execute MySQL charset DDL or TDengine initialization.
- TDengine repositories and `taosJdbcTemplate` remain available; this is not a module or microservice refactor.
- All existing 47 backend tests must continue to pass.

---

### Task 1: Lock the datasource and property contracts with tests

**Files:**
- Create: `src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java`
- Test: `src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java`

**Interfaces:**
- Consumes: `MysqlConfig`, `DatabaseCharsetFix`, and `TdengineConfig`.
- Produces: Tests requiring `MysqlConfig.mysqlJdbcTemplate(DataSource)` and the two exact `@ConditionalOnProperty` contracts.

- [ ] **Step 1: Write the failing configuration test**

```java
package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DataSourceIsolationConfigurationTest {

    @Test
    void mysqlConfigExposesJdbcTemplateBoundToPrimaryDatasource() {
        DataSource dataSource = mock(DataSource.class);

        JdbcTemplate template = new MysqlConfig().mysqlJdbcTemplate(dataSource);

        assertThat(template.getDataSource()).isSameAs(dataSource);
    }

    @Test
    void charsetFixCanBeDisabledByProperty() {
        ConditionalOnProperty condition =
                DatabaseCharsetFix.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("database.charset-fix");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    void tdengineInitializationCanBeDisabledByProperty() {
        Method runner = Arrays.stream(TdengineConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("initTaosDb"))
                .findFirst()
                .orElseThrow();
        ConditionalOnProperty condition =
                runner.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("tdengine");
        assertThat(condition.name()).containsExactly("initialization-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
mvn -Dtest=DataSourceIsolationConfigurationTest test
```

Expected: compilation fails because `MysqlConfig.mysqlJdbcTemplate(DataSource)` does not exist, proving the test detects the missing contract.

- [ ] **Step 3: Commit the red test**

```powershell
git add src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java
git commit -m "test: define datasource isolation contracts"
```

---

### Task 2: Make MySQL template selection explicit

**Files:**
- Modify: `src/main/java/com/platform/config/MysqlConfig.java`
- Modify: `src/main/java/com/platform/config/DatabaseCharsetFix.java`
- Test: `src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java`

**Interfaces:**
- Produces: Bean `mysqlJdbcTemplate` backed by Bean `dataSource`.
- Produces: `DatabaseCharsetFix(JdbcTemplate)` where the constructor parameter is qualified as `mysqlJdbcTemplate`.

- [ ] **Step 1: Add the explicit MySQL JdbcTemplate**

Add the imports:

```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
```

Add to `MysqlConfig`:

```java
@Primary
@Bean(name = "mysqlJdbcTemplate")
public JdbcTemplate mysqlJdbcTemplate(
        @Qualifier("dataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
}
```

- [ ] **Step 2: Qualify the charset fixer and add its property gate**

Replace field injection with:

```java
@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "database.charset-fix",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DatabaseCharsetFix {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCharsetFix(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
```

Keep the existing `fixDatabaseCharset()` behavior unchanged.

- [ ] **Step 3: Run the focused test**

Run:

```powershell
mvn -Dtest=DataSourceIsolationConfigurationTest test
```

Expected: the MySQL template and charset property tests pass; the TDengine property test still fails until Task 3.

- [ ] **Step 4: Commit the MySQL boundary**

```powershell
git add src/main/java/com/platform/config/MysqlConfig.java src/main/java/com/platform/config/DatabaseCharsetFix.java
git commit -m "fix: bind charset repair to mysql datasource"
```

---

### Task 3: Gate TDengine startup work and avoid connection-pool preheating

**Files:**
- Modify: `src/main/java/com/platform/config/TdengineConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java`

**Interfaces:**
- Produces: Property `tdengine.initialization-enabled`, default `true`.
- Produces: Property `database.charset-fix.enabled`, default `true`.

- [ ] **Step 1: Make the TDengine pool lazy when unused**

Add after `config.setMaximumPoolSize(20)`:

```java
config.setMinimumIdle(0);
config.setInitializationFailTimeout(-1);
```

- [ ] **Step 2: Gate the TDengine initialization runner**

Add the import:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

Annotate `initTaosDb`:

```java
@Bean
@ConditionalOnProperty(
        prefix = "tdengine",
        name = "initialization-enabled",
        havingValue = "true",
        matchIfMissing = true)
public CommandLineRunner initTaosDb(...) {
```

- [ ] **Step 3: Make production defaults explicit**

Add under `tdengine` in `application.yml`:

```yaml
  initialization-enabled: true
```

Add a top-level section:

```yaml
database:
  charset-fix:
    enabled: true
```

- [ ] **Step 4: Disable startup-only database work in tests**

Add to `application-test.yml`:

```yaml
database:
  charset-fix:
    enabled: false

tdengine:
  initialization-enabled: false
```

- [ ] **Step 5: Run the focused test**

Run:

```powershell
mvn -Dtest=DataSourceIsolationConfigurationTest test
```

Expected: all three tests pass.

- [ ] **Step 6: Commit TDengine test isolation**

```powershell
git add src/main/java/com/platform/config/TdengineConfig.java src/main/resources/application.yml src/test/resources/application-test.yml src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java
git commit -m "test: isolate tdengine startup from test profile"
```

---

### Task 4: Verify the full backend and absence of accidental TDengine startup

**Files:**
- Inspect: `target/surefire-reports/TEST-*.xml`
- Inspect: all files changed by Tasks 1-3

**Interfaces:**
- Consumes: the completed datasource and property contracts.
- Produces: verified backend with no ordinary-test dependency on `127.0.0.1:6041`.

- [ ] **Step 1: Run the complete backend suite**

Run:

```powershell
mvn test
```

Expected:

```text
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The count increases from 47 to 50 because the new configuration class adds three tests.

- [ ] **Step 2: Search test reports for forbidden startup behavior**

Run:

```powershell
rg -n -g "TEST-*.xml" "Connect to 127\\.0\\.0\\.1:6041|尝试连接并初始化 TDengine|CharsetFix.*6041" target/surefire-reports
```

Expected: no matches.

- [ ] **Step 3: Confirm only intended files changed**

Run:

```powershell
git status --short
git diff --check
```

Expected: no unrelated files and no whitespace errors.

- [ ] **Step 4: Commit any verification-only correction if required**

If verification required no source correction, do not create an empty commit. If a focused correction was needed, stage only the affected datasource/config/test files and commit:

```powershell
git commit -m "fix: complete datasource test isolation"
```
