package com.fantasy.bff.client;

import com.fantasy.bff.dto.request.GameRange;
import com.fantasy.bff.generated.projection.model.GoalieProjectionResponse;
import com.fantasy.bff.generated.projection.model.GoalieSplitResponse;
import com.fantasy.bff.generated.projection.model.PlayerResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonGoalieResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSkaterResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSnapshotsResponse;
import com.fantasy.bff.generated.projection.model.RangeProjectionsResponse;
import com.fantasy.bff.generated.projection.model.ScheduleStrengthResponse;
import com.fantasy.bff.generated.projection.model.ScheduleWeeksResponse;
import com.fantasy.bff.generated.projection.model.SkaterProjectionResponse;
import com.fantasy.bff.generated.projection.model.SkaterSplitResponse;
import com.fantasy.bff.generated.projection.model.RoleChangesResponse;
import com.fantasy.bff.generated.projection.model.SplitSeasonsResponse;
import java.time.LocalDate;
import java.util.List;

public interface ProjectionServiceClient {

    List<SkaterProjectionResponse> skaterProjections(int season, String modelVersion);

    List<GoalieProjectionResponse> goalieProjections(int season, String modelVersion);

    /**
     * Each skater's expected line over the games his club has left in {@code season}. Empty, not
     * an error, while the season has no game played and still to play: projection-service has
     * nothing to say about the rest of a season that has not started or is over.
     */
    List<RestOfSeasonSkaterResponse> restOfSeasonSkaters(int season);

    /** The goalies' half of {@link #restOfSeasonSkaters}, empty on the same terms. */
    List<RestOfSeasonGoalieResponse> restOfSeasonGoalies(int season);

    /**
     * The rest of the season as it stood on {@code asOf}: the line of the newest run
     * projection-service kept on or before that day, its {@code generated_at} saying which. Empty,
     * not an error, when no day that early is kept.
     */
    List<RestOfSeasonSkaterResponse> restOfSeasonSkaters(int season, LocalDate asOf);

    /** The goalies' half of {@link #restOfSeasonSkaters(int, LocalDate)}, empty on the same terms. */
    List<RestOfSeasonGoalieResponse> restOfSeasonGoalies(int season, LocalDate asOf);

    /** The days a rest-of-season line is kept for in {@code season}, oldest first. */
    RestOfSeasonSnapshotsResponse restOfSeasonSnapshots(int season);

    /**
     * Player identity — name, team, sweater number — for every player the NHL still lists as
     * active. The projections themselves carry only an NHL id, so this is what makes it
     * possible to work out which platform player a projection belongs to.
     */
    /**
     * @param season the season rookie status is wanted for, or null to leave it out
     */
    List<PlayerResponse> activePlayers(Integer season);

    /**
     * Players the NHL no longer lists as active — retired, or gone from the league.
     *
     * <p>Every player the store holds has real season history behind them, so an inactive one
     * is someone who played and stopped. A prospect who has never played an NHL game is not in
     * the store at all, which is what separates the two cases the platform cannot tell apart.
     */
    List<PlayerResponse> retiredPlayers();

    /**
     * Measured totals over a stretch of a team's schedule — what a player actually did over
     * that stretch, not a forecast.
     */
    /**
     * @param season the season to measure, or null for the newest season with a game played
     */
    List<SkaterSplitResponse> skaterSplits(Integer season, GameRange range, int limit);

    List<GoalieSplitResponse> goalieSplits(Integer season, GameRange range, int limit);

    /**
     * Every season a split can be measured over, with its length and how far it has got.
     *
     * @param targetSeason a season to list even before it has a game, or null
     */
    SplitSeasonsResponse splitSeasons(Integer targetSeason);

    /**
     * Every skater who dressed in his club's last {@code recentGames}: his ice and power-play share
     * over them beside his baseline (his earlier games this season, else last season), and where
     * the lineup page lists him now and before.
     *
     * @param season the season to measure, or null for the newest season with a game played
     */
    RoleChangesResponse skaterRoleChanges(Integer season, int recentGames);

    /** The newest published season's Monday-Sunday weeks, numbered from opening night. */
    ScheduleWeeksResponse scheduleWeeks();

    /** Every club's games from {@code start} to {@code end} inclusive, scored and ranked for streaming. */
    ScheduleStrengthResponse scheduleStrength(LocalDate start, LocalDate end);

    /**
     * What the model expects of every player over a stretch of dates: his club's games in it, the
     * ones he is expected to dress for, and the line over them.
     */
    RangeProjectionsResponse rangeProjections(LocalDate start, LocalDate end, int limit);
}
