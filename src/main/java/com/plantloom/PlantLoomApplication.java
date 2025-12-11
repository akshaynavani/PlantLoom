package com.plantloom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PlantLoomApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantLoomApplication.class, args);
    }
}
