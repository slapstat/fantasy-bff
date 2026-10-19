package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One player in the league as Player Scout sees him: who holds him, the model's rest of the season
 * for him now and as it stood on the chosen day (rescaled to the games his club has left today).
 * The client scores both lines with the league's own settings: the distance between them is what makes a player worth a look.
 */
@Schema(description = "A player in the league: his owner and the model's view of him now and then")
public record PlayerScoutRow(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's player id")
                String playerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The model's NHL id for him")
                int nhlId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "The club he is on, in the platform's own spelling") String teamAbbrev,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "skater or goalie")
                String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Every position the league may start him at")
                List<String> positions,
        @Schema(description = "FREE_AGENT to add now, WAIVERS to claim first, UNKNOWN when the platform "
                + "does not say; absent for a rostered player")
                PlayerAvailability availability,
        @Schema(description = "The league team holding him; absent when the league has him available")
                ScoutOwner owner,
        @Schema(description = "The slot he sits in on his team today, in the platform's spelling; absent "
                + "for an available player or when the platform does not say")
                String slot,
        @Schema(description = "The platform's injury status; absent for a healthy player") String injuryStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "His club's games still to play today: the schedule both lines are spread over")
                int gamesRemaining,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The model's rest of the season today, lifted to the season line's scale")
                ScoutLine now,
        @Schema(description = "The model's line for him as it stood on the chosen day, rescaled to the "
                + "games his club has left today: its per-game rates over the share of those games it "
                + "expected him to play. Absent when the model had no line for him then.")
                ScoutLine then) {}
