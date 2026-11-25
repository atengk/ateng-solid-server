package io.github.atengk.solid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ateng-solid 单体聚合启动主类，负责装配公共原语、认证域与系统管理域模块。
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.github.atengk.solid")
public class SolidApplication {

    /**
     * 应用主入口
     *
     * @param args 启动命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SolidApplication.class, args);
    }
}
