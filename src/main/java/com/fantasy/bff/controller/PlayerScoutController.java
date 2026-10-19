package com.fantasy.bff.controller;

import com.fantasy.bff.dto.response.PlayerScoutListResponse;
import com.fantasy.bff.dto.response.PlayerScoutTeamResponse;
import com.fantasy.bff.service.PlayerIdSpace;
import com.fantasy.bff.service.PlayerScoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player Scout: every player in a league with the model's view of him now and as it stood on a
 * chosen day. Signed in, and served only where
 * {@code PLAYER_SCOUT_ENABLED} and the AI projection say so; elsewhere its routes are a 404.
 */
@RestController
@Validated
@RequestMapping("/api/v1/player-scout")
@Tag(name = "Player scout", description = "How the model's view of every player in a league has moved, and why")
public class PlayerScoutController {

    private final PlayerScoutService scoutService;

    public PlayerScoutController(PlayerScoutService scoutService) {
        this.scoutService = scoutService;
    }

    @Operation(
            operationId = "playerScoutPlayers",
            summary = "Every player in a league, with the model's rest of the season now and on a chosen day",
            description = "The league's available players and every team's roster, each with the model's "
                    + "rest of the season today and its line for him as it stood on `since` (the newest "
                    + "day kept on or before it, else the preseason line), rescaled to the games his club "
                    + "has left today. Both lines are in the projection's own stat vocabulary: score them with the league's "
                    + "settings. Joined on identity, so the answer does not depend on which platform the "
                    + "player pool is served from.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The league's players the model projects"),
        @ApiResponse(responseCode = "404", description = "Player Scout is off in this environment")
    })
    @GetMapping("/players")
    public PlayerScoutListResponse players(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Which platform's league to read") @RequestParam PlayerIdSpace platform,
            @Parameter(description = "The Yahoo league key or the ESPN league id")
                    @RequestParam @NotBlank @Size(max = 64) String leagueId,
            @Parameter(description = "The day to compare with; left out, the model's preseason line")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate since) {
        return scoutService.players(userId, platform, leagueId, since);
    }

    @Operation(
            operationId = "playerScoutMyTeam",
            summary = "The user's own team in a league, with the model's rest of the season and the schedule",
            description = "Each player on the user's team today, bench and injured reserve included, with "
                    + "the model's rest of the season on the same line the league's players carry, and "
                    + "every club's remaining nights: seat the roster night by night to weigh what each "
                    + "player's place in the lineup is worth. Read live from the platform.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The user's team, or found=false"),
        @ApiResponse(responseCode = "404", description = "Player Scout is off in this environment")
    })
    @GetMapping("/my-team")
    public PlayerScoutTeamResponse myTeam(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Which platform's league to read") @RequestParam PlayerIdSpace platform,
            @Parameter(description = "The Yahoo league key or the ESPN league id")
                    @RequestParam @NotBlank @Size(max = 64) String leagueId) {
        return scoutService.myTeam(userId, platform, leagueId);
    }
}
