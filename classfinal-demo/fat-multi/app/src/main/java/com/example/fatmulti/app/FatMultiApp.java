package com.example.fatmulti.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 多模块 fat jar 示例（默认 layout=JAR，lib 模块打进 BOOT-INF/lib）。
 */
@SpringBootApplication
public class FatMultiApp {

    public static void main(String[] args) {
        SpringApplication.run(FatMultiApp.class, args);
    }

    @Bean
    CommandLineRunner run(AppRunner appRunner) {
        return args -> System.out.println("RESULT=" + appRunner.go());
    }
}
