package com.vajra.common;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Runs the check before any bean (DataSource, Flyway) is created. */
@AutoConfiguration
public class RequiredEnvironmentAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor requiredEnvironmentCheck(Environment environment) {
        return beanFactory -> RequiredEnvironment.check(environment);
    }
}
