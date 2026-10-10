package com.example.thinsingle;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 单模块 thin jar 示例（layout=ZIP -> PropertiesLauncher，依赖外置到 target/lib）。
 * 启动：java -Dloader.path=target/lib -jar target/xxx.jar
 */
@SpringBootApplication
public class ThinSingleApp {

    public static void main(String[] args) {
        SpringApplication.run(ThinSingleApp.class, args);
    }

    @Bean
    CommandLineRunner run(GreetService greetService) {
        return args -> System.out.println("RESULT=" + greetService.greet("world"));
    }
}
