package com.example.thinmulti.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 多模块 thin jar 示例（layout=ZIP -> PropertiesLauncher，依赖外置到 target/lib）。
 */
@SpringBootApplication
public class ThinMultiApp {

    public static void main(String[] args) {
        SpringApplication.run(ThinMultiApp.class, args);
    }

    @Bean
    CommandLineRunner run(AppRunner appRunner) {
        return args -> System.out.println("RESULT=" + appRunner.go());
    }
}
