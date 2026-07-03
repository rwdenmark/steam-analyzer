package com.rwdenmark.steamanalyzer.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness probe. CORS is limited to the origins that poll this endpoint for the demo failover. */
@RestController
public class HealthController {

    @CrossOrigin(origins = {
            "https://rwdenmark.github.io",
            "https://rdenmark.savannah-luma.ts.net"
    })
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
