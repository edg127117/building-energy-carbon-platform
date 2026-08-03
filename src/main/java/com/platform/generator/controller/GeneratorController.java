package com.platform.generator.controller;

import com.platform.framework.common.Result;
import com.platform.generator.model.dto.GeneratorDtos.GeneratorConfigView;
import com.platform.generator.model.dto.GeneratorDtos.ImportTableRequest;
import com.platform.generator.model.dto.GeneratorDtos.TableSummary;
import com.platform.generator.model.dto.GeneratorDtos.UpdateGeneratorConfigRequest;
import com.platform.generator.service.GeneratorService;
import com.platform.generator.support.ZipArchiveWriter;
import com.platform.generator.target.JavaZipGenerationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 平台管理员维护代码生成配置并取得生成结果的 HTTP 入口。
 *
 * <p>请求先交给 {@link GeneratorService} 读取业务库元数据或校验持久化配置，再由
 * {@link JavaZipGenerationTarget} 渲染后端源码。预览直接返回“路径到内容”的内存结果，
 * 下载再交给 {@link ZipArchiveWriter} 封装；两条路径都不会写入服务器工作区。</p>
 */
@RestController
@RequestMapping("/system/generator")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class GeneratorController {
    private final GeneratorService generatorService;
    private final JavaZipGenerationTarget javaZipTarget;
    private final ZipArchiveWriter zipArchiveWriter;

    /** 列出当前业务库中的可导入表，并标明哪些表已有生成配置。 */
    @GetMapping("/tables")
    public Result<List<TableSummary>> tables() {
        return Result.success(generatorService.listTables());
    }

    /** 导入一张表并根据数据库结构建立默认生成配置。 */
    @PostMapping("/import")
    public Result<GeneratorConfigView> importTable(@RequestBody ImportTableRequest request) {
        return Result.success(generatorService.importTable(request));
    }

    /** 查询表级和字段级完整配置。 */
    @GetMapping("/{id}")
    public Result<GeneratorConfigView> detail(@PathVariable Long id) {
        return Result.success(generatorService.detail(id));
    }

    /** 更新并校验完整生成配置。 */
    @PutMapping("/{id}")
    public Result<GeneratorConfigView> update(
            @PathVariable Long id,
            @RequestBody UpdateGeneratorConfigRequest request) {
        return Result.success(generatorService.update(id, request));
    }

    /** 删除生成配置；不会删除业务表或已生成到外部的代码。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        generatorService.delete(id);
        return Result.success();
    }

    /** 在内存中生成源码并以“路径 -> 内容”形式返回，便于页面预览。 */
    @PostMapping("/{id}/preview")
    public Result<Map<String, String>> preview(@PathVariable Long id) {
        return Result.success(javaZipTarget.generate(generatorService.buildContext(id)));
    }

    /** 在内存中生成同一批源码并封装成 ZIP 下载，全程不写入项目源目录。 */
    @PostMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        var context = generatorService.buildContext(id);
        byte[] zip = zipArchiveWriter.write(javaZipTarget.generate(context));
        String filename = context.table().className() + "-backend.zip";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(zip.length)
                .body(zip);
    }
}
