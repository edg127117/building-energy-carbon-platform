package com.platform.generator;

import com.platform.generator.mapper.GenColumnMapper;
import com.platform.generator.mapper.GenTableMapper;
import com.platform.generator.model.dto.GeneratorDtos.ColumnUpdate;
import com.platform.generator.model.dto.GeneratorDtos.GeneratorConfigView;
import com.platform.generator.model.dto.GeneratorDtos.ImportTableRequest;
import com.platform.generator.model.dto.GeneratorDtos.UpdateGeneratorConfigRequest;
import com.platform.generator.service.GeneratorService;
import com.platform.generator.support.ZipArchiveWriter;
import com.platform.generator.target.JavaZipGenerationTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 输出目标验收测试：同时检查模板内容、ZIP 条目安全和生成源码的真实可编译性。
 */
@SpringBootTest
@ActiveProfiles("test")
class JavaZipGenerationTargetTest {
    @Autowired private GeneratorService service;
    @Autowired private GenTableMapper tableMapper;
    @Autowired private GenColumnMapper columnMapper;
    @Autowired private JavaZipGenerationTarget target;
    @Autowired private ZipArchiveWriter zipWriter;
    @TempDir Path tempDir;

    /** 只隔离生成配置，确保元数据读取仍针对测试库中的真实业务表。 */
    @BeforeEach
    void cleanGeneratorConfig() {
        columnMapper.delete(null);
        tableMapper.delete(null);
    }

    /** 一次 BUILDING 范围生成应得到六个文件，并保留项目现有角色与数据权限约定。 */
    @Test
    void should_render_six_safe_java_zip_entries() throws Exception {
        GeneratorConfigView imported = service.importTable(new ImportTableRequest(
                "biz_equipment", "hvacgenerated", "equipmentGenerated",
                "GeneratedEquipment", "com.platform"));
        List<ColumnUpdate> columns = imported.columns().stream().map(column -> new ColumnUpdate(
                column.id(), column.javaType(), column.javaField(), column.list(),
                "equip_name".equals(column.columnName()),
                "equip_name".equals(column.columnName()) ? "LIKE" : column.queryType(),
                column.edit(), column.required(), column.componentType(), column.sortOrder())).toList();
        service.update(imported.id(), new UpdateGeneratorConfigRequest(
                "hvacgenerated", "equipmentGenerated", "GeneratedEquipment", "com.platform",
                "INPUT", "del_flag", "BUILDING", "building_id",
                List.of("BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN"),
                List.of("PLATFORM_ADMIN"), columns));

        Map<String, String> files = target.generate(service.buildContext(imported.id()));
        assertThat(files).hasSize(6).containsKey("README.md");
        String entity = files.entrySet().stream().filter(e -> e.getKey().endsWith("GeneratedEquipment.java"))
                .findFirst().orElseThrow().getValue();
        String controller = files.entrySet().stream().filter(e -> e.getKey().endsWith("Controller.java"))
                .findFirst().orElseThrow().getValue();
        assertThat(entity).contains("@TableId(type = IdType.INPUT)", "@TableLogic", "private String equipId");
        assertThat(controller).contains("BuildingScopeService", "hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')");

        byte[] zip = zipWriter.write(files);
        Set<String> entries = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) entries.add(entry.getName());
        }
        assertThat(entries).containsExactlyInAnyOrderElementsOf(files.keySet());

        compileGeneratedJava(files);
    }

    /**
     * 把内存中的 Java 文件写入 JUnit 临时目录并调用当前 JDK 编译器。
     * 这能发现仅靠字符串断言无法识别的导包、泛型和方法引用错误。
     */
    private void compileGeneratedJava(Map<String, String> files) throws Exception {
        Path sources = tempDir.resolve("sources");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getKey().endsWith(".java")) continue;
            Path source = sources.resolve(file.getKey()).normalize();
            assertThat(source.startsWith(sources)).as("生成路径必须位于临时源码目录中").isTrue();
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(source);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("需要使用 JDK 运行生成源码编译测试").isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(javaFiles);
            String classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            List<String> options = List.of("--release", "21", "-proc:full",
                    "-classpath", classpath, "-d", classes.toString());
            boolean success = compiler.getTask(null, manager, diagnostics, options, null, units).call();
            String messages = diagnostics.getDiagnostics().stream()
                    .map(Object::toString).reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("");
            assertThat(success).as("生成源码应可被 JDK 21 编译:%n%s", messages).isTrue();
        }
    }
}
