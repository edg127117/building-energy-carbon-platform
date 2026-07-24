package com.platform.hvac.service;

import com.platform.hvac.model.entity.BizPointNamingRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 校验标准测点编码是否符合所选命名模板。 */
@Component
public class PointCodeNamingValidator {

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
