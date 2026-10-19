package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Every skater who dressed in his club's recent games, with his role then and before")
public record RoleChangeListResponse(
        @Schema(description = "The season measured; absent when no game is logged at all") Integer season,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "N in each club's last N games")
                int recentGames,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unsorted: the client ranks")
                List<PlayerRoleChangeResponse> players) {}
