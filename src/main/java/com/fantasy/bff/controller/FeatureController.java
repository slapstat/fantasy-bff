package com.fantasy.bff.controller;

import com.fantasy.bff.dto.response.FeaturesResponse;
import com.fantasy.bff.service.AiProjectionAvailability;
import com.fantasy.bff.service.DraftAnalysisAvailability;
import com.fantasy.bff.service.PlayerScoutAvailability;
import com.fantasy.bff.service.RestOfSeasonPresetAvailability;
import com.fantasy.bff.service.RoleChangesAvailability;
import com.fantasy.bff.service.StartBenchAvailability;
import com.fantasy.bff.service.WhosHotAvailableFilterAvailability;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this environment serves, for a client deciding what to offer. Read from configuration
 * alone, so it costs no downstream call and cannot be slowed by one; that is why it is not a
 * field on {@code /api/v1/versions}, which probes every service it reports.
 */
@RestController
@RequestMapping("/api/v1/features")
@Tag(name = "Features", description = "Which features this environment serves")
public class FeatureController {

    private final AiProjectionAvailability aiProjection;
    private final WhosHotAvailableFilterAvailability whosHotAvailableFilter;
    private final RestOfSeasonPresetAvailability restOfSeasonPreset;
    private final DraftAnalysisAvailability draftAnalysis;
    private final StartBenchAvailability startBench;
    private final PlayerScoutAvailability playerScout;
    private final RoleChangesAvailability roleChanges;

    public FeatureController(
            AiProjectionAvailability aiProjection,
            WhosHotAvailableFilterAvailability whosHotAvailableFilter,
            RestOfSeasonPresetAvailability restOfSeasonPreset,
            DraftAnalysisAvailability draftAnalysis,
            StartBenchAvailability startBench,
            PlayerScoutAvailability playerScout,
            RoleChangesAvailability roleChanges) {
        this.aiProjection = aiProjection;
        this.whosHotAvailableFilter = whosHotAvailableFilter;
        this.restOfSeasonPreset = restOfSeasonPreset;
        this.draftAnalysis = draftAnalysis;
        this.startBench = startBench;
        this.playerScout = playerScout;
        this.roleChanges = roleChanges;
    }

    @GetMapping
    @Operation(summary = "Get the features this environment serves",
            description = "Public, since pages a signed-out visitor can open describe these features too. "
                    + "The same answer the endpoints behind each feature enforce.")
    @ApiResponse(responseCode = "200", description = "Features retrieved successfully")
    public FeaturesResponse getFeatures() {
        return new FeaturesResponse(
                aiProjection.available(), whosHotAvailableFilter.available(),
                restOfSeasonPreset.available(),
                draftAnalysis.available(),
                startBench.available(),
                playerScout.available(),
                roleChanges.available());
    }
}
