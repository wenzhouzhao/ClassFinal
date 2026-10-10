package com.example.jdk21boot3;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * JDK 21 + Spring Boot 3.5.5：class file major 65 + nested: 嵌套 jar URL。
 */
@SpringBootApplication
public class Jdk21Boot3App {

    public static void main(String[] args) {
        SpringApplication.run(Jdk21Boot3App.class, args);
    }

    @Bean
    CommandLineRunner run(GreetService greetService) {
        return args -> System.out.println("RESULT=" + greetService.greet("world"));
    }
}
