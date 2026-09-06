package com.example.amaltea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AmalteaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmalteaApplication.class, args);
    }

}
