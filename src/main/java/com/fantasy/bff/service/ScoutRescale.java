package com.fantasy.bff.service;

import com.fantasy.bff.dto.response.ScoutLine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An earlier line of the model's brought onto today's footing, so the two can be read side by side.
 *
 * <p>A line is its per-game rates over the games it expected him to play, and the games it
 * expected are a share of the games his club had left on the day it was made. A line from a
 * month ago covers games that have since been played, so its totals are not comparable with
 * today's rest of the season; its rates and its share are. The old line is therefore re-spread
 * over the games his club has left today: the share of the schedule it expected him to play, of
 * today's schedule, at its own rates. A player the old line expected to miss a third of the
 * season still misses a third of what is left; a player it had at 14 minutes a night still has
 * them.
 *
 * <p>Alexander's own description of the fix: take the projection from then and adjust it so it
 * starts from the same place as today's, with the games left today.
 *
 * <p><b>And on today's lift</b> ({@link #atTodaysLift}). A served line is the model's expectation
 * lifted toward what its rank of a real season takes, and the lift is a property of the field on
 * the day it was made, not of the player: the preseason line lifted Kaprizov's goal rate 11.6%,
 * the rest of the season on 2026-10-23 3.8%, with his expectation's rate unchanged. Compared as
 * served, he fell five places on a lift and nothing else. So the earlier line is compared as its
 * expectation, re-spread, and then lifted by exactly what today's line was lifted by, stat by
 * stat: the distance between the two is then what the model changed its mind on, on today's
 * scale.
 */
final class ScoutRescale {

    /**
     * The stats on a {@link ScoutLine} that are rates rather than totals: per game or per shot, so
     * they carry over as they are. Everything else scales with the games.
     */
    static final Set<String> RATES = Set.of("toiPerGame", "shPct", "svPct", "gaa", "winPct");

    /**
     * The one stat a lift moves by an amount rather than a share: plus/minus is signed and sits
     * near nought, where a share of it is a division by nearly nothing.
     */
    static final Set<String> SIGNED = Set.of("plusMinus");

    private ScoutRescale() {}

    /**
     * {@code thenExpected}, the model's expectation on an earlier day, re-spread over today's games
     * left ({@link #toGamesLeft}) and lifted by what today's served line was lifted by: each stat
     * by the share {@code nowServed} has over {@code nowExpected}, plus/minus by the amount, the
     * games by the games'. A stat today's two lines do not both carry, or one the expectation has
     * none of, is left unlifted. Null where there is nothing to re-spread.
     */
    static ScoutLine atTodaysLift(
            ScoutLine thenExpected,
            double clubGamesThen,
            int clubGamesNow,
            ScoutLine nowExpected,
            ScoutLine nowServed) {
        ScoutLine spread = toGamesLeft(thenExpected, clubGamesThen, clubGamesNow);
        if (spread == null || nowExpected == null || nowServed == null) {
            return spread;
        }
        Map<String, Double> stats = new LinkedHashMap<>();
        spread.stats().forEach((key, value) -> {
            Double served = nowServed.stats().get(key);
            Double expected = nowExpected.stats().get(key);
            if (served == null || expected == null) {
                stats.put(key, value);
            } else if (SIGNED.contains(key)) {
                stats.put(key, value + served - expected);
            } else {
                stats.put(key, value * share(served, expected));
            }
        });
        return new ScoutLine(spread.games() * share(nowServed.games(), nowExpected.games()), stats);
    }

    private static double share(double served, double expected) {
        return expected == 0 ? 1.0 : served / expected;
    }

    /**
     * {@code then} re-spread over {@code clubGamesNow}, given the {@code clubGamesThen} it was spread
     * over. Null for a line with no games, or no schedule, behind it: there is no rate to carry.
     */
    static ScoutLine toGamesLeft(ScoutLine then, double clubGamesThen, int clubGamesNow) {
        if (then == null || then.games() <= 0 || clubGamesThen <= 0 || clubGamesNow < 0) {
            return null;
        }
        double games = then.games() / clubGamesThen * clubGamesNow;
        double factor = games / then.games();
        Map<String, Double> stats = new LinkedHashMap<>();
        then.stats().forEach((key, value) -> stats.put(key, RATES.contains(key) ? value : value * factor));
        return new ScoutLine(games, stats);
    }
}
