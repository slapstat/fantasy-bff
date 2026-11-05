package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Every player in a league the model projects, with its view of him now and on a chosen day")
public record PlayerScoutListResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int season,
        @Schema(description = "The model version the rest of the season came from") String modelVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether the season is under way. False before its first game and after "
                        + "its last, when there is no rest of the season and no player is listed.")
                boolean inSeason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Which earlier line the then-lines are, and what answered the day asked for")
                ScoutSince since,
        @Schema(description = "The day the lineup pages behind each row's lineup were read; absent when "
                + "none could be, and no row then carries one")
                LocalDate lineupSeenOn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Every player the model projects whom the league has available or on a "
                        + "team, in no ranked order: score them with the league's settings")
                List<PlayerScoutRow> players) {}
