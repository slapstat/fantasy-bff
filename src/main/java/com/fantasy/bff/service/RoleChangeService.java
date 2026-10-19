package com.fantasy.bff.service;

import com.fantasy.bff.client.ProjectionServiceClient;
import com.fantasy.bff.dto.response.LineupListing;
import com.fantasy.bff.dto.response.PlayerRoleChangeResponse;
import com.fantasy.bff.dto.response.RoleChangeListResponse;
import com.fantasy.bff.dto.response.RoleWindow;
import com.fantasy.bff.generated.projection.model.LineupListingResponse;
import com.fantasy.bff.generated.projection.model.PlayerResponse;
import com.fantasy.bff.generated.projection.model.RoleChangesResponse;
import com.fantasy.bff.generated.projection.model.RoleWindowResponse;
import com.fantasy.bff.generated.projection.model.SkaterRoleChangeResponse;
import com.fantasy.bff.generated.projection.model.SkaterRoleChangeResponse.BaselineSourceEnum;
import com.fantasy.bff.service.PlayerSplitContextProvider.Context;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Whose role at his club just changed: each skater's recent ice and power-play share beside his
 * baseline, as projection-service measures them, joined to the platform's players the way Who's hot
 * joins its splits (through {@link PlayerSplitContextProvider}). A skater the pool cannot place is
 * left out, since the web has no row to show him on.
 */
@Service
public class RoleChangeService {

    private final ProjectionServiceClient projectionServiceClient;
    private final PlayerSplitContextProvider contextProvider;
    private final RoleChangesAvailability availability;

    public RoleChangeService(
            ProjectionServiceClient projectionServiceClient,
            PlayerSplitContextProvider contextProvider,
            RoleChangesAvailability availability) {
        this.projectionServiceClient = projectionServiceClient;
        this.contextProvider = contextProvider;
        this.availability = availability;
    }

    public RoleChangeListResponse roleChanges(Integer season, int recentGames) {
        availability.require();
        RoleChangesResponse measured = projectionServiceClient.skaterRoleChanges(season, recentGames);
        Context context = contextProvider.context();
        List<PlayerRoleChangeResponse> players = new ArrayList<>();
        for (SkaterRoleChangeResponse skater : measured.getSkaters()) {
            Integer playerId = context.platformId(skater.getNhlId());
            if (playerId == null) {
                continue;
            }
            PlayerResponse identity = context.identities().get(skater.getNhlId().longValue());
            String team = context.currentTeams().get(playerId);
            BaselineSourceEnum source = skater.getBaselineSource();
            players.add(new PlayerRoleChangeResponse(
                    playerId,
                    identity == null ? "" : identity.getFullName(),
                    team != null ? team : skater.getTeam(),
                    context.playsDefence(playerId),
                    window(skater.getRecent()),
                    window(skater.getBaseline()),
                    source == null ? null : source.name(),
                    skater.getFirstRecentGameDate(),
                    skater.getLastRecentGameDate(),
                    listing(skater.getListedNow()),
                    listing(skater.getListedBefore())));
        }
        return new RoleChangeListResponse(measured.getSeason(), recentGames, players);
    }

    private static RoleWindow window(RoleWindowResponse window) {
        if (window == null) {
            return null;
        }
        return new RoleWindow(
                window.getGames(),
                window.getToiPerGame().doubleValue(),
                number(window.getPpToiPerGame()),
                number(window.getPpShare()),
                number(window.getShToiPerGame()));
    }

    private static LineupListing listing(LineupListingResponse listing) {
        if (listing == null) {
            return null;
        }
        return new LineupListing(
                listing.getSeenOn(),
                listing.getLine(),
                listing.getPowerPlayUnit(),
                Boolean.TRUE.equals(listing.getOutOfLineup()));
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
