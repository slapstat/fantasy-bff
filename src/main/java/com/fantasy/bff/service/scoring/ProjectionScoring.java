package com.fantasy.bff.service.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a player is worth in a league: fantasy points where the league counts them, and a z-score
 * where it counts categories.
 *
 * <p>A port of {@code projection-calculation.service.ts} and the scoring half of {@code
 * projection-ranking.service.ts}. It exists on this side so a league summary can be totalled
 * without handing the browser the lines it was totalled from; the browser still ranks a board its
 * owner is editing, and the two must not disagree, which the golden vectors enforce.
 *
 * <p>The manual ranking the editor allows is deliberately not ported: it reorders a board its
 * owner arranged, and nothing scored here has an owner to have arranged it.
 */
public final class ProjectionScoring {

    private static final int MAX_POOL_ITERATIONS = 10;

    private ProjectionScoring() {
    }

    /**
     * What every player is worth, and what each category contributed to it.
     *
     * @param values the overall value per player id: fantasy points, or the summed z-score
     * @param contributions per player id, the active category keys mapped to their share of that
     *     value — so a team's category cells sum to its total
     * @param qualified player ids the league counts; a category league drops a goalie projected
     *     fewer games than it asks for
     */
    public record Scores(
            Map<Integer, Double> values,
            Map<Integer, Map<String, Double>> contributions,
            Set<Integer> qualified) {
    }

    /**
     * Scores a whole pool at once, which is the only way a category league can be scored: a
     * z-score says where a player sits against everyone else, so the pool decides the number.
     *
     * @param pool every row the board holds, not only the drafted ones
     * @param league how the league scores
     * @return the value and breakdown per player id
     */
    public static Scores score(List<ScoredPlayer> pool, LeagueScoring league) {
        Map<String, Integer> decimals = league.decimals() == null
                ? ScoringStatKeys.readableDecimals(pool)
                : league.decimals();
        List<ScoredPlayer> rounded = pool.stream().map(player -> player.rounded(decimals)).toList();

        Map<Integer, Double> values = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> contributions = new LinkedHashMap<>();
        Set<Integer> qualified = new HashSet<>();

        if (league.points()) {
            for (ScoredPlayer player : rounded) {
                Map<String, Double> byCategory = pointsContributions(player, league);
                values.put(player.playerId(), totalPoints(player, league));
                contributions.put(player.playerId(), byCategory);
                qualified.add(player.playerId());
            }
            return new Scores(values, contributions, qualified);
        }

        ZScores zScores = zScores(rounded, league);
        for (ScoredPlayer player : rounded) {
            values.put(player.playerId(), zScores.totals().getOrDefault(player.playerId(), 0.0));
            contributions.put(
                    player.playerId(),
                    zScores.byCategory().getOrDefault(player.playerId(), Map.of()));
            if (!player.goalie() || player.gamesPlayed() >= league.minGoalieGames()) {
                qualified.add(player.playerId());
            }
        }
        return new Scores(values, contributions, qualified);
    }

    /**
     * Points mode: every active category weighed by what the league pays for it. Only the stats
     * that kind of player has — a goalie is paid for no hits, however the league weighs them.
     */
    private static Map<String, Double> pointsContributions(ScoredPlayer player, LeagueScoring league) {
        Set<String> ofHisKind = player.goalie()
                ? ScoringStatKeys.GOALIE_SCORING_SET
                : ScoringStatKeys.SKATER_SCORING_SET;
        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (Map.Entry<String, Double> weight : league.statWeights().entrySet()) {
            String key = weight.getKey();
            if (!league.activeScoringSet().contains(key) || !ofHisKind.contains(key)) {
                continue;
            }
            byCategory.put(key, player.scoringValue(key) * weight.getValue());
        }
        return byCategory;
    }

    /**
     * The league's whole wire, added up. Deliberately not the sum of the contributions above: the
     * web totals every active weight against the line as it stands, leaving a stat of the other
     * kind out only because the line does not carry it, and the two differ on a row that carries
     * a stat it has no business carrying. Scoring a board the same on both sides beats tidiness.
     */
    private static double totalPoints(ScoredPlayer player, LeagueScoring league) {
        double total = 0;
        for (Map.Entry<String, Double> weight : league.statWeights().entrySet()) {
            if (league.activeScoringSet().contains(weight.getKey())) {
                total += player.scoringValue(weight.getKey()) * weight.getValue();
            }
        }
        return total;
    }

    private record ZScores(Map<Integer, Double> totals, Map<Integer, Map<String, Double>> byCategory) {
    }

    /**
     * Skaters and goalies are ranked against their own pool, each the size the league drafts, so
     * the two are scored separately and the results read back per player.
     */
    private static ZScores zScores(List<ScoredPlayer> rounded, LeagueScoring league) {
        List<ScoredPlayer> skaters = rounded.stream().filter(player -> !player.goalie()).toList();
        List<ScoredPlayer> goalies = rounded.stream().filter(ScoredPlayer::goalie).toList();

        Map<Integer, Double> totals = new HashMap<>();
        Map<Integer, Map<String, Double>> byCategory = new HashMap<>();
        applyPool(skaters, ScoringStatKeys.SKATER_SCORING, league, league.skaterPoolSize(), 0, totals, byCategory);
        // A goalie's GAA, save and win percentage are measured against the wire, the half a pool's
        // worth of goalies ranked straight after it: the minutes he plays are minutes his team would
        // otherwise fill from there. Against the pool's own rate, a goalie who never played scored
        // exactly average on both ratios and a rest-of-season board's pool filled up with goalies
        // who do not play. Skaters' shooting percentage stays on the pool's rate.
        int goalieWire = (league.goaliePoolSize() + 1) / 2;
        applyPool(goalies, ScoringStatKeys.GOALIE_SCORING, league, league.goaliePoolSize(), goalieWire, totals, byCategory);
        return new ZScores(totals, byCategory);
    }

    /**
     * One kind of player's z-scores. The pool is the best {@code poolSize} of them, which cannot be
     * known before they are scored — so it is guessed as everyone, scored, and re-derived from those
     * scores until it stops moving or ten passes are up. The mean and deviation come from the pool;
     * every player is then scored against it, pool member or not, so a player nobody would draft
     * still gets a comparable number. The {@code wireSize} players ranked straight after the pool
     * are the wire; where there is one, a ratio is measured against its rate rather than the pool's,
     * and it is re-derived alongside the pool.
     */
    private static void applyPool(
            List<ScoredPlayer> players,
            List<String> categoryKeys,
            LeagueScoring league,
            int poolSize,
            int wireSize,
            Map<Integer, Double> totals,
            Map<Integer, Map<String, Double>> byCategory) {
        if (players.isEmpty()) {
            return;
        }
        List<String> activeKeys = categoryKeys.stream()
                .filter(key -> league.activeScoringSet().contains(key))
                .toList();
        List<Category> categories = activeKeys.stream()
                .map(key -> Category.of(key, players))
                .toList();

        List<Integer> poolPositions = allPositions(players.size());
        List<Integer> wirePositions = List.of();
        PoolZScores scores = poolZScores(categories, poolPositions, wirePositions, players.size());

        for (int iteration = 0; iteration < MAX_POOL_ITERATIONS; iteration++) {
            double[] running = scores.totals();
            List<Integer> ranked = allPositions(players.size()).stream()
                    .sorted(Comparator.comparingDouble((Integer position) -> running[position]).reversed())
                    .toList();
            int poolEnd = Math.max(0, Math.min(poolSize, ranked.size()));
            List<Integer> next = ranked.subList(0, poolEnd);
            List<Integer> nextWire = ranked.subList(poolEnd, Math.min(poolEnd + wireSize, ranked.size()));
            if (samePositions(next, poolPositions) && samePositions(nextWire, wirePositions)) {
                break;
            }
            poolPositions = next;
            wirePositions = nextWire;
            scores = poolZScores(categories, poolPositions, wirePositions, players.size());
        }
        shiftToReplacement(scores, poolSize);

        for (int position = 0; position < players.size(); position++) {
            int playerId = players.get(position).playerId();
            totals.put(playerId, scores.totals()[position]);
            Map<String, Double> contributions = new LinkedHashMap<>();
            for (int index = 0; index < activeKeys.size(); index++) {
                double[] categoryScores = scores.byCategory()[index];
                if (categoryScores != null) {
                    contributions.put(activeKeys.get(index), categoryScores[position]);
                }
            }
            byCategory.put(playerId, contributions);
        }
    }

    /**
     * One active category's numbers, read off the pool once. A counting stat is scored as it
     * stands; a ratio is scored by what it does to a team's own ratio, which needs the volume
     * behind it as well.
     */
    private record Category(double[] values, double[] volumes, int direction) {

        static Category of(String key, List<ScoredPlayer> players) {
            double[] values = new double[players.size()];
            // A pool holds one kind of player, so its first entry says whether this is a ratio.
            boolean ratio = RatioVolumes.volume(players.get(0), key) != null;
            double[] volumes = ratio ? new double[players.size()] : null;
            for (int position = 0; position < players.size(); position++) {
                ScoredPlayer player = players.get(position);
                values[position] = player.scoringValue(key);
                if (volumes != null) {
                    Double volume = RatioVolumes.volume(player, key);
                    volumes[position] = volume == null ? 0 : volume;
                }
            }
            return new Category(values, volumes, ScoringStatKeys.LOWER_IS_BETTER.contains(key) ? -1 : 1);
        }
    }

    /**
     * Moves nought from the pool's average to the replacement player, the {@code poolSize}-th best
     * (the last a league of that size would roster), category by category so the breakdown still
     * sums to the total. The spread is measured from the mean, but half the pool sits under it, and
     * a rostered player reading as less than nothing says his team would be better off without
     * him. One constant per pool, so no order and no difference moves. He is read off the totals
     * rather than the pool, so a pool that has not settled by the last pass still puts nought at
     * the {@code poolSize}-th best.
     */
    private static void shiftToReplacement(PoolZScores scores, int poolSize) {
        double[] totals = scores.totals();
        if (poolSize <= 0 || totals.length == 0) {
            return;
        }
        List<Integer> ranked = allPositions(totals.length).stream()
                .sorted(Comparator.comparingDouble((Integer position) -> totals[position])
                        .reversed()
                        .thenComparingInt(position -> position))
                .toList();
        int replacement = ranked.get(Math.min(poolSize, ranked.size()) - 1);
        double offset = totals[replacement];
        for (int position = 0; position < totals.length; position++) {
            totals[position] -= offset;
        }
        for (double[] categoryScores : scores.byCategory()) {
            if (categoryScores == null) {
                continue;
            }
            double categoryOffset = categoryScores[replacement];
            for (int position = 0; position < categoryScores.length; position++) {
                categoryScores[position] -= categoryOffset;
            }
        }
    }

    /** One pass over a pool: the running total per player, and each category's own scores. */
    private record PoolZScores(double[] totals, double[][] byCategory) {
    }

    private static PoolZScores poolZScores(
            List<Category> categories, List<Integer> poolPositions, List<Integer> wirePositions, int size) {
        double[] totals = new double[size];
        double[][] byCategory = new double[categories.size()][];
        if (poolPositions.isEmpty()) {
            return new PoolZScores(totals, byCategory);
        }

        for (int index = 0; index < categories.size(); index++) {
            Category category = categories.get(index);
            // A ratio's contribution already carries its direction: goals prevented are good.
            Optional<double[]> ratio = category.volumes() == null
                    ? Optional.of(category.values())
                    : ratioValues(category, poolPositions, wirePositions, size);
            if (ratio.isEmpty()) {
                continue;
            }
            double[] values = ratio.get();
            int direction = category.volumes() != null ? 1 : category.direction();
            double sum = 0;
            for (int position : poolPositions) {
                sum += values[position];
            }
            double mean = sum / poolPositions.size();
            double squaredDeviations = 0;
            for (int position : poolPositions) {
                squaredDeviations += Math.pow(values[position] - mean, 2);
            }
            double stdDev = Math.sqrt(squaredDeviations / poolPositions.size());
            if (stdDev == 0) {
                continue;
            }
            double[] categoryScores = new double[size];
            for (int position = 0; position < size; position++) {
                double zScore = direction * (values[position] - mean) / stdDev;
                categoryScores[position] = zScore;
                totals[position] += zScore;
            }
            byCategory[index] = categoryScores;
        }
        return new PoolZScores(totals, byCategory);
    }

    /**
     * A ratio's value for every player: {@code (rate − reference rate) × volume}, turned round where
     * lower is better, so GAA becomes goals prevented and save percentage saves above the reference.
     * The reference is the wire's rate where there is a wire with volume, so the minutes of a goalie
     * better than what a team could pick up count for him, and the pool's own rate otherwise; either
     * is volume-weighted — summed goals against over summed hours, not the mean of the GAAs.
     *
     * <p>A line with a rate but nothing to derive a volume from is weighed at the pool's average
     * volume, which ranks it on its bare rate; a line with neither scores exactly the reference. Empty
     * when the pool has no spread in the rate, or no volume at all — which is not the same as a
     * category of zeroes, and leaves the stat out of the ranking altogether.
     */
    private static Optional<double[]> ratioValues(
            Category category, List<Integer> poolPositions, List<Integer> wirePositions, int size) {
        double[] rates = category.values();
        double[] volumes = category.volumes();

        double knownVolume = 0;
        int knownCount = 0;
        for (int position : poolPositions) {
            if (volumes[position] > 0) {
                knownVolume += volumes[position];
                knownCount++;
            }
        }
        double standInVolume = knownCount > 0 ? knownVolume / knownCount : 1;
        double[] weights = new double[size];
        for (int position = 0; position < size; position++) {
            if (volumes[position] > 0) {
                weights[position] = volumes[position];
            } else if (rates[position] != 0) {
                weights[position] = standInVolume;
            }
        }

        double weightedRate = 0;
        double totalWeight = 0;
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (int position : poolPositions) {
            double weight = weights[position];
            if (weight > 0) {
                weightedRate += rates[position] * weight;
                totalWeight += weight;
                lowest = Math.min(lowest, rates[position]);
                highest = Math.max(highest, rates[position]);
            }
        }
        // Checked on the rates themselves: a pool sharing one rate would otherwise leave rounding
        // noise in `rate − pool rate` for the standard deviation to blow up into whole z-scores.
        if (totalWeight == 0 || lowest == highest) {
            return Optional.empty();
        }
        double wireRate = 0;
        double wireWeight = 0;
        for (int position : wirePositions) {
            wireRate += rates[position] * weights[position];
            wireWeight += weights[position];
        }
        double referenceRate = wireWeight > 0 ? wireRate / wireWeight : weightedRate / totalWeight;

        double[] values = new double[size];
        for (int position = 0; position < size; position++) {
            values[position] = category.direction() * (rates[position] - referenceRate) * weights[position];
        }
        return Optional.of(values);
    }

    private static List<Integer> allPositions(int size) {
        List<Integer> positions = new ArrayList<>(size);
        for (int position = 0; position < size; position++) {
            positions.add(position);
        }
        return positions;
    }

    private static boolean samePositions(List<Integer> first, List<Integer> second) {
        return first.size() == second.size() && Set.copyOf(first).containsAll(second);
    }
}
