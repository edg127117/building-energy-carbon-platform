package com.platform.relation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelationGovernancePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void allowsDevelopmentAndTestSelfApprovalException() {
        for (String environment : List.of("development", "test")) {
            contextRunner.withPropertyValues(
                            "relation-governance.deployment-environment=" + environment,
                            "relation-governance.self-approval-enabled=true",
                            "relation-governance.max-context-depth=4",
                            "relation-governance.max-page-size=99")
                    .run(context -> {
                        assertThat(context).hasSingleBean(RelationGovernanceProperties.class);
                        RelationGovernanceProperties properties =
                                context.getBean(RelationGovernanceProperties.class);

                        assertThat(properties.getDeploymentEnvironment()).isEqualTo(environment);
                        assertThat(properties.isSelfApprovalEnabled()).isTrue();
                        assertThat(properties.getMaxContextDepth()).isEqualTo(4);
                        assertThat(properties.getMaxPageSize()).isEqualTo(99);
                    });
        }
    }

    @Test
    void allowsProductionWhenSelfApprovalIsDisabled() {
        contextRunner.withPropertyValues(
                        "relation-governance.deployment-environment=production",
                        "relation-governance.self-approval-enabled=false")
                .run(context -> {
                    RelationGovernanceProperties properties =
                            context.getBean(RelationGovernanceProperties.class);
                    assertThat(properties.getDeploymentEnvironment()).isEqualTo("production");
                    assertThat(properties.isSelfApprovalEnabled()).isFalse();
                });
    }

    @Test
    void rejectsMissingDeploymentClassification() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("必须显式配置关系治理部署环境");
        });
    }

    @Test
    void rejectsProductionSelfApproval() {
        contextRunner.withPropertyValues(
                        "relation-governance.deployment-environment=production",
                        "relation-governance.self-approval-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("生产环境禁止启用关系治理研发自审");
                });
    }

    @Test
    void rejectsIllegalQueryLimits() {
        for (String property : List.of(
                "relation-governance.max-context-depth=0",
                "relation-governance.max-context-depth=11",
                "relation-governance.max-page-size=0",
                "relation-governance.max-page-size=501")) {
            contextRunner.withPropertyValues(
                            "relation-governance.deployment-environment=development", property)
                    .run(context -> assertThat(context)
                            .as("必须拒绝非法关系查询上限: %s", property)
                            .hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RelationGovernanceProperties.class)
    static class PropertiesConfiguration {
    }
}
