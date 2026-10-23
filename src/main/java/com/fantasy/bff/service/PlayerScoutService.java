package com.fantasy.bff.service;

import com.fantasy.bff.client.ProjectionServiceClient;
import com.fantasy.bff.dto.response.ClubGames;
import com.fantasy.bff.dto.response.ClubNight;
import com.fantasy.bff.dto.response.PlannerRosterPlayer;
import com.fantasy.bff.dto.response.PlayerScoutListResponse;
import com.fantasy.bff.dto.response.PlayerScoutRow;
import com.fantasy.bff.dto.response.PlayerScoutTeamResponse;
import com.fantasy.bff.dto.response.ScoutLine;
import com.fantasy.bff.dto.response.ScoutMyTeamResponse;
import com.fantasy.bff.dto.response.ScoutOwner;
import com.fantasy.bff.dto.response.ScoutSince;
import com.fantasy.bff.generated.projection.model.GoalieProjectionResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonGoalieResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSkaterResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSnapshotsResponse;
import com.fantasy.bff.generated.projection.model.SkaterProjectionResponse;
import com.fantasy.bff.service.LeagueAvailablePlayers.Available;
import com.fantasy.bff.service.LeagueOwnTeam.LeagueTeam;
import com.fantasy.bff.service.LeagueOwnTeam.Rostered;
import com.fantasy.bff.service.mapping.PlayerIdResolver;
import com.fantasy.bff.service.scoring.LeagueSchedule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Player Scout: how the model's view of every player in a league has moved since a chosen day.
 *
 * <p>Each player goes out with two of the model's lines, both on the served rest of the season's
 * scale (lifted to the season line's, the season so far left out): today's, and the one as it
 * stood on the chosen day. That one is the model's expectation on the day, re-spread over the
 * games his club has left today and lifted by what today's line was lifted by
 * ({@link ScoutRescale#atTodaysLift}), so the distance between the two is what the model changed
 * its mind on and not two days' lifts. The day is answered from projection-service's day-by-day history, the
 * newest day kept on or before it; before the history began, or asked for no day, the model's
 * preseason line answers instead, and {@link ScoutSince} says which.
 *
 * <p>Nothing is scored here, as in the planner: the client ranks both lines by the league's own
 * settings, and the distance between them is what says a player has risen. Every player the
 * league holds is listed, available or rostered, each with who holds him; the client filters.
 */
@Service
public class PlayerScoutService {

    /** The model version projection-service freezes the eve of the season under. */
    static final String PRESEASON = ScoutLineService.PRESEASON;

    /** Games in the schedule when no club's count is known; the league has played 82 since 1995. */
    static final int SEASON_GAMES_FALLBACK = 82;

    private final ProjectionServiceClient projectionServiceClient;
    private final LeagueAvailablePlayers leagueAvailablePlayers;
    private final LeagueOwnTeam leagueTeams;
    private final NhlIdentityJoin identityJoin;
    private final ScoutLineService scoutLineService;
    private final SeasonScheduleService scheduleService;
    private final PlayerScoutAvailability availability;
    private final int season;

    public PlayerScoutService(
            ProjectionServiceClient projectionServiceClient,
            LeagueAvailablePlayers leagueAvailablePlayers,
            LeagueOwnTeam leagueTeams,
            NhlIdentityJoin identityJoin,
            ScoutLineService scoutLineService,
            SeasonScheduleService scheduleService,
            PlayerScoutAvailability availability,
            @Value("${services.projection.season}") int season) {
        this.projectionServiceClient = projectionServiceClient;
        this.leagueAvailablePlayers = leagueAvailablePlayers;
        this.leagueTeams = leagueTeams;
        this.identityJoin = identityJoin;
        this.scoutLineService = scoutLineService;
        this.scheduleService = scheduleService;
        this.availability = availability;
        this.season = season;
    }

    /**
     * Every player in the league the model projects, with its view of him now and on {@code since}
     * (null for the preseason line).
     */
    public PlayerScoutListResponse players(
            String userId, PlayerIdSpace platform, String leagueId, LocalDate since) {
        availability.require();
        List<RestOfSeasonSkaterResponse> skaters = projectionServiceClient.restOfSeasonSkaters(season);
        List<RestOfSeasonGoalieResponse> goalies = projectionServiceClient.restOfSeasonGoalies(season);
        if (skaters.isEmpty() && goalies.isEmpty()) {
            // Before the first game or after the last: no rest of the season, so the league is not
            // worth a call to the platform.
            return new PlayerScoutListResponse(
                    season, null, false, new ScoutSince(since, "NONE", null, null), List.of());
        }

        Holders holders = holders(userId, platform, leagueId);
        Then then = then(since);

        List<PlayerScoutRow> rows = new ArrayList<>();
        for (RestOfSeasonSkaterResponse row : skaters) {
            Holder holder = holders.of(row.getNhlId());
            if (holder != null && !holder.goalie()) {
                boolean defence = holder.playsDefence();
                ScoutLine now = ScoutLineService.skaterLine(ProjectionSeedService.seasonShape(row), defence);
                rows.add(holder.row(
                        row.getNhlId(), "skater", row.getGamesRemaining(), now, then.skater(row, now, defence)));
            }
        }
        for (RestOfSeasonGoalieResponse row : goalies) {
            Holder holder = holders.of(row.getNhlId());
            if (holder != null && holder.goalie()) {
                ScoutLine now = ScoutLineService.goalieLine(ProjectionSeedService.seasonShape(row));
                rows.add(holder.row(
                        row.getNhlId(), "goalie", row.getGamesRemaining(), now, then.goalie(row, now)));
            }
        }
        // A default order, not a ranking: the client scores them by the league's settings.
        rows.sort(Comparator.comparingDouble((PlayerScoutRow row) -> row.now().games())
                .reversed()
                .thenComparing(PlayerScoutRow::name));

        String modelVersion = !skaters.isEmpty()
                ? skaters.getFirst().getModelVersion()
                : goalies.getFirst().getModelVersion();
        return new PlayerScoutListResponse(
                season, modelVersion, true, then.since(), List.copyOf(rows));
    }

    /**
     * The user's own team on the rest-of-season line the league's players carry, every club's
     * remaining nights for the client to seat the roster over, and what a night against each
     * opponent at each venue is worth, so a player's games are not all counted alike.
     */
    public PlayerScoutTeamResponse myTeam(String userId, PlayerIdSpace platform, String leagueId) {
        availability.require();
        ScoutMyTeamResponse team = scoutLineService.restOfSeasonTeam(userId, platform, leagueId);
        SeasonScheduleService.SeasonNights nights = scheduleService.nights();
        List<ClubGames> schedule = new ArrayList<>();
        nights.window().forEach((club, games) ->
                schedule.add(new ClubGames(club, games.stream().map(ClubNight::date).toList(), games)));
        schedule.sort(Comparator.comparing(ClubGames::team));
        return new PlayerScoutTeamResponse(
                team.found(), team.teamName(), team.players(), List.copyOf(schedule), nights.worth());
    }

    // --- Who holds whom ---------------------------------------------------------------------------

    /** A player as the league has him: available, or on one of its teams. */
    private interface Holder {
        boolean goalie();

        boolean playsDefence();

        PlayerScoutRow row(int nhlId, String type, int gamesRemaining, ScoutLine now, ScoutLine then);
    }

    private record AvailableHolder(Available player) implements Holder {
        @Override
        public boolean goalie() {
            return player.goalie();
        }

        @Override
        public boolean playsDefence() {
            return player.playsDefence();
        }

        @Override
        public PlayerScoutRow row(int nhlId, String type, int gamesRemaining, ScoutLine now, ScoutLine then) {
            return new PlayerScoutRow(
                    player.playerId(),
                    nhlId,
                    player.name(),
                    player.teamAbbrev(),
                    type,
                    player.positions(),
                    player.availability(),
                    null,
                    null,
                    null,
                    gamesRemaining,
                    now,
                    then);
        }
    }

    private record RosteredHolder(LeagueTeam team, PlannerRosterPlayer player) implements Holder {
        @Override
        public boolean goalie() {
            return "goalie".equals(player.type());
        }

        @Override
        public boolean playsDefence() {
            return player.positions().contains("D");
        }

        @Override
        public PlayerScoutRow row(int nhlId, String type, int gamesRemaining, ScoutLine now, ScoutLine then) {
            return new PlayerScoutRow(
                    player.playerId(),
                    nhlId,
                    player.name(),
                    player.teamAbbrev(),
                    type,
                    player.positions(),
                    null,
                    new ScoutOwner(team.name(), team.mine()),
                    player.slot(),
                    player.injuryStatus(),
                    gamesRemaining,
                    now,
                    then);
        }
    }

    private record Holders(LeagueAvailablePlayers.Wire wire, Map<Integer, Holder> rostered) {
        Holder of(Integer nhlId) {
            Available available = wire.matched(nhlId);
            if (available != null) {
                return new AvailableHolder(available);
            }
            return nhlId == null ? null : rostered.get(nhlId);
        }
    }

    /**
     * The league's wire and every team's roster, each joined to the model's ids on identity. The
     * rosters are joined as one list, since a player sits on one team at a time.
     */
    private Holders holders(String userId, PlayerIdSpace platform, String leagueId) {
        List<LeagueTeam> teams = leagueTeams.readAll(userId, platform, leagueId);
        LeagueAvailablePlayers.Wire wire = leagueAvailablePlayers.read(userId, platform, leagueId, teams);
        Map<String, RosteredHolder> byPlatformId = new HashMap<>();
        List<NhlIdentityJoin.PlatformPlayer> players = new ArrayList<>();
        for (LeagueTeam team : teams) {
            for (Rostered rostered : team.players()) {
                PlannerRosterPlayer player = rostered.player();
                byPlatformId.put(player.playerId(), new RosteredHolder(team, player));
                players.add(new NhlIdentityJoin.PlatformPlayer(
                        player.playerId(), player.name(), player.teamAbbrev(), rostered.sweaterNumber()));
            }
        }
        Map<Integer, Holder> rostered = new HashMap<>();
        if (!players.isEmpty()) {
            identityJoin.join(players).nhlIdToPlatformId().forEach((nhlId, platformId) -> {
                RosteredHolder holder = byPlatformId.get(String.valueOf(platformId));
                if (holder != null) {
                    rostered.put(nhlId.intValue(), holder);
                }
            });
        }
        return new Holders(wire, rostered);
    }

    // --- The earlier line -------------------------------------------------------------------------

    /** The lines of the chosen day, by NHL id, with the schedule each was spread over. */
    private final class Then {
        private final ScoutSince since;
        private final Map<Integer, RestOfSeasonSkaterResponse> keptSkaters;
        private final Map<Integer, RestOfSeasonGoalieResponse> keptGoalies;
        private final Map<Integer, SkaterProjectionResponse> preseasonSkaters;
        private final Map<Integer, GoalieProjectionResponse> preseasonGoalies;
        private LeagueSchedule schedule;

        private Then(
                ScoutSince since,
                Map<Integer, RestOfSeasonSkaterResponse> keptSkaters,
                Map<Integer, RestOfSeasonGoalieResponse> keptGoalies,
                Map<Integer, SkaterProjectionResponse> preseasonSkaters,
                Map<Integer, GoalieProjectionResponse> preseasonGoalies) {
            this.since = since;
            this.keptSkaters = keptSkaters;
            this.keptGoalies = keptGoalies;
            this.preseasonSkaters = preseasonSkaters;
            this.preseasonGoalies = preseasonGoalies;
        }

        ScoutSince since() {
            return since;
        }

        /**
         * The skater's line on the chosen day, on today's footing and today's lift; {@code nowLine}
         * is today's served line, which the lift is read off beside today's expectation.
         */
        ScoutLine skater(RestOfSeasonSkaterResponse now, ScoutLine nowLine, boolean defence) {
            ScoutLine nowExpected =
                    ScoutLineService.skaterLine(ProjectionSeedService.expectationShape(now), defence);
            RestOfSeasonSkaterResponse kept = keptSkaters.get(now.getNhlId());
            if (kept != null) {
                ScoutLine expected =
                        ScoutLineService.skaterLine(ProjectionSeedService.expectationShape(kept), defence);
                return ScoutRescale.atTodaysLift(
                        expected, kept.getGamesRemaining(), now.getGamesRemaining(), nowExpected, nowLine);
            }
            SkaterProjectionResponse preseason = preseasonSkaters.get(now.getNhlId());
            if (preseason != null) {
                SkaterProjectionResponse expected = ProjectionSeedService.expectationShape(preseason);
                if (expected == null) {
                    // A preseason line that carries no expectation can only be compared as served.
                    ScoutLine line = ScoutLineService.skaterLine(preseason, defence);
                    return ScoutRescale.toGamesLeft(line, seasonGames(now.getTeam()), now.getGamesRemaining());
                }
                return ScoutRescale.atTodaysLift(
                        ScoutLineService.skaterLine(expected, defence),
                        seasonGames(now.getTeam()),
                        now.getGamesRemaining(),
                        nowExpected,
                        nowLine);
            }
            return null;
        }

        /** The goalie's line on the chosen day: {@link #skater} for goalies. */
        ScoutLine goalie(RestOfSeasonGoalieResponse now, ScoutLine nowLine) {
            ScoutLine nowExpected = ScoutLineService.goalieLine(ProjectionSeedService.expectationShape(now));
            RestOfSeasonGoalieResponse kept = keptGoalies.get(now.getNhlId());
            if (kept != null) {
                ScoutLine expected = ScoutLineService.goalieLine(ProjectionSeedService.expectationShape(kept));
                return ScoutRescale.atTodaysLift(
                        expected, kept.getGamesRemaining(), now.getGamesRemaining(), nowExpected, nowLine);
            }
            GoalieProjectionResponse preseason = preseasonGoalies.get(now.getNhlId());
            if (preseason != null) {
                GoalieProjectionResponse expected = ProjectionSeedService.expectationShape(preseason);
                if (expected == null) {
                    ScoutLine line = ScoutLineService.goalieLine(preseason);
                    return ScoutRescale.toGamesLeft(line, seasonGames(now.getTeam()), now.getGamesRemaining());
                }
                return ScoutRescale.atTodaysLift(
                        ScoutLineService.goalieLine(expected),
                        seasonGames(now.getTeam()),
                        now.getGamesRemaining(),
                        nowExpected,
                        nowLine);
            }
            return null;
        }

        /**
         * The games the club's whole season has, which the preseason line was spread over. Read
         * off the published schedule once, and only for a page that needs it.
         */
        private int seasonGames(String club) {
            if (schedule == null) {
                schedule = scheduleService.schedule();
            }
            Integer games = schedule.seasonGames().get(PlayerIdResolver.platformTeam(club));
            if (games != null) {
                return games;
            }
            return schedule.seasonGames().values().stream().max(Integer::compare).orElse(SEASON_GAMES_FALLBACK);
        }
    }

    /**
     * The chosen day's lines: the newest kept on or before {@code since}, else the preseason line,
     * which is the newest line before any day in the season.
     */
    private Then then(LocalDate since) {
        LocalDate earliest = earliestSnapshot();
        if (since != null) {
            List<RestOfSeasonSkaterResponse> kept = projectionServiceClient.restOfSeasonSkaters(season, since);
            List<RestOfSeasonGoalieResponse> keptGoalies = projectionServiceClient.restOfSeasonGoalies(season, since);
            if (!kept.isEmpty() || !keptGoalies.isEmpty()) {
                LocalDate resolved = !kept.isEmpty()
                        ? kept.getFirst().getGeneratedAt().toLocalDate()
                        : keptGoalies.getFirst().getGeneratedAt().toLocalDate();
                return new Then(
                        new ScoutSince(since, "SNAPSHOT", resolved, earliest),
                        byNhlId(kept, RestOfSeasonSkaterResponse::getNhlId),
                        byNhlId(keptGoalies, RestOfSeasonGoalieResponse::getNhlId),
                        Map.of(),
                        Map.of());
            }
        }
        Map<Integer, SkaterProjectionResponse> preseasonSkaters = byNhlId(
                projectionServiceClient.skaterProjections(season, PRESEASON), SkaterProjectionResponse::getNhlId);
        Map<Integer, GoalieProjectionResponse> preseasonGoalies = byNhlId(
                projectionServiceClient.goalieProjections(season, PRESEASON), GoalieProjectionResponse::getNhlId);
        boolean stored = !preseasonSkaters.isEmpty() || !preseasonGoalies.isEmpty();
        return new Then(
                new ScoutSince(since, stored ? "PRESEASON" : "NONE", null, earliest),
                Map.of(),
                Map.of(),
                preseasonSkaters,
                preseasonGoalies);
    }

    private LocalDate earliestSnapshot() {
        RestOfSeasonSnapshotsResponse snapshots = projectionServiceClient.restOfSeasonSnapshots(season);
        if (snapshots == null || snapshots.getDays().isEmpty()) {
            return null;
        }
        return snapshots.getDays().getFirst();
    }

    private static <T> Map<Integer, T> byNhlId(List<T> rows, Function<T, Integer> nhlId) {
        Map<Integer, T> byId = new HashMap<>();
        if (rows != null) {
            for (T row : rows) {
                if (nhlId.apply(row) != null) {
                    byId.put(nhlId.apply(row), row);
                }
            }
        }
        return byId;
    }
}
