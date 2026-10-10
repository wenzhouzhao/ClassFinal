package com.example.jdk8boot2;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * JDK 8 + Spring Boot 2.6.3：老字节码（major 52）+ 老式嵌套 jar URL（jar:file:...）。
 * 代码保持 Java 8 语法（不使用 var / 文本块等）。
 */
@SpringBootApplication
public class Jdk8Boot2App {

    public static void main(String[] args) {
        SpringApplication.run(Jdk8Boot2App.class, args);
    }

    @Bean
    CommandLineRunner run(GreetService greetService) {
        return new CommandLineRunner() {
            @Override
            public void run(String... args) {
                System.out.println("RESULT=" + greetService.greet("world"));
            }
        };
    }
}
