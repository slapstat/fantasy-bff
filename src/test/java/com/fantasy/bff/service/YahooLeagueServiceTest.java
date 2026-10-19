package com.fantasy.bff.service;

import com.fantasy.bff.client.YahooServiceClient;
import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.generated.yahoo.model.LeagueSettingsResponse;
import com.fantasy.bff.generated.yahoo.model.LeagueSummary;
import com.fantasy.bff.generated.yahoo.model.LeaguesResponse;
import com.fantasy.bff.mapper.YahooLeagueSettingsMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YahooLeagueServiceTest {

    private static final String USER_ID = "user-1";
    private static final String LEAGUE_KEY = "465.l.18249";

    private final YahooServiceClient client = mock(YahooServiceClient.class);
    private final YahooLeagueSettingsMapper mapper = mock(YahooLeagueSettingsMapper.class);
    private final YahooLeagueService service = new YahooLeagueService(client, mapper);

    private final LeagueSettingsResponse settings = new LeagueSettingsResponse()
            .leagueKey(LEAGUE_KEY)
            .name("Test League")
            .scoringType("headpoint")
            .statCategories(List.of())
            .rosterPositions(List.of());

    private final LeagueProjectionSettingsResponse mapped = new LeagueProjectionSettingsResponse(
            ScoringBasis.POINTS, List.of("goals"), List.of("gp"), Map.of("goals", 4.5), null,
            new RosterSlots(2, 2, 2, 0, 0, 4, 2, 4, 2), 14, List.of(), List.of(), Map.of(), 4);

    @Test
    void resolvesNumTeamsFromTheLeagueListAndDelegatesToTheMapper() {
        when(client.settings(USER_ID, LEAGUE_KEY)).thenReturn(settings);
        when(client.leagues(USER_ID)).thenReturn(new LeaguesResponse().leagues(List.of(
                new LeagueSummary().leagueKey(LEAGUE_KEY).name("Test League").numTeams(14),
                new LeagueSummary().leagueKey("other").name("Other").numTeams(8))));
        when(mapper.toProjectionSettings(settings, 14)).thenReturn(mapped);

        assertThat(service.projectionSettings(USER_ID, LEAGUE_KEY)).isSameAs(mapped);
        verify(mapper).toProjectionSettings(settings, 14);
    }

    @Test
    void passesNullNumTeamsWhenTheLeagueIsNotInTheList() {
        when(client.settings(USER_ID, LEAGUE_KEY)).thenReturn(settings);
        when(client.leagues(USER_ID)).thenReturn(new LeaguesResponse().leagues(List.of(
                new LeagueSummary().leagueKey("other").name("Other").numTeams(8))));
        when(mapper.toProjectionSettings(settings, null)).thenReturn(mapped);

        assertThat(service.projectionSettings(USER_ID, LEAGUE_KEY)).isSameAs(mapped);
        verify(mapper).toProjectionSettings(settings, null);
    }

    @Test
    void passesNullNumTeamsWhenTheMatchedLeagueHasNoTeamCount() {
        when(client.settings(USER_ID, LEAGUE_KEY)).thenReturn(settings);
        when(client.leagues(USER_ID)).thenReturn(new LeaguesResponse().leagues(List.of(
                new LeagueSummary().leagueKey(LEAGUE_KEY).name("Test League"))));
        when(mapper.toProjectionSettings(settings, null)).thenReturn(mapped);

        assertThat(service.projectionSettings(USER_ID, LEAGUE_KEY)).isSameAs(mapped);
        verify(mapper).toProjectionSettings(settings, null);
    }
}
