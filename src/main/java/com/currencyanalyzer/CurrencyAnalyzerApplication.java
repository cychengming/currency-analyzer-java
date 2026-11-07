package com.currencyanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CurrencyAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CurrencyAnalyzerApplication.class, args);
    }
}
