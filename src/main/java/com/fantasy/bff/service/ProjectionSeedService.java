package com.fantasy.bff.service;

import com.fantasy.bff.client.ProjectionServiceClient;
import com.fantasy.bff.dto.response.GoalieResponse;
import com.fantasy.bff.dto.response.SkaterPosition;
import com.fantasy.bff.dto.response.SkaterResponse;
import com.fantasy.bff.generated.db.model.PlayerProjection;
import com.fantasy.bff.generated.db.model.PlayerStats;
import com.fantasy.bff.generated.projection.model.ExpectedGoalieLine;
import com.fantasy.bff.generated.projection.model.ExpectedSkaterLine;
import com.fantasy.bff.generated.projection.model.GoalieProjectionResponse;
import com.fantasy.bff.generated.projection.model.PlayerResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonGoalieResponse;
import com.fantasy.bff.generated.projection.model.RestOfSeasonSkaterResponse;
import com.fantasy.bff.generated.projection.model.ServedRestOfSeasonGoalie;
import com.fantasy.bff.generated.projection.model.ServedRestOfSeasonSkater;
import com.fantasy.bff.generated.projection.model.SkaterProjectionResponse;
import com.fantasy.bff.service.mapping.ModelStatMapping;
import com.fantasy.bff.service.mapping.PlayerIdMapping;
import com.fantasy.bff.service.mapping.PlayerIdOverrides;
import com.fantasy.bff.service.mapping.PlayerIdResolver;
import com.fantasy.bff.service.mapping.PlayerIdResolver.Candidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns the projection service's model output into a projection a user can save and edit.
 *
 * <p>Three things have to line up. The projections are keyed by NHL id, the app is keyed by the
 * platform's player id, so {@link PlayerIdResolver} bridges them. The two sides also name the
 * same stats differently, so the vocabularies are translated here. And a stat the projection
 * service leaves out — a goalie with no projected starts has no save % — must not be filled in
 * with a zero, which would read as a terrible goalie rather than an absent one.
 */
@Service
public class ProjectionSeedService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionSeedService.class);

    private final ProjectionServiceClient projectionServiceClient;
    private final PlayerPoolSource playerPool;
    private final PlayerIdResolver resolver;
    private final PlayerIdOverrides overrides;
    private final ProjectionSeedCache cache;

    public ProjectionSeedService(
            ProjectionServiceClient projectionServiceClient,
            PlayerPoolSource playerPool,
            PlayerIdResolver resolver,
            PlayerIdOverrides overrides,
            ProjectionSeedCache cache) {
        this.projectionServiceClient = projectionServiceClient;
        this.playerPool = playerPool;
        this.resolver = resolver;
        this.overrides = overrides;
        this.cache = cache;
    }

    /**
     * @param players the seeded lines, keyed by platform player id
     * @param modelVersion the version the rows actually came back stamped with, which is not
     *     necessarily the one asked for: with nothing pinned, projection-service picks the
     *     season's most recent run and says so on every row
     * @param skatersSeeded how many skaters made it through
     * @param goaliesSeeded how many goalies made it through with a projected workload
     * @param unmapped players the model projected that the platform doesn't carry
     * @param withoutWorkload goalies the model projects no starts for, seeded at nought starts
     *     with no save % and no GAA
     * @param retiredZeroed pool players who have left the league, seeded at zero
     */
    public record Seed(
            List<PlayerProjection> players,
            String modelVersion,
            int skatersSeeded,
            int goaliesSeeded,
            int unmapped,
            int withoutWorkload,
            int retiredZeroed) {}

    public Seed seed(int season, String modelVersion) {
        return seed(season, modelVersion, null, null);
    }

    /**
     * What the cache holds the in-season lines under. A model version is never called this, so it
     * cannot be taken for one.
     */
    static final String IN_SEASON = "in-season";

    /**
     * The model's rest of the season for each player while it is under way, served on the
     * season line's scale, mapped and zeroed exactly as the season line is, or empty while the
     * season has none: before its first game and after its last.
     *
     * <p>Each line is the projection service's {@code served}: the rest of the season lifted
     * toward the table the whole season will end on, the season so far ranked in it and left out
     * of it, so a team is ranked on what its players will do from here and not on goals they
     * scored for another team (Alexander's call, 2026-10-02). The rest's expectation beside it is
     * on a smaller scale than the season line, and ranked on it every star read 15-20% below the
     * line he was drafted on a game into the season. A row without {@code served} (one the
     * nightly run wrote before the projection service served it) is read as that expectation, and
     * counted in the log.
     *
     * <p>It is the model's own answer for a season under way, counted from the day of the last
     * nightly run, with the injuries and the lines it knew of then. Nothing here derives it from
     * the season line.
     */
    public Optional<Seed> inSeason(int season) {
        return inSeason(season, null, null);
    }

    /**
     * {@link #inSeason(int)}, optionally only the top of it, trimmed exactly as
     * {@link #seed(int, String, Integer, Integer)} trims the season line: the rows and nothing
     * else, out of the one cached board the projection it seeds is written from.
     */
    public Optional<Seed> inSeason(int season, Integer skaterLimit, Integer goalieLimit) {
        return wholeInSeason(season).map(whole -> skaterLimit == null && goalieLimit == null
                ? whole
                : new Seed(
                        topOf(whole.players(), skaterLimit, goalieLimit),
                        whole.modelVersion(),
                        whole.skatersSeeded(),
                        whole.goaliesSeeded(),
                        whole.unmapped(),
                        whole.withoutWorkload(),
                        whole.retiredZeroed()));
    }

    private Optional<Seed> wholeInSeason(int season) {
        Optional<Seed> cached = cache.get(season, IN_SEASON);
        if (cached.isPresent()) {
            return cached;
        }
        List<RestOfSeasonSkaterResponse> skaterRows = projectionServiceClient.restOfSeasonSkaters(season);
        List<RestOfSeasonGoalieResponse> goalieRows = projectionServiceClient.restOfSeasonGoalies(season);
        if (skaterRows.isEmpty() && goalieRows.isEmpty()) {
            return Optional.empty();
        }
        long unserved = skaterRows.stream().filter(r -> r.getServed() == null).count()
                + goalieRows.stream().filter(r -> r.getServed() == null).count();
        if (unserved > 0) {
            log.warn(
                    "{} of {} in-season lines for {} have no served line yet and are read as their "
                            + "rest of the season's expectation, until the next nightly run writes one",
                    unserved,
                    skaterRows.size() + goalieRows.size(),
                    season);
        }
        Seed built = build(
                season,
                null,
                skaterRows.stream().map(ProjectionSeedService::seasonShape).toList(),
                goalieRows.stream().map(ProjectionSeedService::seasonShape).toList(),
                true);
        cache.put(season, IN_SEASON, built);
        return Optional.of(built);
    }

    /**
     * A skater's served rest of the season in the season line's shape, which is what the mapping
     * reads. The FA scout reads it too, so its rest of the season is the one every page shows.
     */
    static SkaterProjectionResponse seasonShape(RestOfSeasonSkaterResponse r) {
        ServedRestOfSeasonSkater s = r.getServed();
        if (s == null) {
            return expectationShape(r);
        }
        return new SkaterProjectionResponse()
                .nhlId(r.getNhlId())
                .targetSeason(r.getTargetSeason())
                .modelVersion(r.getModelVersion())
                .gamesPlayed(s.getGamesPlayed())
                .toiPerGameSeconds(s.getToiPerGameSeconds())
                .goals(s.getGoals())
                .assists(s.getAssists())
                .points(s.getPoints())
                .plusMinus(s.getPlusMinus())
                .pim(s.getPim())
                .ppGoals(s.getPpGoals())
                .ppAssists(s.getPpAssists())
                .ppPoints(s.getPpPoints())
                .shGoals(s.getShGoals())
                .shAssists(s.getShAssists())
                .shPoints(s.getShPoints())
                .gwGoals(s.getGwGoals())
                .shots(s.getShots())
                .shootingPct(s.getShootingPct())
                .faceoffsWon(s.getFaceoffsWon())
                .faceoffsLost(s.getFaceoffsLost())
                .hits(s.getHits())
                .blocks(s.getBlocks())
                .shifts(s.getShifts())
                .hatTricks(s.getHatTricks());
    }

    /**
     * A skater's rest of the season as the model expects it, before any lift, in the season line's
     * shape: what Player Scout compares two days' lines on ({@link ScoutRescale#atTodaysLift}).
     */
    static SkaterProjectionResponse expectationShape(RestOfSeasonSkaterResponse r) {
        return new SkaterProjectionResponse()
                .nhlId(r.getNhlId())
                .targetSeason(r.getTargetSeason())
                .modelVersion(r.getModelVersion())
                .gamesPlayed(r.getGamesPlayed())
                .toiPerGameSeconds(r.getToiPerGameSeconds())
                .goals(r.getGoals())
                .assists(r.getAssists())
                .points(r.getPoints())
                .plusMinus(r.getPlusMinus())
                .pim(r.getPim())
                .ppGoals(r.getPpGoals())
                .ppAssists(r.getPpAssists())
                .ppPoints(r.getPpPoints())
                .shGoals(r.getShGoals())
                .shAssists(r.getShAssists())
                .shPoints(r.getShPoints())
                .gwGoals(r.getGwGoals())
                .shots(r.getShots())
                .shootingPct(r.getShootingPct())
                .faceoffsWon(r.getFaceoffsWon())
                .faceoffsLost(r.getFaceoffsLost())
                .hits(r.getHits())
                .blocks(r.getBlocks())
                .shifts(r.getShifts())
                .hatTricks(r.getHatTricks());
    }

    /**
     * The expectation a season line was lifted from, in the season line's own shape; null where
     * the row carries none (written before the model served one).
     */
    static SkaterProjectionResponse expectationShape(SkaterProjectionResponse p) {
        ExpectedSkaterLine e = p.getExpectation();
        if (e == null) {
            return null;
        }
        return new SkaterProjectionResponse()
                .nhlId(p.getNhlId())
                .targetSeason(p.getTargetSeason())
                .modelVersion(p.getModelVersion())
                .gamesPlayed(e.getGamesPlayed())
                .toiPerGameSeconds(e.getToiPerGameSeconds())
                .goals(e.getGoals())
                .assists(e.getAssists())
                .points(e.getPoints())
                .plusMinus(e.getPlusMinus())
                .pim(e.getPim())
                .ppGoals(e.getPpGoals())
                .ppAssists(e.getPpAssists())
                .ppPoints(e.getPpPoints())
                .shGoals(e.getShGoals())
                .shAssists(e.getShAssists())
                .shPoints(e.getShPoints())
                .gwGoals(e.getGwGoals())
                .shots(e.getShots())
                .shootingPct(e.getShootingPct())
                .faceoffsWon(e.getFaceoffsWon())
                .faceoffsLost(e.getFaceoffsLost())
                .hits(e.getHits())
                .blocks(e.getBlocks())
                .shifts(e.getShifts())
                .hatTricks(e.getHatTricks());
    }

    /** A goalie's served rest of the season in the season line's shape. */
    static GoalieProjectionResponse seasonShape(RestOfSeasonGoalieResponse r) {
        ServedRestOfSeasonGoalie s = r.getServed();
        if (s == null) {
            return expectationShape(r);
        }
        return new GoalieProjectionResponse()
                .nhlId(r.getNhlId())
                .targetSeason(r.getTargetSeason())
                .modelVersion(r.getModelVersion())
                .gamesPlayed(s.getGamesPlayed())
                .gamesStarted(s.getGamesStarted())
                .wins(s.getWins())
                .losses(s.getLosses())
                .otLosses(s.getOtLosses())
                .shutouts(s.getShutouts())
                .shotsAgainst(s.getShotsAgainst())
                .saves(s.getSaves())
                .goalsAgainst(s.getGoalsAgainst())
                .goalsAgainstAvg(s.getGoalsAgainstAvg())
                .savePct(s.getSavePct())
                .toiSeconds(s.getToiSeconds());
    }

    /** A goalie's rest of the season as the model expects it: the skater's {@code expectationShape}. */
    static GoalieProjectionResponse expectationShape(RestOfSeasonGoalieResponse r) {
        return new GoalieProjectionResponse()
                .nhlId(r.getNhlId())
                .targetSeason(r.getTargetSeason())
                .modelVersion(r.getModelVersion())
                .gamesPlayed(r.getGamesPlayed())
                .gamesStarted(r.getGamesStarted())
                .wins(r.getWins())
                .losses(r.getLosses())
                .otLosses(r.getOtLosses())
                .shutouts(r.getShutouts())
                .shotsAgainst(r.getShotsAgainst())
                .saves(r.getSaves())
                .goalsAgainst(r.getGoalsAgainst())
                .goalsAgainstAvg(r.getGoalsAgainstAvg())
                .savePct(r.getSavePct())
                .toiSeconds(r.getToiSeconds());
    }

    /** The expectation a goalie's season line was lifted from; null where the row carries none. */
    static GoalieProjectionResponse expectationShape(GoalieProjectionResponse p) {
        ExpectedGoalieLine e = p.getExpectation();
        if (e == null) {
            return null;
        }
        return new GoalieProjectionResponse()
                .nhlId(p.getNhlId())
                .targetSeason(p.getTargetSeason())
                .modelVersion(p.getModelVersion())
                .gamesPlayed(e.getGamesPlayed())
                .gamesStarted(e.getGamesStarted())
                .wins(e.getWins())
                .losses(e.getLosses())
                .otLosses(e.getOtLosses())
                .shutouts(e.getShutouts())
                .shotsAgainst(e.getShotsAgainst())
                .saves(e.getSaves())
                .goalsAgainst(e.getGoalsAgainst())
                .goalsAgainstAvg(e.getGoalsAgainstAvg())
                .savePct(e.getSavePct())
                .toiSeconds(e.getToiSeconds());
    }

    /**
     * The model's lines, optionally only the top of them.
     *
     * <p>The limits trim the rows returned and nothing else: the counts on the {@link Seed} keep
     * reporting the whole of what the model reached, because a caller showing five rows still
     * wants to say how much of the league it covers. Nor do they save any work — the seed is
     * computed in full either way, since the coverage counts and the retired-zeroing both need
     * the whole board. What they save is sending it: a caller drawing a five-row preview would
     * otherwise download every line to show four of them.
     *
     * <p>Which is why the full board is what {@link ProjectionSeedCache} holds, and the limits
     * are applied to what comes back: the preview and the projection it seeds are the same
     * board, so they share one entry. The rows in it are shared with every other reader and
     * must not be modified — the list is unmodifiable, the {@code PlayerProjection}s in it are
     * only read and serialised.
     *
     * <p>Ordered the way the player pool is, skaters by projected points and goalies by projected
     * wins, so the top of this board is the top of that one. A caller scoring by its own weights
     * will not want exactly these players in exactly this order — ask for enough of them that the
     * ones it would pick are inside.
     *
     * @param skaterLimit how many skater lines to return, or null for all of them
     * @param goalieLimit how many goalie lines to return, or null for all of them
     */
    public Seed seed(int season, String modelVersion, Integer skaterLimit, Integer goalieLimit) {
        Seed whole = cache.get(season, modelVersion).orElseGet(() -> {
            Seed built = build(season, modelVersion);
            cache.put(season, modelVersion, built);
            return built;
        });
        if (skaterLimit == null && goalieLimit == null) {
            return whole;
        }
        return new Seed(
                topOf(whole.players(), skaterLimit, goalieLimit),
                whole.modelVersion(),
                whole.skatersSeeded(),
                whole.goaliesSeeded(),
                whole.unmapped(),
                whole.withoutWorkload(),
                whole.retiredZeroed());
    }

    /**
     * The whole board, built from the model. Called on a cache miss and nowhere else, so its log
     * line marks a rebuild rather than a read.
     */
    private Seed build(int season, String modelVersion) {
        return build(
                season,
                modelVersion,
                projectionServiceClient.skaterProjections(season, modelVersion),
                projectionServiceClient.goalieProjections(season, modelVersion),
                false);
    }

    private Seed build(
            int season,
            String modelVersion,
            List<SkaterProjectionResponse> skaterRows,
            List<GoalieProjectionResponse> goalieRows,
            boolean inSeason) {
        List<PlayerResponse> nhlPlayers = projectionServiceClient.activePlayers(null);
        Map<Long, PlayerResponse> byNhlId = nhlPlayers.stream()
                .collect(Collectors.toMap(p -> p.getNhlId().longValue(), Function.identity(), (a, b) -> a));

        Pool pool = platformPool();
        PlayerIdMapping mapping = resolver.resolve(
                nhlPlayers.stream().map(ProjectionSeedService::nhlCandidate).toList(),
                pool.candidates(),
                overrides.asMap());

        List<PlayerProjection> seeded = new ArrayList<>();
        int unmapped = 0;
        int withoutWorkload = 0;
        // What we asked for, until a row tells us otherwise. Unpinned, the version is the
        // service's to choose, and the only honest way to report it is to read it off what came
        // back rather than to echo the request.
        String servedVersion = modelVersion;

        for (SkaterProjectionResponse projection : skaterRows) {
            servedVersion = stampedOr(projection.getModelVersion(), servedVersion);
            Integer platformId = mapping.nhlIdToPlatformId().get(projection.getNhlId().longValue());
            if (platformId == null) {
                unmapped++;
                continue;
            }
            seeded.add(skaterLine(platformId, projection, pool.playsDefence(platformId)));
        }
        int skaters = seeded.size();

        int goalies = 0;
        for (GoalieProjectionResponse projection : goalieRows) {
            servedVersion = stampedOr(projection.getModelVersion(), servedVersion);
            Integer platformId = mapping.nhlIdToPlatformId().get(projection.getNhlId().longValue());
            if (platformId == null) {
                unmapped++;
                continue;
            }
            // A goalie the model gives no starts is still the model's answer about him, so he is
            // seeded like anyone else: nought starts, and no save % or GAA, which his line leaves
            // out rather than filling in with a .000 that would read as the worst goalie in the
            // league. He used to be left off the board instead, and whatever builds a board from
            // this one reads a player missing from it as a player the model cannot reach - the
            // pool reconciler gives those last season's line - so each club's starts came to its
            // schedule plus what its idle goalies had started a year ago. On staging's
            // marcel-v94 board five clubs read over, the Islanders at 103 of 84.
            if (projection.getSavePct() == null || projection.getGamesStarted() == null) {
                withoutWorkload++;
            } else {
                goalies++;
            }
            seeded.add(goalieLine(platformId, projection));
        }

        int retiredZeroed = seedRetired(seeded, pool);

        // Unmodifiable because it is about to be shared with every reader of this season's seed.
        Seed seed = new Seed(
                List.copyOf(seeded),
                servedVersion,
                skaters,
                goalies,
                unmapped,
                withoutWorkload,
                retiredZeroed);
        log.info(
                "Seeded {} projections for {} (in season: {}, {}): {} skaters, {} goalies; {} unmapped, "
                        + "{} goalies without a projected workload, {} retired zeroed. "
                        + "Player pool: {}",
                seeded.size(),
                season,
                inSeason,
                forLog(servedVersion),
                seed.skatersSeeded(),
                seed.goaliesSeeded(),
                seed.unmapped(),
                seed.withoutWorkload(),
                seed.retiredZeroed(),
                byNhlId.size());
        return seed;
    }

    /**
     * The highest {@code skaterLimit} skaters and {@code goalieLimit} goalies, by the stat each
     * kind is ranked on elsewhere. A null limit takes them all; a list with neither limit set is
     * returned untouched, in the order it was built.
     */
    private static List<PlayerProjection> topOf(
            List<PlayerProjection> seeded, Integer skaterLimit, Integer goalieLimit) {
        if (skaterLimit == null && goalieLimit == null) {
            return seeded;
        }
        List<PlayerProjection> top = new ArrayList<>();
        top.addAll(top(seeded, PlayerProjection.TypeEnum.SKATER, "points", skaterLimit));
        top.addAll(top(seeded, PlayerProjection.TypeEnum.GOALIE, "w", goalieLimit));
        return top;
    }

    private static List<PlayerProjection> top(
            List<PlayerProjection> seeded,
            PlayerProjection.TypeEnum type,
            String rankedOn,
            Integer limit) {
        Stream<PlayerProjection> ofType =
                seeded.stream().filter(projection -> projection.getType() == type);
        if (limit == null) {
            return ofType.toList();
        }
        return ofType.sorted(Comparator.comparingDouble(
                        (PlayerProjection projection) ->
                                projection.getStats().getScoring().getOrDefault(rankedOn, 0d))
                .reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Seeds a zero line for every pool player who has left the league.
     *
     * <p>A platform keeps carrying a player for a while after they retire, and the model does
     * not project them, so they would otherwise arrive as an untouched row — indistinguishable
     * from a player the model simply could not reach. Zero is the honest number: they will not
     * play. A prospect is the opposite case and must not be caught here, which is why this
     * matches against the store's inactive players rather than against whoever went unmapped:
     * a prospect who has never played an NHL game is not in the store at all.
     *
     * <p>Matching runs only over pool players nothing has claimed yet, so an active player who
     * shares a name with a retired one keeps his projection. That ordering is the safeguard —
     * there are two Sebastian Ahos, and only one of them has retired.
     *
     * @return how many rows were zeroed
     */
    private int seedRetired(List<PlayerProjection> seeded, Pool pool) {
        Set<Integer> claimed =
                seeded.stream().map(PlayerProjection::getPlayerId).collect(Collectors.toSet());
        List<Candidate> unclaimed =
                pool.candidates().stream().filter(c -> !claimed.contains((int) c.id())).toList();
        if (unclaimed.isEmpty()) {
            return 0;
        }

        List<Candidate> retired =
                projectionServiceClient.retiredPlayers().stream()
                        .map(ProjectionSeedService::nhlCandidate)
                        .toList();
        // No overrides here: they pin a player to his projection, and pinning one to a zero
        // line would be a way to silently delete him.
        PlayerIdMapping retiredMapping = resolver.resolve(retired, unclaimed, Map.of());

        Set<Integer> goalieIds = goaliePlatformIds();
        for (Integer platformId : retiredMapping.nhlIdToPlatformId().values()) {
            seeded.add(
                    goalieIds.contains(platformId)
                            ? zeroLine(platformId, PlayerProjection.TypeEnum.GOALIE, GOALIE_STATS)
                            : zeroLine(
                                    platformId,
                                    PlayerProjection.TypeEnum.SKATER,
                                    SKATER_STATS,
                                    pool.playsDefence(platformId)));
        }
        return retiredMapping.matched();
    }

    private Set<Integer> goaliePlatformIds() {
        return playerPool.getGoalies().stream().map(GoalieResponse::id).collect(Collectors.toSet());
    }

    private PlayerProjection zeroLine(
            int platformId, PlayerProjection.TypeEnum type, Stats stats) {
        return zeroLine(platformId, type, stats, false);
    }

    private PlayerProjection zeroLine(
            int platformId, PlayerProjection.TypeEnum type, Stats stats, boolean playsDefence) {
        Map<String, Double> utility = new HashMap<>();
        for (String key : stats.utility()) {
            utility.put(key, 0.0);
        }
        Map<String, Double> scoring = new HashMap<>();
        for (String key : stats.scoring()) {
            scoring.put(key, 0.0);
        }
        // Same rule as a played line: a forward has none of this category rather than nought of
        // it, so it is left off him here too.
        if (playsDefence) {
            scoring.put("defPoints", 0.0);
        }
        return line(platformId, type, utility, scoring);
    }

    /** The stat keys a zero line has to fill, so the row is complete rather than half-blank. */
    private record Stats(List<String> utility, List<String> scoring) {}

    private static final Stats SKATER_STATS =
            new Stats(
                    List.of("gp", "toiPerGame"),
                    List.of(
                            "goals", "assists", "points", "plusMinus", "pim", "ppg", "ppa", "ppp",
                            "shg", "sha", "shp", "stpg", "stpa", "stp", "gwg", "hatTricks", "sog",
                            "shPct", "fw", "fl", "hits", "blocks", "shifts", "toi"));

    private static final Stats GOALIE_STATS =
            new Stats(
                    List.of("gp"),
                    List.of(
                            "gs", "w", "l", "otl", "sho", "sa", "sv", "ga", "gaa", "svPct",
                            "winPct", "toi"));

    /**
     * The platform's players, plus which of them a league would slot at defence. Defencemen
     * points are a category of their own, and eligibility is the platform's answer — the NHL's
     * listed position doesn't decide what a fantasy league lets you start.
     */
    private record Pool(List<Candidate> candidates, Set<Integer> defenceEligible) {
        boolean playsDefence(int platformId) {
            return defenceEligible.contains(platformId);
        }
    }

    private Pool platformPool() {
        List<Candidate> candidates = new ArrayList<>();
        Set<Integer> defenceEligible = new HashSet<>();
        for (SkaterResponse skater : playerPool.getSkaters()) {
            candidates.add(new Candidate(
                    skater.id(), skater.name(), skater.teamAbbrev(), skater.sweaterNumber()));
            if (skater.positions() != null && skater.positions().contains(SkaterPosition.D)) {
                defenceEligible.add(skater.id());
            }
        }
        for (GoalieResponse goalie : playerPool.getGoalies()) {
            candidates.add(new Candidate(
                    goalie.id(), goalie.name(), goalie.teamAbbrev(), goalie.sweaterNumber()));
        }
        return new Pool(candidates, defenceEligible);
    }

    private static Candidate nhlCandidate(PlayerResponse player) {
        return new Candidate(
                player.getNhlId().longValue(),
                player.getFullName(),
                player.getCurrentTeam(),
                player.getSweaterNumber());
    }

    private PlayerProjection skaterLine(
            int platformId, SkaterProjectionResponse p, boolean playsDefence) {
        Map<String, Double> utility = new HashMap<>();
        put(utility, "gp", p.getGamesPlayed());
        put(utility, "toiPerGame", p.getToiPerGameSeconds());

        Map<String, Double> scoring = new HashMap<>();
        put(scoring, "goals", p.getGoals());
        put(scoring, "assists", p.getAssists());
        put(scoring, "points", p.getPoints());
        put(scoring, "plusMinus", p.getPlusMinus());
        put(scoring, "pim", p.getPim());
        put(scoring, "ppg", p.getPpGoals());
        put(scoring, "ppa", p.getPpAssists());
        put(scoring, "ppp", p.getPpPoints());
        put(scoring, "shg", p.getShGoals());
        put(scoring, "sha", p.getShAssists());
        put(scoring, "shp", p.getShPoints());
        put(scoring, "gwg", p.getGwGoals());
        put(scoring, "hatTricks", p.getHatTricks());
        put(scoring, "sog", p.getShots());
        ModelStatMapping.putPercent(scoring, "shPct", p.getShootingPct());
        put(scoring, "fw", p.getFaceoffsWon());
        put(scoring, "fl", p.getFaceoffsLost());
        put(scoring, "hits", p.getHits());
        put(scoring, "blocks", p.getBlocks());
        put(scoring, "shifts", p.getShifts());
        ModelStatMapping.putTotal(scoring, "toi", p.getToiPerGameSeconds(), p.getGamesPlayed());
        // A league that scores special teams scores one category, power play plus shorthanded.
        // The model has no such stat and never will: it is the two it does project, added.
        putSum(scoring, "stpg", p.getPpGoals(), p.getShGoals());
        putSum(scoring, "stpa", p.getPpAssists(), p.getShAssists());
        putSum(scoring, "stp", p.getPpPoints(), p.getShPoints());
        // Defencemen points count a player's points only while he is eligible at defence, so a
        // forward's are not a smaller version of the same thing — they are none of it, and the
        // column is left out rather than seeded at nought.
        if (playsDefence) {
            put(scoring, "defPoints", p.getPoints());
        }

        return line(platformId, PlayerProjection.TypeEnum.SKATER, utility, scoring);
    }

    private PlayerProjection goalieLine(int platformId, GoalieProjectionResponse p) {
        Map<String, Double> utility = new HashMap<>();
        put(utility, "gp", p.getGamesPlayed());

        Map<String, Double> scoring = new HashMap<>();
        put(scoring, "gs", p.getGamesStarted());
        put(scoring, "w", p.getWins());
        put(scoring, "l", p.getLosses());
        put(scoring, "sho", p.getShutouts());
        put(scoring, "sa", p.getShotsAgainst());
        put(scoring, "sv", p.getSaves());
        put(scoring, "ga", p.getGoalsAgainst());
        put(scoring, "gaa", p.getGoalsAgainstAvg());
        // Save % stays a fraction here, matching the app's goalie column.
        put(scoring, "svPct", p.getSavePct());
        put(scoring, "otl", p.getOtLosses());
        put(scoring, "toi", p.getToiSeconds());
        ModelStatMapping.putWinPct(scoring, p.getWins(), p.getLosses(), p.getOtLosses());

        return line(platformId, PlayerProjection.TypeEnum.GOALIE, utility, scoring);
    }

    private PlayerProjection line(
            int platformId,
            PlayerProjection.TypeEnum type,
            Map<String, Double> utility,
            Map<String, Double> scoring) {
        PlayerStats stats = new PlayerStats();
        stats.setUtility(utility);
        stats.setScoring(scoring);

        PlayerProjection projection = new PlayerProjection();
        projection.setPlayerId(platformId);
        projection.setType(type);
        projection.setStats(stats);
        return projection;
    }

    /**
     * The two services' specs generate different numeric types — the projection service's
     * plain {@code number} becomes a BigDecimal, db-service's {@code number/double} a Double —
     * so the conversion happens once, here. A missing value is left out rather than zeroed.
     */
    private static void put(Map<String, Double> target, String key, BigDecimal value) {
        if (value != null) {
            target.put(key, value.doubleValue());
        }
    }

    /**
     * The model version reaches us from the caller, so it is stripped of line breaks before it
     * reaches the log — otherwise a crafted value could forge log lines around it.
     */
    /** The version a row was stamped with, keeping what we had if a row does not carry one. */
    private static String stampedOr(String stamped, String fallback) {
        return stamped == null || stamped.isBlank() ? fallback : stamped;
    }

    private static String forLog(String value) {
        return value == null ? "" : value.replaceAll("[\r\n]", "");
    }

    /** A stat the model has no column for, because it is two of the ones it does have. */
    private static void putSum(
            Map<String, Double> target, String key, BigDecimal first, BigDecimal second) {
        if (first != null && second != null) {
            target.put(key, first.add(second).doubleValue());
        }
    }
}
