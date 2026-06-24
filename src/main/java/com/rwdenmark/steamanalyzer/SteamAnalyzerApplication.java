package com.rwdenmark.steamanalyzer;

import com.rwdenmark.steamanalyzer.config.SteamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(SteamProperties.class)
public class SteamAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SteamAnalyzerApplication.class, args);
    }
}
