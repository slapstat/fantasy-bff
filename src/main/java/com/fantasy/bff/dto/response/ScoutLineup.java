package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where today's Daily Faceoff lineup page lists a player: an editor's page, read through the
 * model's own gate, reported for the client to filter on and never scored.
 */
@Schema(description = "Where today's lineup page lists a player: his line or pair and his special-teams units")
public record ScoutLineup(
        @Schema(description = "f1-f4 for the forward lines, d1-d3 for the pairs; absent when the page lists "
                + "him only on special teams or out of the lineup",
                allowableValues = {"f1", "f2", "f3", "f4", "d1", "d2", "d3"})
                String line,
        @Schema(description = "1 or 2 for the power-play unit; absent for neither") Integer powerPlayUnit,
        @Schema(description = "1 or 2 for the penalty-kill unit; absent for neither") Integer penaltyKillUnit) {}
