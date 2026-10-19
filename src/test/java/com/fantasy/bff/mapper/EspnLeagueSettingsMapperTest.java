package com.fantasy.bff.mapper;

import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.generated.espn.model.LeagueSettingsResponse;
import com.fantasy.bff.generated.espn.model.RosterSlot;
import com.fantasy.bff.generated.espn.model.StatCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class EspnLeagueSettingsMapperTest {

    private final EspnLeagueSettingsMapper mapper = new EspnLeagueSettingsMapper();

    private static StatCategory cat(int statId, String name) {
        return new StatCategory().statId(statId).name(name);
    }

    private static StatCategory cat(int statId, String name, double pointValue) {
        return new StatCategory().statId(statId).name(name).pointValue(pointValue);
    }

    private static RosterSlot slot(String position, int count) {
        return new RosterSlot().position(position).count(count);
    }

    private static LeagueSettingsResponse league(String scoringType, Integer size,
                                                 List<StatCategory> stats, List<RosterSlot> roster) {
        return new LeagueSettingsResponse()
                .leagueId("123")
                .name("Test League")
                .scoringType(scoringType)
                .size(size)
                .statCategories(stats)
                .rosterPositions(roster);
    }

    @Test
    void mapsHeadToHeadPointsLeagueDerivingWeightsAndZeroingTheRest() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_POINTS", 12,
                        List.of(cat(13, "G", 6), cat(14, "A", 4), cat(6, "SV", 0.2)),
                        List.of(slot("C", 2), slot("G", 2), slot("BN", 4))));

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "assists", "sv");
        assertThat(mapped.statWeights()).containsEntry("goals", 6.0).containsEntry("assists", 4.0)
                .containsEntry("sv", 0.2).containsEntry("hits", 0.0);
        assertThat(mapped.leagueSize()).isEqualTo(12);
        // Carried through so a projection synced from ESPN can name the league it came from —
        // the client only ever had the id it typed.
        assertThat(mapped.leagueName()).isEqualTo("Test League");
        assertThat(mapped.rosterSlots().c()).isEqualTo(2);
        assertThat(mapped.rosterSlots().g()).isEqualTo(2);
        assertThat(mapped.rosterSlots().bn()).isEqualTo(4);
        assertThat(mapped.rosterSlots().d()).isZero();
    }

    /**
     * The skater half of a real ESPN H2H points league: ESPN lists +/-, P, PPG, PPA and FW
     * among its scoringItems at 0 points. They score nothing, so they are not league stats —
     * showing them as zero-weight columns read as if the league counted them.
     */
    @Test
    void dropsStatsAPointsLeagueListsAtZeroPoints() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_POINTS", null,
                        List.of(cat(15, "+/-", 0), cat(16, "P", 0), cat(18, "PPG", 0), cat(19, "PPA", 0),
                                cat(23, "FOW", 0), cat(13, "G", 2), cat(14, "A", 1), cat(38, "PPP", 0.5),
                                cat(4, "GA", -2), cat(2, "L", 0), cat(99, "MYSTERY", 0)),
                        List.of()));

        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "assists", "ppp", "ga");
        assertThat(mapped.statWeights()).containsEntry("goals", 2.0).containsEntry("ga", -2.0)
                .containsEntry("plusMinus", 0.0).containsEntry("points", 0.0);
        assertThat(mapped.unsupportedStats()).isEmpty();
    }

    @Test
    void keepsZeroPointStatsInACategoryLeague() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(cat(13, "G", 0), cat(15, "+/-", 0)), List.of()));

        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "plusMinus");
    }

    @Test
    void keepsAZeroPointUtilityStatAsAUtilityColumn() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_POINTS", null, List.of(cat(27, "ATOI", 0), cat(13, "G", 2)), List.of()));

        assertThat(mapped.activeUtilityColumns()).containsExactly("gp", "toiPerGame");
    }

    @Test
    void mapsHeadToHeadCategoriesLeagueWithNullWeights() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", 10,
                        List.of(cat(13, "G"), cat(14, "A"), cat(1, "W"), cat(11, "SV%")),
                        List.of()));

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
        assertThat(mapped.statWeights()).isNull();
        assertThat(mapped.activeScoringColumns()).containsExactly("goals", "assists", "w", "svPct");
        assertThat(mapped.leagueSize()).isEqualTo(10);
    }

    @Test
    void mapsRotisserieLeagueAsCategory() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("ROTO", null, List.of(cat(13, "G"), cat(14, "A")), List.of()));

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
        assertThat(mapped.statWeights()).isNull();
    }

    @Test
    void mapsTotalPointsLeagueAsPoints() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("TOTAL_POINTS", null, List.of(cat(13, "G", 1.5)), List.of()));

        assertThat(mapped.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(mapped.statWeights()).containsEntry("goals", 1.5);
    }

    @Test
    void fallsBackToPointValuePresenceForUnrecognisedScoringType() {
        LeagueProjectionSettingsResponse withWeights = mapper.toProjectionSettings(
                league("MYSTERY", null, List.of(cat(13, "G", 2)), List.of()));
        assertThat(withWeights.scoringType()).isEqualTo(ScoringBasis.POINTS);

        LeagueProjectionSettingsResponse withoutWeights = mapper.toProjectionSettings(
                league("MYSTERY", null, List.of(cat(13, "G")), List.of()));
        assertThat(withoutWeights.scoringType()).isEqualTo(ScoringBasis.CATEGORY);

        LeagueProjectionSettingsResponse zeroWeights = mapper.toProjectionSettings(
                league("MYSTERY", null, List.of(cat(13, "G", 0)), List.of()));
        assertThat(zeroWeights.scoringType()).isEqualTo(ScoringBasis.CATEGORY);
    }

    @Test
    void flagsStatsWithNoProjectionEquivalentAsUnsupported() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(cat(13, "G"), cat(99, "Defensive Points")), List.of()));

        assertThat(mapped.activeScoringColumns()).containsExactly("goals");
        assertThat(mapped.unsupportedStats()).containsExactly("Defensive Points");
    }

    @Test
    void mapsPowerPlayAndShortHandedPointsWhichEspnScoresSeparately() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_POINTS", null, List.of(cat(38, "PPP", 2), cat(39, "SHP", 3)), List.of()));

        assertThat(mapped.activeScoringColumns()).containsExactly("ppp", "shp");
        assertThat(mapped.statWeights()).containsEntry("ppp", 2.0).containsEntry("shp", 3.0);
        assertThat(mapped.unsupportedStats()).isEmpty();
    }

    @Test
    void mapsTheStatsOnlyEspnScores() {
        // Hat tricks, shifts, goalie overtime losses and special-teams points have no Yahoo
        // equivalent, which is why they used to land in unsupportedStats. The projection domain
        // covers them now, so an ESPN league that scores them is mapped in full.
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null,
                        List.of(cat(13, "G"), cat(28, "HAT"), cat(9, "OTL"), cat(37, "STP"),
                                cat(25, "SHIFTS"), cat(33, "DEF"), cat(12, "W%"), cat(16, "P")),
                        List.of()));

        assertThat(mapped.activeScoringColumns())
                .containsExactly("goals", "hatTricks", "otl", "stp", "shifts", "defPoints", "winPct", "points");
        assertThat(mapped.unsupportedStats()).isEmpty();
    }

    @Test
    void countsGoalieGamesPlayedWhichEspnNumbersDifferentlyFromSkaterGames() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(cat(30, "GP"), cat(1, "W")), List.of()));

        assertThat(mapped.activeUtilityColumns()).containsExactly("gp");
        assertThat(mapped.activeScoringColumns()).containsExactly("w");
        assertThat(mapped.unsupportedStats()).isEmpty();
    }

    @Test
    void routesGpAndToiToUtilityAndAlwaysKeepsGp() {
        LeagueProjectionSettingsResponse withToi = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(cat(27, "ATOI"), cat(13, "G")), List.of()));
        assertThat(withToi.activeUtilityColumns()).containsExactly("gp", "toiPerGame");

        LeagueProjectionSettingsResponse noUtil = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(cat(13, "G")), List.of()));
        assertThat(noUtil.activeUtilityColumns()).containsExactly("gp");
    }

    @Test
    void mapsRosterSlotsIgnoresIrAndKeepsTheForwardFlexSlot() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_CATEGORY", null, List.of(),
                        List.of(slot("C", 2), slot("LW", 2), slot("RW", 2), slot("D", 4),
                                slot("Util", 1), slot("G", 2), slot("BN", 4), slot("IR", 2), slot("F", 1))));

        assertThat(mapped.rosterSlots()).isEqualTo(new RosterSlots(2, 2, 2, 0, 1, 4, 1, 4, 2));
        assertThat(mapped.unsupportedRosterCodes()).containsExactly("IR");
        assertThat(mapped.reserveSlots()).containsExactly(entry("IR", 2));
    }

    @Test
    void leavesTheWeeklyAddLimitNullSinceEspnServiceDoesNotReadIt() {
        assertThat(mapper.toProjectionSettings(league("H2H_POINTS", null, List.of(), List.of()))
                .maxWeeklyAdds()).isNull();
    }

    /**
     * An ESPN league of nine forwards and no centre or wing slots at all: espn-service reads
     * lineupSlotCounts {3: 9, 4: 5, 5: 2, 6: 1, 7: 5, 8: 1} as F 9, D 5, G 2, Util 1, BN 5, IR 1.
     * Before F was a slot of its own this board read Util 10, and Util takes a defenceman.
     */
    @Test
    void mapsAForwardsOnlyLeagueToForwardSlotsNotUtil() {
        LeagueProjectionSettingsResponse mapped = mapper.toProjectionSettings(
                league("H2H_POINTS", null, List.of(),
                        List.of(slot("F", 9), slot("D", 5), slot("G", 2), slot("Util", 1),
                                slot("BN", 5), slot("IR", 1))));

        assertThat(mapped.rosterSlots()).isEqualTo(new RosterSlots(0, 0, 0, 0, 9, 5, 1, 5, 2));
        assertThat(mapped.unsupportedRosterCodes()).containsExactly("IR");
    }

    @Test
    void clampsLeagueSizeAndLeavesItNullWhenUnknown() {
        assertThat(mapper.toProjectionSettings(league("H2H_CATEGORY", null, List.of(), List.of()))
                .leagueSize()).isNull();
        assertThat(mapper.toProjectionSettings(league("H2H_CATEGORY", 40, List.of(), List.of()))
                .leagueSize()).isEqualTo(30);
        assertThat(mapper.toProjectionSettings(league("H2H_CATEGORY", 1, List.of(), List.of()))
                .leagueSize()).isEqualTo(2);
        assertThat(mapper.toProjectionSettings(league("H2H_CATEGORY", 14, List.of(), List.of()))
                .leagueSize()).isEqualTo(14);
    }
}
