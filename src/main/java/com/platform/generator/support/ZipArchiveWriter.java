package com.platform.generator.support;

import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将内存中的生成文件封装成 UTF-8 ZIP 字节数组。
 *
 * <p>V1 不把生成代码写入项目目录，只返回下载内容。所有条目路径都会进行安全校验，
 * 防止绝对路径、盘符、反斜杠和 {@code ..} 路径穿越。</p>
 */
@Component
public class ZipArchiveWriter {
    /** 将“相对路径 -> 文件内容”写为可直接响应给浏览器的 ZIP。 */
    public byte[] write(Map<String, String> files) {
        if (files == null || files.isEmpty()) throw new BusinessException(400, "没有可下载的生成文件");
        Set<String> entries = new HashSet<>();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                String path = requireSafeEntry(file.getKey());
                if (!entries.add(path)) throw new BusinessException(400, "ZIP 中存在重复文件: " + path);
                zip.putNextEntry(new ZipEntry(path));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return buffer.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "生成 ZIP 文件失败");
        }
    }

    /** 校验 ZIP 条目只能是由生成器控制的正斜杠相对路径。 */
    private String requireSafeEntry(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("..") || path.contains(":") || path.contains("\\")) {
            throw new BusinessException(400, "ZIP 文件路径不安全");
        }
        return path;
    }
}
