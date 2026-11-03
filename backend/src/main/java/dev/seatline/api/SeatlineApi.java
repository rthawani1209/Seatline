package dev.seatline.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SeatlineApi {

    public static void main(String[] args) {
        SpringApplication.run(SeatlineApi.class, args);
    }
}
