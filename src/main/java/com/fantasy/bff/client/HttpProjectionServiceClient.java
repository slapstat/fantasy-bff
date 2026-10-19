package com.fantasy.bff.client;

import com.fantasy.bff.dto.request.GameRange;
import com.fantasy.bff.generated.projection.model.GoalieProjectionResponse;
import com.fantasy.bff.generated.projection.model.GoalieSplitResponse;
import com.fantasy.bff.generated.projection.model.PlayerResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonGoalieResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSkaterResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSnapshotsResponse;
import com.fantasy.bff.generated.projection.model.RangeProjectionsResponse;
import com.fantasy.bff.generated.projection.model.RoleChangesResponse;
import com.fantasy.bff.generated.projection.model.ScheduleStrengthResponse;
import com.fantasy.bff.generated.projection.model.ScheduleWeeksResponse;
import com.fantasy.bff.generated.projection.model.SkaterProjectionResponse;
import com.fantasy.bff.generated.projection.model.SkaterSplitResponse;
import com.fantasy.bff.generated.projection.model.SplitSeasonsResponse;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * {@link ProjectionServiceClient} that talks to fantasy-projection-service over HTTP.
 *
 * The service is NHL-id-keyed and league-agnostic — it returns raw projected stat lines
 * ({@code nhl_id} + the projected stats). Mapping NHL ids to the platform's player id and
 * seeding the user's projection happens in the caller (a later slice), not here.
 *
 * Uses model classes generated from specs/fantasy-projection-service-openapi.json — if the
 * service API changes, update the spec and re-run ./gradlew generateProjectionClient.
 */
@Component
public class HttpProjectionServiceClient implements ProjectionServiceClient {

    private static final ParameterizedTypeReference<List<SkaterProjectionResponse>> SKATER_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<GoalieProjectionResponse>> GOALIE_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<RestOfSeasonSkaterResponse>> REST_SKATER_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<RestOfSeasonGoalieResponse>> REST_GOALIE_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<PlayerResponse>> PLAYER_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<SkaterSplitResponse>> SKATER_SPLITS =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<GoalieSplitResponse>> GOALIE_SPLITS =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public HttpProjectionServiceClient(@Qualifier("projectionServiceClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<SkaterProjectionResponse> skaterProjections(int season, String modelVersion) {
        return restClient.get()
                .uri(b -> version(b.path("/api/v1/projections/skaters").queryParam("season", season), modelVersion))
                .retrieve()
                .body(SKATER_LIST);
    }

    @Override
    public List<GoalieProjectionResponse> goalieProjections(int season, String modelVersion) {
        return restClient.get()
                .uri(b -> version(b.path("/api/v1/projections/goalies").queryParam("season", season), modelVersion))
                .retrieve()
                .body(GOALIE_LIST);
    }

    @Override
    public List<RestOfSeasonSkaterResponse> restOfSeasonSkaters(int season) {
        return restOfSeason("/api/v1/rest-of-season/skaters", season, null, REST_SKATER_LIST);
    }

    @Override
    public List<RestOfSeasonGoalieResponse> restOfSeasonGoalies(int season) {
        return restOfSeason("/api/v1/rest-of-season/goalies", season, null, REST_GOALIE_LIST);
    }

    @Override
    public List<RestOfSeasonSkaterResponse> restOfSeasonSkaters(int season, LocalDate asOf) {
        return restOfSeason("/api/v1/rest-of-season/skaters", season, asOf, REST_SKATER_LIST);
    }

    @Override
    public List<RestOfSeasonGoalieResponse> restOfSeasonGoalies(int season, LocalDate asOf) {
        return restOfSeason("/api/v1/rest-of-season/goalies", season, asOf, REST_GOALIE_LIST);
    }

    @Override
    public RestOfSeasonSnapshotsResponse restOfSeasonSnapshots(int season) {
        return restClient.get()
                .uri(b -> b.path("/api/v1/rest-of-season/snapshots").queryParam("season", season).build())
                .retrieve()
                .body(RestOfSeasonSnapshotsResponse.class);
    }

    /**
     * A 404 is projection-service saying the season is not under way, or, asked for a day, that no
     * run that early is kept: an answer rather than a fault, so the caller gets an empty list.
     */
    private <T> List<T> restOfSeason(
            String path, int season, LocalDate asOf, ParameterizedTypeReference<List<T>> type) {
        try {
            List<T> rows = restClient.get()
                    .uri(b -> {
                        b.path(path).queryParam("season", season);
                        if (asOf != null) {
                            b.queryParam("as_of", asOf);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(type);
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound notKept) {
            return List.of();
        }
    }

    /**
     * Sends {@code model_version} only when one is pinned. Left off, projection-service answers
     * with the season's most recent run, which is the version that actually has rows: the model
     * version the code carries changes the moment it deploys, but its rows do not exist until a
     * projection run writes them. Asking for a named version is the rollback path, not the
     * everyday one.
     */
    private static URI version(UriBuilder builder, String modelVersion) {
        if (modelVersion != null && !modelVersion.isBlank()) {
            builder.queryParam("model_version", modelVersion);
        }
        return builder.build();
    }

    @Override
    public List<PlayerResponse> activePlayers(Integer season) {
        return restClient.get()
                .uri(b -> {
                    b.path("/api/v1/players").queryParam("active", true);
                    if (season != null) {
                        b.queryParam("season", season);
                    }
                    return b.build();
                })
                .retrieve()
                .body(PLAYER_LIST);
    }

    @Override
    public List<PlayerResponse> retiredPlayers() {
        // active=false lifts the filter rather than inverting it, so the endpoint hands back
        // everyone and the inactive ones are picked out here.
        List<PlayerResponse> all = restClient
                .get()
                .uri(b -> b.path("/api/v1/players").queryParam("active", false).build())
                .retrieve()
                .body(PLAYER_LIST);
        return all == null
                ? List.of()
                : all.stream().filter(p -> !Boolean.TRUE.equals(p.getIsActive())).toList();
    }

    @Override
    public List<SkaterSplitResponse> skaterSplits(Integer season, GameRange range, int limit) {
        return restClient.get()
                .uri(b -> splits(b, "/api/v1/splits/skaters", season, range, limit))
                .retrieve()
                .body(SKATER_SPLITS);
    }

    @Override
    public List<GoalieSplitResponse> goalieSplits(Integer season, GameRange range, int limit) {
        return restClient.get()
                .uri(b -> splits(b, "/api/v1/splits/goalies", season, range, limit))
                .retrieve()
                .body(GOALIE_SPLITS);
    }

    @Override
    public SplitSeasonsResponse splitSeasons(Integer targetSeason) {
        return restClient.get()
                .uri(b -> {
                    b.path("/api/v1/splits/seasons");
                    if (targetSeason != null) {
                        b.queryParam("target_season", targetSeason);
                    }
                    return b.build();
                })
                .retrieve()
                .body(SplitSeasonsResponse.class);
    }

    @Override
    public RoleChangesResponse skaterRoleChanges(Integer season, int recentGames) {
        return restClient.get()
                .uri(b -> {
                    b.path("/api/v1/role-changes/skaters").queryParam("recent_games", recentGames);
                    if (season != null) {
                        b.queryParam("season", season);
                    }
                    return b.build();
                })
                .retrieve()
                .body(RoleChangesResponse.class);
    }

    @Override
    public RangeProjectionsResponse rangeProjections(LocalDate start, LocalDate end, int limit) {
        return restClient.get()
                .uri(b -> b.path("/api/v1/projections/range")
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(RangeProjectionsResponse.class);
    }

    @Override
    public ScheduleWeeksResponse scheduleWeeks() {
        return restClient.get()
                .uri("/api/v1/schedule/weeks")
                .retrieve()
                .body(ScheduleWeeksResponse.class);
    }

    @Override
    public ScheduleStrengthResponse scheduleStrength(LocalDate start, LocalDate end) {
        return restClient.get()
                .uri(b -> b.path("/api/v1/schedule/strength")
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .retrieve()
                .body(ScheduleStrengthResponse.class);
    }

    /**
     * Unset bounds are left off the query entirely rather than sent as nulls — the service
     * reads an absent bound as "the whole season", and rejects a request that carries both a
     * last_games shorthand and an explicit range. An unset season is left off too: the service
     * then reads the newest season with a game played.
     */
    private static URI splits(
            UriBuilder builder, String path, Integer season, GameRange range, int limit) {
        builder.path(path).queryParam("limit", limit);
        if (season != null) {
            builder.queryParam("season", season);
        }
        if (range.fromGame() != null) {
            builder.queryParam("from_game", range.fromGame());
        }
        if (range.toGame() != null) {
            builder.queryParam("to_game", range.toGame());
        }
        if (range.lastGames() != null) {
            builder.queryParam("last_games", range.lastGames());
        }
        return builder.build();
    }
}
