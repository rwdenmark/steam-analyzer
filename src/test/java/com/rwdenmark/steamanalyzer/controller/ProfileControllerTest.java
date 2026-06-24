package com.rwdenmark.steamanalyzer.controller;

import com.rwdenmark.steamanalyzer.controller.ProfileController;
import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.error.NotFoundException;
import com.rwdenmark.steamanalyzer.error.PrivateProfileException;
import com.rwdenmark.steamanalyzer.service.AnalyzerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AnalyzerService analyzer;

    private static final String ID = "76561197960287930";

    @Test
    void returnsProfileIdentity() throws Exception {
        given(analyzer.getProfile(ID)).willReturn(
                new ProfileSummary(ID, "Rabscuttle", "avatar", "url", 1234567890L));

        mvc.perform(get("/api/profile/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steamId").value(ID))
                .andExpect(jsonPath("$.personaName").value("Rabscuttle"))
                .andExpect(jsonPath("$.createdAt").value(1234567890));
    }

    @Test
    void vanityMissReturns404() throws Exception {
        given(analyzer.getProfile(anyString()))
                .willThrow(new NotFoundException("No Steam account matches 'ghost'."));

        mvc.perform(get("/api/profile/{id}", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No Steam account matches 'ghost'."));
    }

    @Test
    void privateProfileReturns403() throws Exception {
        given(analyzer.getProfile(anyString()))
                .willThrow(new PrivateProfileException("This profile's Game details are private."));

        mvc.perform(get("/api/profile/{id}", ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("This profile's Game details are private."));
    }

    @Test
    void returnsLibrary() throws Exception {
        given(analyzer.getLibrary(ID, "playtime", false)).willReturn(List.of(
                OwnedGame.of(220, "Half-Life 2", 600, "img"),
                OwnedGame.of(400, "Portal", 0, "img")));

        mvc.perform(get("/api/profile/{id}/library", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Half-Life 2"));
    }

    @Test
    void returnsPlayNext() throws Exception {
        given(analyzer.getPlayNext(ID)).willReturn(List.of(
                OwnedGame.of(400, "Portal", 0, "img"),
                OwnedGame.of(620, "Portal 2", 0, "img")));

        mvc.perform(get("/api/profile/{id}/next", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Portal"))
                .andExpect(jsonPath("$[1].name").value("Portal 2"));
    }
}
