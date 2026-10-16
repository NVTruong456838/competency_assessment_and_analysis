package vn.smartassessment.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartAssessmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartAssessmentApplication.class, args);
    }
}
