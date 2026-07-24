package com.platform.hvac.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建筑内设备业务编号分配器。
 *
 * <p>只识别“固定前缀 + 正整数”形式，扫描时必须包含逻辑删除记录，
 * 因而退役设备的编号不会被新设备复用。</p>
 */
@Component
public class EquipmentCodeAllocator {

    public String next(String prefix, Collection<String> historicalCodes) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("设备编码前缀不能为空");
        }
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "([1-9]\\d*)");
        long maximum = 0;
        for (String code : historicalCodes) {
            if (code == null) continue;
            Matcher matcher = pattern.matcher(code);
            if (matcher.matches()) {
                maximum = Math.max(maximum, Long.parseLong(matcher.group(1)));
            }
        }
        return prefix + Math.addExact(maximum, 1);
    }
}
