package com.example.jdk17boot3;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * JDK 17 + Spring Boot 3.5.5：class file major 61 + nested: 嵌套 jar URL。
 */
@SpringBootApplication
public class Jdk17Boot3App {

    public static void main(String[] args) {
        SpringApplication.run(Jdk17Boot3App.class, args);
    }

    @Bean
    CommandLineRunner run(GreetService greetService) {
        return args -> System.out.println("RESULT=" + greetService.greet("world"));
    }
}
