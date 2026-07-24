package com.platform.generator.template;

import com.platform.framework.exception.BusinessException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Map;

/**
 * FreeMarker 模板的统一渲染适配器。
 *
 * <p>集中规定 UTF-8 编码、异常日志和对外业务错误，避免每个输出目标重复处理模板细节。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreemarkerTemplateRenderer {
    private final Configuration configuration;

    /** 使用指定模型渲染 classpath 下的模板并返回源码文本。 */
    public String render(String templateName, Map<String, Object> model) {
        try {
            Template template = configuration.getTemplate(templateName, "UTF-8");
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("代码模板渲染失败: {}", templateName, e);
            throw new BusinessException(500, "代码模板渲染失败: " + templateName);
        }
    }
}
