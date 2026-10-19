package com.fantasy.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fantasy.bff.dto.response.DraftAnalysisPick;
import com.fantasy.bff.dto.response.DraftAnalysisResponse;
import com.fantasy.bff.dto.response.DraftAnalysisTeam;
import com.fantasy.bff.dto.response.DraftPickGrade;
import com.fantasy.bff.dto.response.GoalieResponse;
import com.fantasy.bff.dto.response.LeagueDraftPick;
import com.fantasy.bff.dto.response.LeagueDraftResponse;
import com.fantasy.bff.dto.response.LeagueDraftStatus;
import com.fantasy.bff.dto.response.LeagueDraftTeam;
import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.dto.response.SkaterPosition;
import com.fantasy.bff.dto.response.SkaterResponse;
import com.fantasy.bff.generated.db.model.PlayerProjection;
import com.fantasy.bff.generated.db.model.PlayerStats;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Where Draft Analysis takes its ranking from, and how much of it each account sees. The grading
 * itself is covered in {@code DraftGraderTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DraftAnalysisServiceTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String LEAGUE = "465.l.12345";
    private static final int SEASON = 2026;

    @Mock private DraftAnalysisAvailability availability;
    @Mock private YahooLeagueDraftService draftService;
    @Mock private YahooLeagueService leagueService;
    @Mock private ProjectionSeedService seedService;
    @Mock private PlayerService playerService;
    @Mock private EntitlementService entitlementService;

    private DraftAnalysisService service;

    private static PlayerProjection row(int playerId, double goals) {
        return new PlayerProjection()
                .playerId(playerId)
                .type(PlayerProjection.TypeEnum.SKATER)
                .stats(new PlayerStats().utility(Map.of("gp", 82.0)).scoring(Map.of("goals", goals)));
    }

    private static ProjectionSeedService.Seed seed(String version, List<PlayerProjection> rows) {
        return new ProjectionSeedService.Seed(rows, version, rows.size(), 0, 0, 0, 0);
    }

    @BeforeEach
    void setUp() {
        service = new DraftAnalysisService(
                availability, draftService, leagueService, seedService, playerService, entitlementService,
                SEASON, "");

        when(playerService.getSkaters()).thenReturn(List.of(
                new SkaterResponse(1, "Forward One", "TOR", null, null, Set.of(SkaterPosition.C), null),
                new SkaterResponse(2, "Forward Two", "BOS", null, null, Set.of(SkaterPosition.C), null),
                new SkaterResponse(3, "Forward Three", "MTL", null, null, Set.of(SkaterPosition.C), null),
                new SkaterResponse(4, "Defence One", "COL", null, null, Set.of(SkaterPosition.D), null),
                new SkaterResponse(5, "Defence Two", "BUF", null, null, Set.of(SkaterPosition.D), null),
                new SkaterResponse(6, "Defence Three", "NYR", null, null, Set.of(SkaterPosition.D), null)));
        when(playerService.getGoalies()).thenReturn(List.<GoalieResponse>of());
        when(seedService.seed(SEASON, "preseason")).thenReturn(seed("preseason", List.of(
                row(1, 40.0), row(2, 30.0), row(3, 15.0), row(4, 12.0), row(5, 10.0), row(6, 5.0))));
        when(draftService.draft(USER, LEAGUE)).thenReturn(new LeagueDraftResponse(
                LeagueDraftStatus.FINISHED,
                false,
                List.of(new LeagueDraftTeam("t1", "Mine", true), new LeagueDraftTeam("t2", "Jocke", false)),
                true,
                List.of(
                        new LeagueDraftPick(1, 1, "t1", 3),
                        new LeagueDraftPick(2, 1, "t2", 1),
                        new LeagueDraftPick(3, 2, "t1", 4)),
                null));
        when(leagueService.projectionSettings(USER, LEAGUE)).thenReturn(new LeagueProjectionSettingsResponse(
                ScoringBasis.POINTS,
                List.of("goals"),
                List.of("gp"),
                Map.of("goals", 1.0),
                "Beer League",
                new RosterSlots(1, 0, 0, 0, 0, 1, 0, 0, 0),
                2,
                List.of(),
                List.of(),
                Map.of(),
                null));
        when(entitlementService.hasPremiumAccess(anyString())).thenReturn(true);
    }

    /**
     * Two teams starting a centre and a defenceman each: the third centre and the third defenceman
     * are the replacements (15 and 5 goals), so the best defenceman's 12 goals are worth more over
     * his replacement than the third centre's 15.
     */
    @Test
    void gradesEveryPickByValueOverReplacementOnThePreseasonLine() {
        DraftAnalysisResponse analysis = service.yahoo(USER, LEAGUE);

        assertThat(analysis.preseason()).isTrue();
        assertThat(analysis.modelVersion()).isEqualTo("preseason");
        assertThat(analysis.scoringType()).isEqualTo(ScoringBasis.POINTS);
        assertThat(analysis.premium()).isTrue();

        DraftAnalysisPick first = analysis.picks().get(0);
        assertThat(first.name()).isEqualTo("Forward Three");
        assertThat(first.aiRank()).isEqualTo(5);
        assertThat(first.positionRank()).isEqualTo("C3");
        assertThat(first.value()).isEqualTo(15.0);
        assertThat(first.valueOverSlot()).isEqualTo(0.0 - 25.0);
        assertThat(first.grade()).isEqualTo(DraftPickGrade.BIG_REACH);
        assertThat(first.bestAvailable().name()).isEqualTo("Forward One");

        DraftAnalysisPick second = analysis.picks().get(1);
        assertThat(second.grade()).isEqualTo(DraftPickGrade.GOOD);

        DraftAnalysisPick defenceman = analysis.picks().get(2);
        assertThat(defenceman.aiRank()).as("fourth in goals, third over replacement").isEqualTo(3);
        assertThat(defenceman.positionRank()).isEqualTo("D1");
        assertThat(defenceman.grade()).isEqualTo(DraftPickGrade.FAIR);
        assertThat(analysis.teams()).extracting(DraftAnalysisTeam::name).containsExactly("Jocke", "Mine");
    }

    @Test
    void fallsBackToTheCurrentSeasonLineWhereNoPreseasonLineIsStored() {
        when(seedService.seed(SEASON, "preseason")).thenReturn(seed("preseason", List.of()));
        when(seedService.seed(SEASON, "")).thenReturn(seed("marcel-v118", List.of(
                row(1, 10.0), row(2, 30.0), row(3, 20.0))));

        DraftAnalysisResponse analysis = service.yahoo(USER, LEAGUE);

        assertThat(analysis.preseason()).isFalse();
        assertThat(analysis.modelVersion()).isEqualTo("marcel-v118");
        assertThat(analysis.picks().get(0).aiRank()).isEqualTo(2);
    }

    @Test
    void withoutPremiumThePicksCarryNothingOfTheModelsButTheTeamsSumsStay() {
        when(entitlementService.hasPremiumAccess(USER)).thenReturn(false);

        DraftAnalysisResponse analysis = service.yahoo(USER, LEAGUE);

        assertThat(analysis.premium()).isFalse();
        assertThat(analysis.picks()).allSatisfy(pick -> {
            assertThat(pick.name()).isNotNull();
            assertThat(pick.aiRank()).isNull();
            assertThat(pick.positionRank()).isNull();
            assertThat(pick.value()).isNull();
            assertThat(pick.valueOverSlot()).isNull();
            assertThat(pick.grade()).isNull();
            assertThat(pick.bestAvailable()).isNull();
        });
        assertThat(analysis.teams()).extracting(DraftAnalysisTeam::grade).containsExactly("A", "F");
    }

    @Test
    void offAsksNothingOfYahooOrTheModel() {
        doThrow(new NoSuchElementException("off")).when(availability).require();

        assertThatThrownBy(() -> service.yahoo(USER, LEAGUE)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(draftService, leagueService, seedService);
    }
}
