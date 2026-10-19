package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public record LeagueProjectionSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ScoringBasis scoringType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> activeScoringColumns,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> activeUtilityColumns,
        @Schema(description = "Per-stat point weights for points leagues; null for category leagues.")
        Map<String, Double> statWeights,
        @Schema(description = "The league's own name, so a synced projection can say where its "
                + "settings came from. Set for ESPN, whose settings response carries it; null for "
                + "Yahoo, where the client picked the league by name from the league list.")
        String leagueName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RosterSlots rosterSlots,
        @Schema(description = "League size from the Yahoo league's team count; null when unknown.")
        Integer leagueSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> unsupportedStats,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> unsupportedRosterCodes,
        @Schema(description = "The league's injured-reserve slots by the platform's code (IR, IR+, "
                + "IR-LT, IR-NR) and how many of each; not-active (NA) is not one. A player in one "
                + "takes no roster spot. Empty for a league without any.")
        Map<String, Integer> reserveSlots,
        @Schema(description = "How many players a team may add in one fantasy week, from the league's "
                + "own setting. Null when the league sets no maximum, and for ESPN, whose limit "
                + "espn-service does not read.", minimum = "1")
        Integer maxWeeklyAdds
) {}
