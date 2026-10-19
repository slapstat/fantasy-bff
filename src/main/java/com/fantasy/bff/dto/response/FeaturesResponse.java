package com.fantasy.bff.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record FeaturesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this environment serves the AI projection: the model's lines "
                        + "and a projection or preset draft seeded from them. False means neither is "
                        + "served to anyone, so a client should not offer it. It says nothing about "
                        + "this account's plan: an AI projection that needs premium is still available.")
        boolean aiProjection,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether Who's hot may show only the players a linked league has "
                        + "available. False means the rostered-players endpoint answers 404, so a "
                        + "client should not offer it.")
        boolean whosHotAvailableFilter,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether a draft may be started from the rest of the season "
                        + "(source=rest_of_season). False means it is refused, so a client should "
                        + "not offer it. True still offers it only while a season is under way, "
                        + "which `/projection-model/rest-of-season/status` says.")
        boolean restOfSeasonPreset,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this environment serves Draft Analysis, a league's draft graded "
                        + "against the model. False means its endpoint answers 404, so a client should "
                        + "not offer it.")
        boolean draftAnalysis,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this environment serves Start/Bench, the user's lineup for a "
                        + "day with the model's suggestion of who to start. False means its endpoint "
                        + "answers 404, so a client should not offer it.")
        boolean startBench,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this environment serves Player Scout, every player in a league "
                        + "with the model's view of him now and on a chosen day. False means its "
                        + "endpoints answer 404, so a client should not offer it.")
        boolean playerScout,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this environment serves Role Changes. False means its endpoint "
                        + "answers 404, so a client should not offer it.")
        boolean roleChanges
) {}
