package com.rwdenmark.steamanalyzer.controller;

import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.service.AnalyzerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final AnalyzerService analyzer;

    public ProfileController(AnalyzerService analyzer) {
        this.analyzer = analyzer;
    }

    /** Profile identity plus computed stats. Resolves a vanity name if needed. */
    @GetMapping("/{idOrVanity}")
    public ProfileSummary profile(@PathVariable String idOrVanity) {
        return analyzer.getProfile(idOrVanity);
    }

    /**
     * Owned games with playtime. sort is "playtime" (default), "least", or "name". enrich adds
     * each game's store type and free flag for the Free and Tools filters, off by default.
     */
    @GetMapping("/{idOrVanity}/library")
    public List<OwnedGame> library(@PathVariable String idOrVanity,
                                   @RequestParam(defaultValue = "playtime") String sort,
                                   @RequestParam(defaultValue = "false") boolean enrich) {
        return analyzer.getLibrary(idOrVanity, sort, enrich);
    }

    /** Up to two never-played games to start next. */
    @GetMapping("/{idOrVanity}/next")
    public List<OwnedGame> next(@PathVariable String idOrVanity) {
        return analyzer.getPlayNext(idOrVanity);
    }
}
