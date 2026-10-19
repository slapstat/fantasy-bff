package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * One skater's recent usage beside his baseline. Measured, not projected: the change is in how his
 * coach plays him, which is what tends to move his production next.
 */
@Schema(description = "A skater's recent ice and power-play role beside his baseline")
public record PlayerRoleChangeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's player id")
                int playerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        String teamAbbrev,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Eligible at defence")
                boolean defence,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "His club's last N games")
                RoleWindow recent,
        @Schema(description = "What the recent games are compared with; absent for a call-up with no "
                + "earlier games")
                RoleWindow baseline,
        @Schema(description = "SEASON for his earlier games this season, LAST_SEASON while this season has "
                + "too few of them", allowableValues = {"SEASON", "LAST_SEASON"})
                String baselineSource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate firstRecentGameDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate lastRecentGameDate,
        @Schema(description = "Where the lineup page lists him now") LineupListing listedNow,
        @Schema(description = "Where the lineup page listed him before the recent games began")
                LineupListing listedBefore) {}
