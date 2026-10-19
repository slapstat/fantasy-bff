package com.fantasy.bff.mapper;

import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.generated.yahoo.model.LeagueSettingsResponse;
import com.fantasy.bff.generated.yahoo.model.RosterSlot;
import com.fantasy.bff.generated.yahoo.model.StatCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class YahooLeagueSettingsMapperTest {

    private final YahooLeagueSettingsMapper mapper = new YahooLeagueSettingsMapper();

    private static StatCategory cat(int statId, String name) {
        return new StatCategory().statId(statId).name(name).displayName(name);
    }

    private static StatCategory cat(int statId, String name, double pointValue) {
        return new StatCategory().statId(statId).name(name).displayName(name).pointValue(pointValue);
    }

    private static StatCategory displayOnlyCat(int statId, String name) {
        return new StatCategory().statId(statId).name(name).displayName(name).displayOnly(true);
    }

    private static RosterSlot slot(String position, int count) {
        return new RosterSlot().position(position).count(count);
    }

    private static LeagueSettingsResponse league(String scoringType,
                                                 List<StatCategory> stats,
                                                 List<RosterSlot> roster) {
        return new LeagueSettingsResponse()
                .leagueKey("453.l.1")
                .name("Test League")
                .scoringType(scoringType)
                .statCategories(stats)
                .rosterPositions(roster);
    }

    @Test
    void mapsHeadToHeadPointsLeagueDerivingWeightsAndZeroingTheRest() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("headpoint",
                        List.of(cat(1, "Goals", 3), cat(2, "Assists", 2), cat(25, "Saves", 0.2)),
                        List.of(slot("C", 2), slot("G", 2), slot("BN", 4))),
                12);

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "assists", "sv");
        assertThat(mapped.statWeights()).containsEntry("goals", 3.0).containsEntry("assists", 2.0)
                .containsEntry("sv", 0.2).containsEntry("hits", 0.0);
        assertThat(mapped.leagueSize()).isEqualTo(12);
        assertThat(mapped.rosterSlots().c()).isEqualTo(2);
        assertThat(mapped.rosterSlots().g()).isEqualTo(2);
        assertThat(mapped.rosterSlots().bn()).isEqualTo(4);
        assertThat(mapped.rosterSlots().d()).isZero();
    }

    @Test
    void mapsHeadToHeadCategoriesLeagueWithNullWeights() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("head",
                        List.of(cat(1, "Goals"), cat(2, "Assists"), cat(19, "Wins"), cat(26, "Save %")),
                        List.of()),
                10);

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
        assertThat(mapped.statWeights()).isNull();
        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "assists", "w", "svPct");
        assertThat(mapped.leagueSize()).isEqualTo(10);
    }

    @Test
    void mapsRotisserieLeagueAsCategory() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("roto", List.of(cat(1, "Goals"), cat(2, "Assists")), List.of()), null);

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
        assertThat(mapped.statWeights()).isNull();
    }

    @Test
    void mapsSeasonLongPointsLeagueAsPoints() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("point", List.of(cat(1, "Goals", 1.5)), List.of()), null);

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(mapped.statWeights()).containsEntry("goals", 1.5);
    }

    @Test
    void fallsBackToPointValuePresenceForUnrecognisedScoringType() {
        LeagueProjectionSettingsResponse withWeights = mapper.toProjectionSettings(
                league("mystery", List.of(cat(1, "Goals", 2)), List.of()), null);
        assertThat(withWeights.scoringType()).isEqualTo(ScoringBasis.POINTS);

        LeagueProjectionSettingsResponse withoutWeights = mapper.toProjectionSettings(
                league("mystery", List.of(cat(1, "Goals")), List.of()), null);
        assertThat(withoutWeights.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
    }

    @Test
    void flagsStatsWithNoProjectionEquivalentAsUnsupported() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("head", List.of(cat(1, "Goals"), cat(13, "Game-Tying Goals")), List.of()), null);

        assertThat(mapped.activeScoringColumns()).containsExactly("goals");
        assertThat(mapped.unsupportedStats()).containsExactly("Game-Tying Goals");
    }

    @Test
    void routesGpAndToiCategoriesToUtilityAndAlwaysKeepsGp() {
        LeagueProjectionSettingsResponse withToi = mapper.toProjectionSettings(
                league("head", List.of(cat(34, "Time on Ice/G"), cat(1, "Goals")), List.of()), null);
        assertThat(withToi.activeUtilityColumns()).containsExactly("gp", "toiPerGame");

        LeagueProjectionSettingsResponse noUtil = mapper.toProjectionSettings(
                league("head", List.of(cat(1, "Goals")), List.of()), null);
        assertThat(noUtil.activeUtilityColumns()).containsExactly("gp");
    }

    @Test
    void mapsRosterSlotsIgnoresIrAndKeepsTheWingFlexSlot() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("head", List.of(),
                        List.of(slot("C", 2), slot("LW", 2), slot("RW", 2), slot("D", 4),
                                slot("Util", 1), slot("G", 2), slot("BN", 4), slot("IR", 2), slot("W", 1))),
                null);

        assertThat(mapped.rosterSlots()).isEqualTo(new RosterSlots(2, 2, 2, 1, 0, 4, 1, 4, 2));
        assertThat(mapped.unsupportedRosterCodes()).containsExactly("IR");
        assertThat(mapped.reserveSlots()).containsExactly(entry("IR", 2));
    }

    @Test
    void mapsTheWingAndForwardFlexSlotsToTheirOwnSlots() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("headpoint", List.of(),
                        List.of(slot("C", 1), slot("W", 2), slot("F", 2), slot("D", 4), slot("Util", 1),
                                slot("G", 2), slot("BN", 4), slot("IR+", 1), slot("NA", 1))),
                12);

        assertThat(mapped.rosterSlots()).isEqualTo(new RosterSlots(1, 0, 0, 2, 2, 4, 1, 4, 2));
        assertThat(mapped.unsupportedRosterCodes()).containsExactly("IR+", "NA");
        // Not-active holds prospects, not the injured: no reserve slot.
        assertThat(mapped.reserveSlots()).containsExactly(entry("IR+", 1));
    }

    /** A code the mapper does not know still counts as a skater flex, and is reported. */
    @Test
    void countsAnUnknownRosterCodeAsUtilAndReportsIt() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("head", List.of(), List.of(slot("C", 2), slot("XX", 1))), null);

        assertThat(mapped.rosterSlots()).isEqualTo(new RosterSlots(2, 0, 0, 0, 0, 0, 1, 0, 0));
        assertThat(mapped.unsupportedRosterCodes()).containsExactly("XX");
        assertThat(mapped.reserveSlots()).isEmpty();
    }

    @Test
    void passesTheWeeklyAddLimitThroughAndLeavesItNullWhenTheLeagueSetsNone() {
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()).maxWeeklyAdds(4), null)
                .maxWeeklyAdds()).isEqualTo(4);
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()), null)
                .maxWeeklyAdds()).isNull();
    }

    @Test
    void readsAWeeklyAddLimitBelowOneAsNone() {
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()).maxWeeklyAdds(0), null)
                .maxWeeklyAdds()).isNull();
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()).maxWeeklyAdds(-1), null)
                .maxWeeklyAdds()).isNull();
    }

    @Test
    void clampsLeagueSizeAndLeavesItNullWhenUnknown() {
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()), null)
                .leagueSize()).isNull();
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()), 40)
                .leagueSize()).isEqualTo(30);
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()), 1)
                .leagueSize()).isEqualTo(2);
        assertThat(mapper.toProjectionSettings(league("head", List.of(), List.of()), 14)
                .leagueSize()).isEqualTo(14);
    }

    @Test
    void skipsDisplayOnlyStatsFromScoringColumns() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("head",
                        List.of(cat(19, "Wins"), cat(23, "Goals Against Average"), cat(26, "Save %"),
                                displayOnlyCat(24, "Shots Against"), displayOnlyCat(25, "Saves"),
                                displayOnlyCat(22, "Goals Against")),
                        List.of()),
                null);

        assertThat(mapped.activeScoringColumns()).containsExactly("w", "gaa", "svPct");
    }
}
