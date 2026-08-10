package com.platform.iot.websocket;

import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 Jakarta WebSocket 容器创建的端点实例交给当前 Spring 容器完成构造器注入。
 *
 * <p>嵌入式 Tomcat 不会建立传统 WAR 应用的根 {@code WebApplicationContext} 属性，
 * 因而不能使用依赖该属性的通用 Spring 配置器。Spring 管理的本类实例先登记 BeanFactory，
 * Tomcat 随后创建的配置器实例再通过同一引用创建端点；应用关闭时只清理自己登记的引用，
 * 避免测试重启或同 JVM 多上下文残留旧容器。</p>
 */
public class HvacRealtimeEndpointConfigurator
        extends ServerEndpointConfig.Configurator
        implements ApplicationContextAware, DisposableBean {

    private static final AtomicReference<AutowireCapableBeanFactory> BEAN_FACTORY =
            new AtomicReference<>();

    private AutowireCapableBeanFactory ownedBeanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext)
            throws BeansException {
        ownedBeanFactory = applicationContext.getAutowireCapableBeanFactory();
        BEAN_FACTORY.set(ownedBeanFactory);
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass)
            throws InstantiationException {
        AutowireCapableBeanFactory beanFactory = BEAN_FACTORY.get();
        if (beanFactory == null) {
            throw new InstantiationException("HVAC realtime Spring context is unavailable");
        }
        try {
            return beanFactory.createBean(endpointClass);
        } catch (BeansException exception) {
            InstantiationException failure = new InstantiationException(
                    "Unable to create HVAC realtime endpoint");
            failure.initCause(exception);
            throw failure;
        }
    }

    @Override
    public void destroy() {
        BEAN_FACTORY.compareAndSet(ownedBeanFactory, null);
        ownedBeanFactory = null;
    }
}
