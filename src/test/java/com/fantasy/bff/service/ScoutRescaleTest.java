package com.fantasy.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fantasy.bff.dto.response.ScoutLine;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An earlier line brought onto today's games left: its share of the schedule, at its own rates. */
class ScoutRescaleTest {

    @Test
    void reSpreadsTheTotalsOverTheShareOfTodaysGamesItExpectedAndKeepsTheRates() {
        // 80 of 84 games expected, 40 goals, 20 minutes a night, 10% shooting.
        ScoutLine then = new ScoutLine(80, Map.of(
                "goals", 40.0, "toi", 96000.0, "toiPerGame", 1200.0, "shPct", 10.0));

        ScoutLine rescaled = ScoutRescale.toGamesLeft(then, 84, 42);

        // Half the schedule left, so 40 of the 42 games and half the counting totals.
        assertThat(rescaled.games()).isCloseTo(40.0, within(1e-9));
        assertThat(rescaled.stats().get("goals")).isCloseTo(20.0, within(1e-9));
        assertThat(rescaled.stats().get("toi")).isCloseTo(48000.0, within(1e-9));
        assertThat(rescaled.stats().get("toiPerGame")).isEqualTo(1200.0);
        assertThat(rescaled.stats().get("shPct")).isEqualTo(10.0);
    }

    @Test
    void aGoaliesStartsAreTheGamesAndHisRatesCarry() {
        ScoutLine then = new ScoutLine(50, Map.of("gs", 50.0, "w", 25.0, "svPct", 0.91, "gaa", 2.6, "winPct", 0.5));

        ScoutLine rescaled = ScoutRescale.toGamesLeft(then, 73, 78);

        assertThat(rescaled.games()).isCloseTo(53.4247, within(1e-4));
        assertThat(rescaled.stats().get("gs")).isCloseTo(53.4247, within(1e-4));
        assertThat(rescaled.stats().get("w")).isCloseTo(26.7123, within(1e-4));
        assertThat(rescaled.stats().get("svPct")).isEqualTo(0.91);
        assertThat(rescaled.stats().get("gaa")).isEqualTo(2.6);
        assertThat(rescaled.stats().get("winPct")).isEqualTo(0.5);
    }

    @Test
    void aLineWithNoGamesOrNoScheduleBehindItHasNoRateToCarry() {
        ScoutLine empty = new ScoutLine(0, Map.of("goals", 0.0));

        assertThat(ScoutRescale.toGamesLeft(empty, 84, 42)).isNull();
        assertThat(ScoutRescale.toGamesLeft(new ScoutLine(10, Map.of("goals", 5.0)), 0, 42)).isNull();
        assertThat(ScoutRescale.toGamesLeft(null, 84, 42)).isNull();
    }

    @Test
    void anEarlierExpectationIsLiftedByWhatTodaysLineWasLiftedBy() {
        // Kaprizov on 2026-10-23: his preseason expectation 39.9 goals over 72.7 of 84 games, today's
        // 38.1 over 69.3 of 74, served 41.6 over 73.1. Preseason he was served 49.4, lifted 11.6% on
        // the rate, so compared as served he fell; on today's lift both sides he rose.
        ScoutLine thenExpected = new ScoutLine(72.7, Map.of("goals", 39.9, "plusMinus", 10.0));
        ScoutLine nowExpected = new ScoutLine(69.3, Map.of("goals", 38.1, "plusMinus", 12.0));
        ScoutLine nowServed = new ScoutLine(73.1, Map.of("goals", 41.6, "plusMinus", 14.0));

        ScoutLine then = ScoutRescale.atTodaysLift(thenExpected, 84, 74, nowExpected, nowServed);

        double spreadGoals = 39.9 * 74 / 84;
        assertThat(then.stats().get("goals")).isCloseTo(spreadGoals * 41.6 / 38.1, within(1e-9));
        assertThat(then.games()).isCloseTo(72.7 * 74 / 84 * 73.1 / 69.3, within(1e-9));
        assertThat(nowServed.stats().get("goals") - then.stats().get("goals")).isPositive();
        // Plus/minus moves by the amount today's was lifted by.
        assertThat(then.stats().get("plusMinus")).isCloseTo(10.0 * 74 / 84 + 2.0, within(1e-9));
    }

    @Test
    void aStatTodaysLinesDoNotBothCarryIsLeftUnlifted() {
        ScoutLine thenExpected = new ScoutLine(80, Map.of("goals", 20.0, "hits", 40.0, "blocks", 30.0));
        ScoutLine nowExpected = new ScoutLine(80, Map.of("goals", 20.0, "hits", 0.0));
        ScoutLine nowServed = new ScoutLine(84, Map.of("goals", 22.0, "hits", 5.0));

        ScoutLine then = ScoutRescale.atTodaysLift(thenExpected, 84, 84, nowExpected, nowServed);

        assertThat(then.stats().get("goals")).isCloseTo(22.0, within(1e-9));
        assertThat(then.stats().get("hits")).isCloseTo(40.0, within(1e-9));
        assertThat(then.stats().get("blocks")).isCloseTo(30.0, within(1e-9));
        assertThat(then.games()).isCloseTo(84.0, within(1e-9));
    }

    @Test
    void withoutTodaysTwoLinesItIsTheReSpreadLine() {
        ScoutLine thenExpected = new ScoutLine(80, Map.of("goals", 40.0));

        assertThat(ScoutRescale.atTodaysLift(thenExpected, 84, 42, null, null))
                .isEqualTo(ScoutRescale.toGamesLeft(thenExpected, 84, 42));
        assertThat(ScoutRescale.atTodaysLift(null, 84, 42, thenExpected, thenExpected)).isNull();
    }

    @Test
    void noGamesLeftIsAnEmptyLineRatherThanNone() {
        ScoutLine rescaled = ScoutRescale.toGamesLeft(new ScoutLine(10, Map.of("goals", 5.0)), 20, 0);

        assertThat(rescaled.games()).isZero();
        assertThat(rescaled.stats().get("goals")).isZero();
    }
}
