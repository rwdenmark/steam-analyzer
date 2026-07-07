package com.rwdenmark.steamanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Boots the full application context, which the slice tests never do. This is what
// catches wiring failures like duplicate bean names before they reach a deploy.
@SpringBootTest(properties = "steam.api-key=test-key")
class SteamAnalyzerApplicationTests {

    @Test
    void contextLoads() {
    }
}
