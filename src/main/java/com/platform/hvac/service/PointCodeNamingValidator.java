package com.platform.hvac.service;

import com.platform.hvac.model.entity.BizPointNamingRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 将 MySQL 命名模板解释为平台标准测点编码规则。
 *
 * <p>测点档案服务在写库前调用；模板中的 {@code [n]} 只接受正整数序号，环境测点
 * 必须完整匹配模板，设备测点还必须带参数后缀。该组件只判断格式，不校验设备、
 * 系统分组或建筑关系。</p>
 */
@Component
public class PointCodeNamingValidator {

    /**
     * 判断调用方提交的测点编码是否符合所选规则。
     *
     * @return 规则或编码为空、模板不匹配时返回 {@code false}，由 Service 转换为 400
     */
    public boolean matches(BizPointNamingRule rule, String pointCode) {
        if (rule == null || pointCode == null || pointCode.isBlank()) {
            return false;
        }
        String template = rule.getCodeTemplate();
        int marker = template.indexOf("[n]");
        String baseRegex = marker < 0
                ? Pattern.quote(template)
                : Pattern.quote(template.substring(0, marker))
                    + "[1-9]\\d*"
                    + Pattern.quote(template.substring(marker + 3));
        boolean environment = "ENV".equalsIgnoreCase(rule.getComponentCode());
        String regex = environment
                ? "^" + baseRegex + "$"
                : "^" + baseRegex + "_[A-Za-z0-9]+$";
        return Pattern.compile(regex).matcher(pointCode).matches();
    }
}
