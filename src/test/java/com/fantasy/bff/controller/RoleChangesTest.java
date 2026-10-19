package com.fantasy.bff.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fantasy.bff.BaseIntegrationTest;
import com.fantasy.bff.dto.response.RoleChangeListResponse;
import com.fantasy.bff.service.RoleChangeService;
import com.fantasy.bff.security.JwtTokenValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** With ROLE_CHANGES_ENABLED=true: served to a signed-in user, refused to anyone else. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "role-changes.enabled=true")
class RoleChangesTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenValidator jwtTokenValidator;

    @MockitoBean private RoleChangeService service;

    @Test
    void servesASignedInUserTheRecentStretchAsked() throws Exception {
        when(service.roleChanges(null, 10)).thenReturn(new RoleChangeListResponse(2026, 10, List.of()));
        String bearer = "Bearer " + jwtTokenValidator.generateToken("user-1", "a@example.com");

        mockMvc.perform(get("/api/v1/role-changes?recentGames=10").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.recentGames").value(10));
    }

    @Test
    void refusesARecentStretchOutsideItsBounds() throws Exception {
        String bearer = "Bearer " + jwtTokenValidator.generateToken("user-1", "a@example.com");

        mockMvc.perform(get("/api/v1/role-changes?recentGames=21").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void refusesASignedOutVisitor() throws Exception {
        mockMvc.perform(get("/api/v1/role-changes")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void theFeatureIsReportedOn() throws Exception {
        mockMvc.perform(get("/api/v1/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleChanges").value(true));
    }
}
