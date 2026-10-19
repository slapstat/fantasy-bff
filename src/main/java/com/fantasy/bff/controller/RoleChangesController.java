package com.fantasy.bff.controller;

import com.fantasy.bff.dto.response.RoleChangeListResponse;
import com.fantasy.bff.service.RoleChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role Changes: skaters whose ice or power-play role just moved. Signed in, not premium, and served
 * only where {@code ROLE_CHANGES_ENABLED} says so; elsewhere it is a 404.
 */
@RestController
@Validated
@RequestMapping("/api/v1/role-changes")
@Tag(name = "Role changes", description = "Skaters whose role at their club just changed")
public class RoleChangesController {

    private final RoleChangeService service;

    public RoleChangesController(RoleChangeService service) {
        this.service = service;
    }

    @Operation(
            operationId = "roleChanges",
            summary = "Every skater's recent ice and power-play role beside his baseline",
            description = "Measured, not projected. The recent stretch is each club's own last N games; the "
                    + "baseline is the skater's earlier games this season once he has five, else his last "
                    + "season. The power-play share is read against his club's power-play time, so a club "
                    + "drawing more power plays does not read as a promotion. Unsorted.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Every skater who dressed in the recent games"),
        @ApiResponse(responseCode = "404", description = "Role changes are off in this environment")
    })
    @GetMapping
    public RoleChangeListResponse roleChanges(
            @Parameter(description = "Season start year; defaults to the newest season with a game played")
                    @RequestParam(required = false)
                    @Min(1917)
                    @Max(2100)
                    Integer season,
            @Parameter(description = "N in each club's own last N games")
                    @RequestParam(defaultValue = "5")
                    @Min(1)
                    @Max(20)
                    int recentGames) {
        return service.roleChanges(season, recentGames);
    }
}
