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

    /**
     * 根据同建筑、同类型的全部历史编码分配下一个正整数编号。
     *
     * <p>调用方必须传入包含逻辑删除记录的编码集合；不符合“前缀 + 正整数”的历史值
     * 会被忽略。若最大编号溢出则让算术异常向上抛出，不能静默复用旧编号。</p>
     */
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
