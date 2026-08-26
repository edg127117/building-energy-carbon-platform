package com.platform.relation;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "relation-governance")
/** 关系治理的环境安全开关和有界查询限制。 */
public class RelationGovernanceProperties {
    private static final Set<String> ENVIRONMENTS = Set.of("development", "test", "production");

    private String deploymentEnvironment = "unspecified";
    private boolean selfApprovalEnabled;
    private int maxContextDepth = 3;
    private int maxPageSize = 100;

    /** 生产部署绝不能携带研发自审例外，错误组合直接阻止应用启动。 */
    @PostConstruct
    void validateProductionSafety() {
        if (deploymentEnvironment == null
                || !ENVIRONMENTS.contains(deploymentEnvironment.toLowerCase())) {
            throw new IllegalStateException("必须显式配置关系治理部署环境");
        }
        if ("production".equalsIgnoreCase(deploymentEnvironment) && selfApprovalEnabled) {
            throw new IllegalStateException("生产环境禁止启用关系治理研发自审");
        }
        if (maxContextDepth < 1 || maxContextDepth > 10 || maxPageSize < 1 || maxPageSize > 500) {
            throw new IllegalStateException("关系治理查询限制配置不合法");
        }
    }
}
