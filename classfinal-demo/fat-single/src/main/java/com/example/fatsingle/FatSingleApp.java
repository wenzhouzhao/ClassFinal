package com.example.fatsingle;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 单模块 fat jar 示例（默认 layout=JAR，依赖打进 BOOT-INF/lib）。
 */
@SpringBootApplication
public class FatSingleApp {

    public static void main(String[] args) {
        SpringApplication.run(FatSingleApp.class, args);
    }

    @Bean
    CommandLineRunner run(GreetService greetService) {
        return args -> System.out.println("RESULT=" + greetService.greet("world"));
    }
}
