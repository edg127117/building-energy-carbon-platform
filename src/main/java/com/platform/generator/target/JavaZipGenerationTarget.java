package com.platform.generator.target;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.generator.metadata.GeneratorNames;
import com.platform.generator.model.meta.GeneratorMetadata.ColumnMeta;
import com.platform.generator.model.meta.GeneratorMetadata.GenerationContext;
import com.platform.generator.template.FreemarkerTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 后端源码的内存输出目标。
 *
 * <p>根据同一份中立上下文生成 Entity、Mapper、Service、ServiceImpl、Controller 和 README。
 * 返回值仍是内存文件集合，ZIP 封装由 {@code ZipArchiveWriter} 独立负责。</p>
 */
@Component
@RequiredArgsConstructor
public class JavaZipGenerationTarget implements GenerationTarget {
    private static final String TEMPLATE_ROOT = "generator/java/";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final FreemarkerTemplateRenderer renderer;
    private final ObjectMapper objectMapper;

    /** 与 {@code gen_table.generate_mode} 对应的稳定模式名。 */
    @Override
    public String mode() { return "JAVA_ZIP"; }

    /** 渲染一套与当前项目 Spring Security、MyBatis-Plus 约定一致的后端代码。 */
    @Override
    public Map<String, String> generate(GenerationContext context) {
        String basePackage = context.table().packageName() + "." + context.table().moduleName();
        String basePath = "src/main/java/" + GeneratorNames.packagePath(basePackage) + "/";
        Map<String, Object> model = new LinkedHashMap<>();
        // FreeMarker 会把 Java record 访问器识别为可调用方法；先转为 Map，模板才能按普通属性
        // 稳定取值，同时避免模板直接绑定中立模型的 Java 实现细节。
        model.put("table", objectMapper.convertValue(context.table(), MAP_TYPE));
        model.put("primaryKey", objectMapper.convertValue(context.primaryKey(), MAP_TYPE));
        model.put("basePackage", basePackage);
        model.put("imports", context.imports().stream().sorted().toList());
        model.put("buildingScope", "BUILDING".equals(context.table().dataScope().type()));
        model.put("readExpression", roleExpression(context.table().permissions().readRoles()));
        model.put("writeExpression", roleExpression(context.table().permissions().writeRoles()));
        model.put("queryColumns", queryColumns(context).stream()
                .map(column -> objectMapper.convertValue(column, MAP_TYPE)).toList());

        // 所有路径均由通过校验的包名和类名组成，且只使用 ZIP 安全的正斜杠。
        Map<String, String> files = new LinkedHashMap<>();
        String className = context.table().className();
        files.put(basePath + "model/entity/" + className + ".java", render("entity.java.ftl", model));
        files.put(basePath + "mapper/" + className + "Mapper.java", render("mapper.java.ftl", model));
        files.put(basePath + "service/" + className + "Service.java", render("service.java.ftl", model));
        files.put(basePath + "service/impl/" + className + "ServiceImpl.java", render("serviceImpl.java.ftl", model));
        files.put(basePath + "controller/" + className + "Controller.java", render("controller.java.ftl", model));
        files.put("README.md", render("README.md.ftl", model));
        return Map.copyOf(files);
    }

    /** 统一补全模板根目录，避免各文件重复硬编码 classpath 路径。 */
    private String render(String template, Map<String, Object> model) {
        return renderer.render(TEMPLATE_ROOT + template, model);
    }

    /** 通用 keyword 只覆盖显式配置为 LIKE 的字符串字段，避免对数值和日期生成无效模糊查询。 */
    private List<ColumnMeta> queryColumns(GenerationContext context) {
        List<ColumnMeta> columns = new ArrayList<>();
        for (ColumnMeta column : context.table().columns()) {
            if (column.query() && "LIKE".equals(column.queryType()) && "String".equals(column.javaType())) {
                columns.add(column);
            }
        }
        return List.copyOf(columns);
    }

    /** 把配置角色转换为 Spring Security 可直接使用的 SpEL 表达式。 */
    private String roleExpression(List<String> roles) {
        if (roles.size() == 1) return "hasRole('" + roles.getFirst() + "')";
        return "hasAnyRole(" + roles.stream().map(role -> "'" + role + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow() + ")";
    }
}
