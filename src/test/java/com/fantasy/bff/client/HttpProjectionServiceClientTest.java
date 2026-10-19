package com.fantasy.bff.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fantasy.bff.dto.request.GameRange;
import com.fantasy.bff.generated.projection.model.SkaterProjectionResponse;
import com.fantasy.bff.generated.projection.model.SkaterSplitResponse;
import com.fantasy.bff.generated.projection.model.SplitSeasonResponse;
import com.fantasy.bff.generated.projection.model.SplitSeasonsResponse;
import com.fantasy.bff.support.WireMockConfigs;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class HttpProjectionServiceClientTest {

    private WireMockServer server;
    private HttpProjectionServiceClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfigs.http11());
        server.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
        client = new HttpProjectionServiceClient(restClient);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void skaterProjections_leavesTheVersionOutWhenNothingIsPinned() {
        // Unpinned is the normal setting: projection-service then answers with the season's most
        // recent run, which is the version that actually has rows. Sending a version we made up
        // would be asking for rows that may not exist yet.
        server.stubFor(get(urlPathEqualTo("/api/v1/projections/skaters"))
                .withQueryParam("season", equalTo("2026"))
                .withQueryParam("model_version", absent())
                .willReturn(okJson("""
                        [{"nhl_id":8478402,"target_season":2026,"model_version":"marcel-v14","goals":51.0}]
                        """)));

        List<SkaterProjectionResponse> projections = client.skaterProjections(2026, "");

        assertThat(projections).hasSize(1);
        assertThat(projections.getFirst().getModelVersion()).isEqualTo("marcel-v14");
        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/projections/skaters"))
                .withQueryParam("model_version", absent()));
    }

    @Test
    void goalieProjections_leavesTheVersionOutWhenNothingIsPinned() {
        server.stubFor(get(urlPathEqualTo("/api/v1/projections/goalies"))
                .withQueryParam("season", equalTo("2026"))
                .withQueryParam("model_version", absent())
                .willReturn(okJson("[]")));

        assertThat(client.goalieProjections(2026, null)).isEmpty();
        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/projections/goalies"))
                .withQueryParam("model_version", absent()));
    }

    @Test
    void skaterProjections_parsesResponseAndSendsQueryParams() {
        server.stubFor(get(urlPathEqualTo("/api/v1/projections/skaters"))
                .withQueryParam("season", equalTo("2026"))
                .withQueryParam("model_version", equalTo("marcel-v1"))
                .willReturn(okJson("""
                        [{"nhl_id":8478402,"target_season":2026,"model_version":"marcel-v1",
                          "goals":48.5,"assists":90.1,"points":138.6,"shots":306.0,"shooting_pct":0.158}]
                        """)));

        List<SkaterProjectionResponse> projections = client.skaterProjections(2026, "marcel-v1");

        assertThat(projections).hasSize(1);
        SkaterProjectionResponse mcdavid = projections.getFirst();
        assertThat(mcdavid.getModelVersion()).isEqualTo("marcel-v1");
        // openapi-generator maps `type: number` (no format) to BigDecimal.
        assertThat(mcdavid.getPoints()).isEqualByComparingTo(new BigDecimal("138.6"));
        assertThat(mcdavid.getShootingPct()).isEqualByComparingTo(new BigDecimal("0.158"));
    }

    @Test
    void skaterSplits_sendsAnExplicitRangeAndParsesTheNewCounts() {
        server.stubFor(get(urlPathEqualTo("/api/v1/splits/skaters"))
                .withQueryParam("season", equalTo("2025"))
                .withQueryParam("from_game", equalTo("50"))
                .withQueryParam("to_game", equalTo("82"))
                .withQueryParam("limit", equalTo("500"))
                .willReturn(okJson("""
                        [{"nhl_id":8478402,"season":2025,"games":30,"first_team_game":50,
                          "last_team_game":82,"goals":20,"assists":30,"points":50,"plus_minus":5,
                          "pim":10,"pp_goals":6,"pp_points":18,"sh_goals":1,"sh_points":2,
                          "gw_goals":3,"shots":110,"toi_seconds":39000,"hits":24,"blocks":11,
                          "faceoffs_won":210,"faceoffs_lost":180}]
                        """)));

        List<SkaterSplitResponse> splits = client.skaterSplits(2025, new GameRange(50, 82, null), 500);

        assertThat(splits).hasSize(1);
        SkaterSplitResponse mcdavid = splits.getFirst();
        assertThat(mcdavid.getHits()).isEqualTo(24);
        assertThat(mcdavid.getBlocks()).isEqualTo(11);
        assertThat(mcdavid.getFaceoffsWon()).isEqualTo(210);
        assertThat(mcdavid.getFaceoffsLost()).isEqualTo(180);
    }

    @Test
    void skaterSplits_omitsBoundsThatWereNotSet() {
        server.stubFor(get(urlPathEqualTo("/api/v1/splits/skaters")).willReturn(okJson("[]")));

        client.skaterSplits(2025, GameRange.ofLastGames(20), 100);

        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/splits/skaters"))
                .withQueryParam("last_games", equalTo("20"))
                .withQueryParam("from_game", absent())
                .withQueryParam("to_game", absent()));
    }

    @Test
    void skaterSplits_anOpenRangeSendsNoBoundsAtAll() {
        server.stubFor(get(urlPathEqualTo("/api/v1/splits/skaters")).willReturn(okJson("[]")));

        client.skaterSplits(2025, GameRange.season(), 100);

        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/splits/skaters"))
                .withQueryParam("from_game", absent())
                .withQueryParam("to_game", absent())
                .withQueryParam("last_games", absent()));
    }

    @Test
    void skaterSplits_leavesTheSeasonOutWhenNoneIsNamed() {
        server.stubFor(get(urlPathEqualTo("/api/v1/splits/skaters")).willReturn(okJson("[]")));

        client.skaterSplits(null, GameRange.ofLastGames(5), 100);

        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/splits/skaters"))
                .withQueryParam("season", absent())
                .withQueryParam("last_games", equalTo("5")));
    }

    @Test
    void splitSeasons_asksForTheTargetAndParsesEachSeasonsOwnLength() {
        server.stubFor(get(urlPathEqualTo("/api/v1/splits/seasons"))
                .withQueryParam("target_season", equalTo("2026"))
                .willReturn(okJson("""
                        {"default_season":2025,"seasons":[
                          {"season":2026,"schedule_games":84,"games_played":0},
                          {"season":2025,"schedule_games":82,"games_played":82}]}
                        """)));

        SplitSeasonsResponse seasons = client.splitSeasons(2026);

        assertThat(seasons.getDefaultSeason()).isEqualTo(2025);
        assertThat(seasons.getSeasons())
                .extracting(SplitSeasonResponse::getScheduleGames)
                .containsExactly(84, 82);
    }

    @Test
    void restOfSeasonSkaters_asksForADayAndParsesTheKeptLine() {
        server.stubFor(get(urlPathEqualTo("/api/v1/rest-of-season/skaters"))
                .withQueryParam("season", equalTo("2026"))
                .withQueryParam("as_of", equalTo("2026-10-27"))
                .willReturn(okJson("""
                        [{"nhl_id":8478402,"target_season":2026,"model_version":"marcel-v110","team":"EDM",
                          "games_remaining":73,"games_played":69.0,"toi_per_game_seconds":1300.0,
                          "goals":30.0,"assists":60.0,"points":90.0,"plus_minus":null,"pim":null,
                          "pp_goals":null,"pp_assists":null,"pp_points":null,"sh_goals":null,
                          "sh_assists":null,"sh_points":null,"gw_goals":null,"shots":null,
                          "shooting_pct":null,"faceoffs_won":null,"faceoffs_lost":null,"hits":null,
                          "blocks":null,"shifts":null,"hat_tricks":null,
                          "served":{"games_played":70.0,"points":96.0},
                          "generated_at":"2026-10-27T04:30:00Z"}]
                        """)));

        var rows = client.restOfSeasonSkaters(2026, LocalDate.of(2026, 10, 27));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getGamesRemaining()).isEqualTo(73);
        assertThat(rows.getFirst().getServed().getPoints()).isEqualByComparingTo(new BigDecimal("96.0"));
        assertThat(rows.getFirst().getGeneratedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    void restOfSeasonGoalies_aDayNothingIsKeptForIsAnEmptyListNotAFault() {
        server.stubFor(get(urlPathEqualTo("/api/v1/rest-of-season/goalies"))
                .withQueryParam("as_of", equalTo("2026-10-01"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"No rest-of-season projection of season 2026 is kept on or before 2026-10-01\"}")));

        assertThat(client.restOfSeasonGoalies(2026, LocalDate.of(2026, 10, 1))).isEmpty();
    }

    @Test
    void restOfSeasonSkaters_leavesTheDayOutWhenNoneIsAsked() {
        server.stubFor(get(urlPathEqualTo("/api/v1/rest-of-season/skaters"))
                .willReturn(okJson("[]")));

        client.restOfSeasonSkaters(2026);

        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/rest-of-season/skaters"))
                .withQueryParam("season", equalTo("2026"))
                .withoutQueryParam("as_of"));
    }

    @Test
    void restOfSeasonSnapshots_parsesTheDaysKept() {
        server.stubFor(get(urlPathEqualTo("/api/v1/rest-of-season/snapshots"))
                .withQueryParam("season", equalTo("2026"))
                .willReturn(okJson("{\"season\":2026,\"days\":[\"2026-10-20\",\"2026-10-27\"]}")));

        var snapshots = client.restOfSeasonSnapshots(2026);

        assertThat(snapshots.getDays()).containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));
    }

    @Test
    void skaterRoleChanges_sendsTheStretchAndLeavesTheSeasonOutWhenNoneIsNamed() {
        server.stubFor(get(urlPathEqualTo("/api/v1/role-changes/skaters"))
                .willReturn(okJson("{\"season\":null,\"recent_games\":3,\"skaters\":[]}")));

        var answer = client.skaterRoleChanges(null, 3);

        assertThat(answer.getRecentGames()).isEqualTo(3);
        server.verify(getRequestedFor(urlPathEqualTo("/api/v1/role-changes/skaters"))
                .withQueryParam("recent_games", equalTo("3"))
                .withoutQueryParam("season")
                .withoutQueryParam("since"));
    }

}
