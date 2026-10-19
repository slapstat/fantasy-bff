package com.fantasy.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fantasy.bff.dto.request.ProjectionKind;
import com.fantasy.bff.dto.response.DraftPick;
import com.fantasy.bff.dto.response.DraftSettings;
import com.fantasy.bff.dto.response.DraftState;
import com.fantasy.bff.dto.response.DraftTeam;
import com.fantasy.bff.dto.response.GoalieResponse;
import com.fantasy.bff.dto.response.LeagueDraftPick;
import com.fantasy.bff.dto.response.LeagueDraftResponse;
import com.fantasy.bff.dto.response.LeagueDraftStatus;
import com.fantasy.bff.dto.response.LeagueDraftTeam;
import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.PositionOverride;
import com.fantasy.bff.dto.response.ProjectionData;
import com.fantasy.bff.dto.response.ProjectionResponse;
import com.fantasy.bff.dto.response.ProjectionSettings;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.dto.response.SkaterPosition;
import com.fantasy.bff.dto.response.SkaterResponse;
import com.fantasy.bff.dto.response.SummarySource;
import com.fantasy.bff.generated.db.model.PlayerProjection;
import com.fantasy.bff.generated.db.model.PlayerStats;
import com.fantasy.bff.service.scoring.LeagueSchedule;
import com.fantasy.bff.service.scoring.LeagueSummaryCalculator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * What the cold path is and is not allowed to hand back. The arithmetic behind the totals is
 * covered where it lives ({@code service.scoring}); what is decided here is where the league
 * comes from, and how much of the answer each account sees.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeagueSummaryServiceTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final UUID BOARD = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String LEAGUE = "465.l.12345";

    @Mock private YahooLeagueDraftService draftService;
    @Mock private YahooLeagueRosterService rosterService;
    @Mock private YahooLeagueService leagueService;
    @Mock private EspnLeagueRosterService espnRosterService;
    @Mock private EspnLeagueService espnLeagueService;
    @Mock private ProjectionSeedService seedService;
    @Mock private ProjectionService projectionService;
    @Mock private PlayerPoolRows poolRows;
    @Mock private PlayerService playerService;
    @Mock private AiProjectionAvailability aiProjection;
    @Mock private SeasonScheduleService scheduleService;

    private LeagueSummaryService service;

    private static PlayerProjection row(int playerId, PlayerProjection.TypeEnum type, Map<String, Double> scoring) {
        return new PlayerProjection()
                .playerId(playerId)
                .type(type)
                .stats(new PlayerStats().utility(Map.of("gp", 82.0)).scoring(scoring));
    }

    private static SkaterResponse skater(int id, String name, SkaterPosition position) {
        return new SkaterResponse(id, name, "TOR", null, null, Set.of(position), null);
    }

    @BeforeEach
    void setUp() {
        service = new LeagueSummaryService(
                draftService, rosterService, leagueService, espnRosterService, espnLeagueService,
                seedService, projectionService, poolRows, playerService, aiProjection,
                new LeagueSummaryCalculator(), scheduleService, 20262027, "v1.2.3");

        // Every club plays every night of an 82-game season and every line is 82 games, so the
        // totals here are the lineup's, exactly, with nobody missing a game.
        List<LocalDate> nights = IntStream.range(0, 82).mapToObj(LocalDate.of(2026, 10, 7)::plusDays).toList();
        when(scheduleService.schedule()).thenReturn(new LeagueSchedule(
                Map.of("TOR", nights), Map.of("TOR", 82)));

        when(aiProjection.available()).thenReturn(true);
        // Yahoo lists every roster empty unless a case says otherwise, so the picks are read.
        lenient().when(rosterService.rosters(anyString(), anyString()))
                .thenReturn(new YahooLeagueRosterService.Rosters(Map.of(), Map.of()));
        when(playerService.getSkaters()).thenReturn(List.of(
                skater(1, "Forward One", SkaterPosition.C),
                skater(2, "Forward Two", SkaterPosition.LW),
                skater(3, "Forward Three", SkaterPosition.RW)));
        when(playerService.getGoalies()).thenReturn(List.<GoalieResponse>of());
        when(seedService.seed(anyInt(), anyString())).thenReturn(new ProjectionSeedService.Seed(
                List.of(
                        row(1, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 40.0)),
                        row(2, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 30.0)),
                        row(3, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 15.0))),
                "v1.2.3", 3, 0, 0, 0, 0));
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.FINISHED,
                false,
                List.of(
                        new LeagueDraftTeam("t1", "Mine", true),
                        new LeagueDraftTeam("t2", "Theirs", false)),
                true,
                List.of(
                        new LeagueDraftPick(1, 1, "t1", 1),
                        new LeagueDraftPick(2, 1, "t2", 2),
                        new LeagueDraftPick(3, 2, "t2", 3)), null));
        when(leagueService.projectionSettings(USER, LEAGUE)).thenReturn(settings(null));
    }

    private LeagueProjectionSettingsResponse settings(Integer leagueSize) {
        return new LeagueProjectionSettingsResponse(
                ScoringBasis.POINTS,
                List.of("goals"),
                List.of("gp"),
                Map.of("goals", 1.0),
                "Beer League",
                new RosterSlots(1, 1, 1, 0, 0, 0, 0, 1, 0),
                leagueSize,
                List.of(),
                List.of(),
                Map.of(),
                null);
    }

    /** The totals are fantasy points or z-scores depending on the league, and the page has to say
     * which — so the answer travels with them rather than being asked for again. */
    @Test
    @DisplayName("says how the league scores, which is what its totals are in")
    void saysHowTheLeagueScores() {
        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.scoringType()).isEqualTo(ScoringBasis.POINTS);
    }

    @Test
    @DisplayName("totals the league's own teams from the league's own picks")
    void totalsTheLeague() {
        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.picks()).isEqualTo(3);
        assertThat(result.status()).isEqualTo(LeagueDraftStatus.FINISHED);
        assertThat(result.modelVersion()).isEqualTo("v1.2.3");
        assertThat(result.summary().teams()).extracting(team -> team.teamId())
                .containsExactly("t2", "t1");
        // Two picks worth 45 between them beat one worth 40, and the table is ordered by that.
        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
        assertThat(result.summary().teams().get(1).total()).isCloseTo(40, within(1e-9));
    }

    /** The players behind the totals are everyone's, premium or not (Alexander's call, 2026-10-09). */
    @Test
    @DisplayName("every account gets the players behind each total, the model's included")
    void everyAccountGetsThePlayers() {
        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).allSatisfy(team -> {
            assertThat(team.roster()).isNotNull();
            assertThat(team.positionPlayers()).isNotNull();
        });
        assertThat(result.summary().teams().get(0).roster()).extracting(row -> row.name())
                .containsExactly("Forward Two", "Forward Three");
        // Who he is besides his name: the club and the positions the pool lists him under.
        assertThat(result.summary().teams().get(0).roster().get(0).team()).isEqualTo("TOR");
        assertThat(result.summary().teams().get(0).roster().get(0).positions()).containsExactly("LW");
    }

    @Test
    @DisplayName("last season's stats are read from the pool, and the model is left alone")
    void lastSeasonDoesNotTouchTheModel() {
        when(poolRows.read()).thenThrow(new IllegalStateException("read"));

        assertThatThrownBy(() -> service.summarise(USER, LEAGUE, SummarySource.LAST_SEASON, null))
                .isInstanceOf(IllegalStateException.class);
        verify(seedService, never()).seed(anyInt(), anyString());
    }

    /** A team is ranked on what it holds today, so the model is asked about the games left. */
    @Test
    @DisplayName("once the season is under way the model ranks by its rest of the season")
    void theModelRanksByTheRestOfTheSeason() {
        when(seedService.inSeason(20262027)).thenReturn(Optional.of(new ProjectionSeedService.Seed(
                List.of(
                        row(1, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 20.0)),
                        row(2, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 15.0)),
                        row(3, PlayerProjection.TypeEnum.SKATER, Map.of("goals", 4.0))),
                "v1.2.4", 3, 0, 0, 0, 0)));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.inSeason()).isTrue();
        assertThat(result.summary().teams().get(0).teamId()).isEqualTo("t1");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(20, within(1e-9));
        assertThat(result.summary().teams().get(1).total()).isCloseTo(19, within(1e-9));
        verify(seedService, never()).seed(anyInt(), anyString());
    }

    /** Before the first game there is no rest of the season, and the season line is the answer. */
    @Test
    @DisplayName("before the season the model ranks by its season line")
    void beforeTheSeasonTheModelRanksByTheSeason() {
        when(seedService.inSeason(anyInt())).thenReturn(Optional.empty());

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.inSeason()).isFalse();
        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
    }

    /** A board is a whole season, and is ranked as one at any point in it (Alexander's call). */
    @Test
    @DisplayName("last season's stats are not asked about the rest of the season")
    void lastSeasonIgnoresTheRestOfTheSeason() {
        when(poolRows.read()).thenThrow(new IllegalStateException("read"));

        assertThatThrownBy(() -> service.summarise(USER, LEAGUE, SummarySource.LAST_SEASON, null))
                .isInstanceOf(IllegalStateException.class);
        verify(seedService, never()).inSeason(anyInt());
    }

    @Test
    @DisplayName("the model cannot be asked for where the AI projection is switched off")
    void modelOffIsNotFound() {
        when(aiProjection.available()).thenReturn(false);

        assertThatThrownBy(() -> service.summarise(USER, LEAGUE, SummarySource.MODEL, null))
                .isInstanceOf(NoSuchElementException.class);
        verify(draftService, never()).draft(anyString(), anyString());
    }

    @Test
    @DisplayName("a pick for a team the league does not list is dropped, not credited to anyone")
    void unknownTeamIsDropped() {
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.FINISHED,
                false,
                List.of(new LeagueDraftTeam("t1", "Mine", true)),
                true,
                List.of(new LeagueDraftPick(1, 1, "t1", 1), new LeagueDraftPick(2, 1, "ghost", 2)), null));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).hasSize(1);
        assertThat(result.summary().teams().get(0).total()).isCloseTo(40, within(1e-9));
    }

    /**
     * The z-score pools are sized from the league's size, so a league Yahoo does not count for us
     * is counted from its own teams rather than falling back to a stranger's league.
     */
    @Test
    @DisplayName("a league with no stated size is sized by its teams")
    void leagueSizeFallsBackToTheTeamCount() {
        when(leagueService.projectionSettings(USER, LEAGUE)).thenReturn(settings(null));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).hasSize(2);
    }

    /**
     * The draft said t1 took Forward One and t2 the other two. Since then t2 traded Forward Three to
     * t1, so the table must follow the rosters, not the picks.
     */
    @Test
    @DisplayName("a traded player counts for the team that holds him now")
    void tradedPlayerCountsForHisNewTeam() {
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(Map.of("t1", List.of(1, 3), "t2", List.of(2)), Map.of()));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t1", "t2");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(55, within(1e-9));
        assertThat(result.summary().teams().get(0).roster()).extracting(row -> row.name())
                .containsExactly("Forward One", "Forward Three");
        assertThat(result.summary().teams().get(1).total()).isCloseTo(30, within(1e-9));
        // The draft's own count still describes the draft.
        assertThat(result.picks()).isEqualTo(3);
        assertThat(result.status()).isEqualTo(LeagueDraftStatus.FINISHED);
    }

    @Test
    @DisplayName("a player Yahoo has parked on injured reserve is marked so on his team's row")
    void parkedPlayerIsMarked() {
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(
                Map.of("t1", List.of(1, 3), "t2", List.of(2)), Map.of(3, "NA")));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams().get(0).roster())
                .extracting(row -> row.name(), row -> row.reserveSlot())
                .containsExactly(tuple("Forward One", null), tuple("Forward Three", "NA"));
    }

    @Test
    @DisplayName("a dropped player counts for nobody")
    void droppedPlayerCountsForNobody() {
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(Map.of("t1", List.of(1), "t2", List.of(2)), Map.of()));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t1", "t2");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(40, within(1e-9));
        assertThat(result.summary().teams().get(1).total()).isCloseTo(30, within(1e-9));
        assertThat(result.summary().teams()).allSatisfy(team ->
                assertThat(team.roster()).extracting(row -> row.name()).doesNotContain("Forward Three"));
    }

    @Test
    @DisplayName("a player picked up after the draft counts for the team that picked him up")
    void pickupCounts() {
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.FINISHED,
                false,
                List.of(new LeagueDraftTeam("t1", "Mine", true), new LeagueDraftTeam("t2", "Theirs", false)),
                true,
                List.of(new LeagueDraftPick(1, 1, "t1", 1), new LeagueDraftPick(2, 1, "t2", 2)), null));
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(Map.of("t1", List.of(1), "t2", List.of(2, 3)), Map.of()));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t2", "t1");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
        assertThat(result.picks()).isEqualTo(2);
    }

    @Test
    @DisplayName("a roster for a team the league does not list is dropped, not credited to anyone")
    void rosterOfAnUnknownTeamIsDropped() {
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(Map.of("t1", List.of(1), "ghost", List.of(2, 3)), Map.of()));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t1", "t2");
        assertThat(result.summary().teams().get(1).total()).isZero();
    }

    @Test
    @DisplayName("a finished draft whose rosters Yahoo lists all empty is totalled from its picks")
    void emptyRostersFallBackToPicks() {
        when(rosterService.rosters(USER, LEAGUE)).thenReturn(new YahooLeagueRosterService.Rosters(Map.of("t1", List.of(), "t2", List.of()), Map.of()));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
        assertThat(result.summary().teams().get(1).total()).isCloseTo(40, within(1e-9));
    }

    /** A live draft's table follows the picks as they are made; its rosters are not read at all. */
    @Test
    @DisplayName("a draft under way is totalled from its picks without reading the rosters")
    void liveDraftFollowsThePicks() {
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.IN_PROGRESS,
                false,
                List.of(new LeagueDraftTeam("t1", "Mine", true), new LeagueDraftTeam("t2", "Theirs", false)),
                true,
                List.of(new LeagueDraftPick(1, 1, "t1", 1)), null));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams().get(0).teamId()).isEqualTo("t1");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(40, within(1e-9));
        verify(rosterService, never()).rosters(anyString(), anyString());
    }

    @Test
    @DisplayName("a league that has not drafted yet says so, with every team at nothing")
    void preDraftLeague() {
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.PRE_DRAFT,
                false,
                List.of(new LeagueDraftTeam("t1", "Mine", true)),
                false,
                List.of(), null));

        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.picks()).isZero();
        assertThat(result.status()).isEqualTo(LeagueDraftStatus.PRE_DRAFT);
        assertThat(result.summary().teams().get(0).total()).isZero();
    }

    private static ProjectionResponse board(
            List<com.fantasy.bff.dto.response.PlayerProjection> players, List<PositionOverride> overrides) {
        return new ProjectionResponse(
                BOARD.toString(), "My board", ProjectionKind.PROJECTION, "20262027",
                new ProjectionData(null, players, null, overrides),
                null, null, null, null, false);
    }

    private static com.fantasy.bff.dto.response.PlayerProjection boardRow(int playerId, double goals) {
        return new com.fantasy.bff.dto.response.PlayerProjection(
                playerId,
                com.fantasy.bff.dto.response.PlayerProjection.Type.SKATER,
                new com.fantasy.bff.dto.response.PlayerStats(Map.of("gp", 82.0), Map.of("goals", goals)));
    }

    /** The whole of the feature: the same league, ranked by the user's own numbers. */
    @Test
    @DisplayName("a board of the user's own ranks the league by its numbers, not the model's")
    void boardRanksByItsOwnNumbers() {
        when(projectionService.get(UUID.fromString(USER), BOARD)).thenReturn(board(
                List.of(boardRow(1, 60), boardRow(2, 10), boardRow(3, 5)), null));

        LeagueSummaryService.Result result =
                service.summarise(USER, LEAGUE, SummarySource.PROJECTION, BOARD);

        assertThat(result.source()).isEqualTo(SummarySource.PROJECTION);
        assertThat(result.projectionId()).isEqualTo(BOARD.toString());
        assertThat(result.modelVersion()).isNull();
        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t1", "t2");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(60, within(1e-9));
        assertThat(result.summary().teams().get(1).total()).isCloseTo(15, within(1e-9));
        verify(seedService, never()).seed(anyInt(), anyString());
    }

    @Test
    @DisplayName("a board that is not the user's to read is refused before Yahoo is asked")
    void unreadableBoardIsRefusedFirst() {
        when(projectionService.get(UUID.fromString(USER), BOARD))
                .thenThrow(new NoSuchElementException("no such projection"));

        assertThatThrownBy(() -> service.summarise(USER, LEAGUE, SummarySource.PROJECTION, BOARD))
                .isInstanceOf(NoSuchElementException.class);
        verify(draftService, never()).draft(anyString(), anyString());
    }

    /** The board works whether or not this environment serves the model. */
    @Test
    @DisplayName("a board can be ranked by where the AI projection is switched off")
    void boardWorksWithTheModelOff() {
        when(aiProjection.available()).thenReturn(false);
        when(projectionService.get(UUID.fromString(USER), BOARD)).thenReturn(board(
                List.of(boardRow(1, 60), boardRow(2, 10), boardRow(3, 5)), null));

        LeagueSummaryService.Result result =
                service.summarise(USER, LEAGUE, SummarySource.PROJECTION, BOARD);

        assertThat(result.summary().teams()).hasSize(2);
    }

    /** A spreadsheet import with a player missing: he counts for nothing, and the page is told. */
    @Test
    @DisplayName("counts the teams' players the board has no line for")
    void countsUnprojectedPlayers() {
        when(projectionService.get(UUID.fromString(USER), BOARD)).thenReturn(board(
                List.of(boardRow(1, 60), boardRow(2, 10)), null));

        LeagueSummaryService.Result result =
                service.summarise(USER, LEAGUE, SummarySource.PROJECTION, BOARD);

        assertThat(result.unprojectedPlayers()).isEqualTo(1);
        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("t1", "t2");
        assertThat(result.summary().teams().get(1).total()).isCloseTo(10, within(1e-9));
    }

    @Test
    @DisplayName("the model's pool leaves nobody the teams hold unprojected")
    void modelHasNoUnprojectedPlayers() {
        LeagueSummaryService.Result result = service.summarise(USER, LEAGUE, SummarySource.MODEL, null);

        assertThat(result.unprojectedPlayers()).isZero();
        assertThat(result.projectionId()).isNull();
    }

    /**
     * Forward Two is a LW in the pool, and the league has one LW slot and one C slot. His owner
     * moved him to C on the board, so the lineup must seat him there — where Forward One is not
     * on his team to compete for it.
     */
    @Test
    @DisplayName("a board's hand-set positions decide where its players are seated")
    void boardPositionOverridesApply() {
        when(projectionService.get(UUID.fromString(USER), BOARD)).thenReturn(board(
                List.of(boardRow(1, 60), boardRow(2, 10), boardRow(3, 5)),
                List.of(new PositionOverride(2, List.of(SkaterPosition.C)))));

        LeagueSummaryService.Result result =
                service.summarise(USER, LEAGUE, SummarySource.PROJECTION, BOARD);

        var theirs = result.summary().teams().stream()
                .filter(team -> team.teamId().equals("t2")).findFirst().orElseThrow();
        assertThat(theirs.positionPlayers().get("C")).extracting(player -> player.name())
                .containsExactly("Forward Two");
        assertThat(theirs.positionPlayers().getOrDefault("LW", List.of())).isEmpty();
    }

    // ---- A draft made here: its own teams, picks and league. ----

    private static final UUID DRAFT = UUID.fromString("00000000-0000-0000-0000-0000000000d0");

    private static DraftSettings draftLeague() {
        return new DraftSettings(
                ProjectionSettings.ScoringType.POINTS,
                Map.of("goals", 1.0),
                List.of("goals"),
                List.of("gp"),
                null,
                new RosterSlots(1, 1, 1, 0, 0, 0, 0, 1, 0),
                null,
                null,
                null,
                null);
    }

    private static DraftState draftState(DraftSettings league, OffsetDateTime finishedAt, List<DraftPick> picks) {
        return new DraftState(
                List.of(new DraftTeam("a", "Alpha", false), new DraftTeam("b", "Bravo", true)),
                List.of("b", "a"),
                picks,
                finishedAt,
                league,
                null);
    }

    private static ProjectionResponse draftRow(ProjectionSettings settings, DraftState draft) {
        return new ProjectionResponse(
                DRAFT.toString(), "Mock #1", ProjectionKind.DRAFT, "20262027",
                new ProjectionData(settings, List.of(), draft, null),
                null, null, null, null, false);
    }

    private static final List<DraftPick> MOCK_PICKS = List.of(
            new DraftPick(1, "b"), new DraftPick(2, "a"), new DraftPick(3, "a"));

    @Test
    @DisplayName("a draft made here is totalled from its own teams and picks")
    void totalsADraftFromItsOwnPicks() {
        when(projectionService.get(UUID.fromString(USER), DRAFT)).thenReturn(
                draftRow(null, draftState(draftLeague(), OffsetDateTime.parse("2026-09-28T10:00:00Z"), MOCK_PICKS)));

        LeagueSummaryService.Result result = service.summariseDraft(USER, DRAFT, SummarySource.MODEL, null);

        assertThat(result.status()).isEqualTo(LeagueDraftStatus.FINISHED);
        assertThat(result.picks()).isEqualTo(3);
        assertThat(result.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(result.summary().teams()).extracting(team -> team.teamId()).containsExactly("a", "b");
        assertThat(result.summary().teams().get(0).name()).isEqualTo("Alpha");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
        assertThat(result.summary().teams().get(1).mine()).isTrue();
        assertThat(result.summary().teams().get(1).total()).isCloseTo(40, within(1e-9));
        verify(draftService, never()).draft(anyString(), anyString());
    }

    /** The same rule as a league's, whichever kind of league it is. */
    @Test
    @DisplayName("a draft's totals and the players behind them reach every account")
    void aDraftGivesEveryAccountThePlayers() {
        when(projectionService.get(UUID.fromString(USER), DRAFT)).thenReturn(
                draftRow(null, draftState(draftLeague(), null, MOCK_PICKS)));

        LeagueSummaryService.Result result = service.summariseDraft(USER, DRAFT, SummarySource.MODEL, null);

        assertThat(result.status()).isEqualTo(LeagueDraftStatus.IN_PROGRESS);
        assertThat(result.summary().teams()).allSatisfy(team -> {
            assertThat(team.total()).isNotNull();
            assertThat(team.roster()).as("roster rows").isNotNull();
        });
    }

    @Test
    @DisplayName("a board is not a draft, and has no teams to total")
    void aBoardIsNotADraft() {
        when(projectionService.get(UUID.fromString(USER), DRAFT)).thenReturn(new ProjectionResponse(
                DRAFT.toString(), "My board", ProjectionKind.PROJECTION, "20262027",
                new ProjectionData(null, List.of(), null, null), null, null, null, null, false));

        assertThatThrownBy(() -> service.summariseDraft(USER, DRAFT, SummarySource.MODEL, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** A draft saved before drafts held a league is ranked by its projection's, as the board ranks it. */
    @Test
    @DisplayName("a draft with no league of its own scores by its projection's settings")
    void anOldDraftScoresByItsProjection() {
        ProjectionSettings settings = new ProjectionSettings(
                ProjectionSettings.ScoringType.POINTS, Map.of("goals", 2.0), List.of("goals"), List.of("gp"),
                null, null, true, null, null, null, null, null, null, null, null, null, null);
        when(projectionService.get(UUID.fromString(USER), DRAFT)).thenReturn(
                draftRow(settings, draftState(null, null, MOCK_PICKS)));

        LeagueSummaryService.Result result = service.summariseDraft(USER, DRAFT, SummarySource.MODEL, null);

        assertThat(result.summary().teams().get(0).total()).isCloseTo(90, within(1e-9));
    }

    @Test
    @DisplayName("the model cannot be asked for a draft where the AI projection is switched off")
    void draftModelOffIsNotFound() {
        when(aiProjection.available()).thenReturn(false);

        assertThatThrownBy(() -> service.summariseDraft(USER, DRAFT, SummarySource.MODEL, null))
                .isInstanceOf(NoSuchElementException.class);
        verify(projectionService, never()).get(UUID.fromString(USER), DRAFT);
    }
    private static final String ESPN_LEAGUE = "123";

    private void espnLeague(LeagueDraftStatus status, Map<String, List<Integer>> players) {
        when(espnRosterService.rosters(USER, ESPN_LEAGUE)).thenReturn(new EspnLeagueRosterService.Rosters(
                status,
                List.of(
                        new LeagueDraftTeam("espn.l.123.t.1", "Mine", true),
                        new LeagueDraftTeam("espn.l.123.t.2", "Theirs", false)),
                players,
                Map.of()));
        when(espnLeagueService.projectionSettings(USER, ESPN_LEAGUE)).thenReturn(settings(null));
    }

    @Test
    @DisplayName("totals an ESPN league's teams from what each holds on ESPN today")
    void totalsAnEspnLeague() {
        espnLeague(LeagueDraftStatus.FINISHED, Map.of(
                "espn.l.123.t.1", List.of(1),
                "espn.l.123.t.2", List.of(2, 3)));

        LeagueSummaryService.Result result = service.summariseEspn(USER, ESPN_LEAGUE, SummarySource.MODEL, null);

        assertThat(result.picks()).isEqualTo(3);
        assertThat(result.status()).isEqualTo(LeagueDraftStatus.FINISHED);
        assertThat(result.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(result.summary().teams()).extracting(team -> team.teamId())
                .containsExactly("espn.l.123.t.2", "espn.l.123.t.1");
        assertThat(result.summary().teams().get(0).total()).isCloseTo(45, within(1e-9));
        verify(draftService, never()).draft(anyString(), anyString());
    }

    @Test
    @DisplayName("an ESPN league gives every account the players, as a Yahoo one does")
    void espnGivesEveryAccountThePlayers() {
        espnLeague(LeagueDraftStatus.FINISHED, Map.of(
                "espn.l.123.t.1", List.of(1),
                "espn.l.123.t.2", List.of(2, 3)));

        LeagueSummaryService.Result result = service.summariseEspn(USER, ESPN_LEAGUE, SummarySource.MODEL, null);

        assertThat(result.summary().teams()).allSatisfy(team -> assertThat(team.roster()).isNotNull());
    }

    @Test
    @DisplayName("an ESPN league yet to draft has no picks, and a player the pool lacks counts as unprojected")
    void espnLeagueCountsWhatItHolds() {
        espnLeague(LeagueDraftStatus.PRE_DRAFT, Map.of(
                "espn.l.123.t.1", List.of(),
                "espn.l.123.t.2", List.of()));

        assertThat(service.summariseEspn(USER, ESPN_LEAGUE, SummarySource.MODEL, null).picks()).isZero();

        espnLeague(LeagueDraftStatus.IN_PROGRESS, Map.of(
                "espn.l.123.t.1", List.of(1, -4002),
                "espn.l.123.t.2", List.of()));

        LeagueSummaryService.Result drafting =
                service.summariseEspn(USER, ESPN_LEAGUE, SummarySource.MODEL, null);
        assertThat(drafting.picks()).isEqualTo(2);
        assertThat(drafting.unprojectedPlayers()).isEqualTo(1);
    }

    @Test
    @DisplayName("a board the user may not read stops an ESPN league before ESPN is asked")
    void espnBoardIsReadFirst() {
        when(projectionService.get(UUID.fromString(USER), BOARD)).thenThrow(new NoSuchElementException("board"));

        assertThatThrownBy(() -> service.summariseEspn(USER, ESPN_LEAGUE, SummarySource.PROJECTION, BOARD))
                .isInstanceOf(NoSuchElementException.class);
        verify(espnRosterService, never()).rosters(anyString(), anyString());
    }
}
