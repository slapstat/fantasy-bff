package com.fantasy.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fantasy.bff.client.ProjectionServiceClient;
import com.fantasy.bff.config.RoleChangesProperties;
import com.fantasy.bff.dto.response.PlayerRoleChangeResponse;
import com.fantasy.bff.dto.response.RoleChangeListResponse;
import com.fantasy.bff.generated.projection.model.LineupListingResponse;
import com.fantasy.bff.generated.projection.model.PlayerResponse;
import com.fantasy.bff.generated.projection.model.RoleChangesResponse;
import com.fantasy.bff.generated.projection.model.RoleWindowResponse;
import com.fantasy.bff.generated.projection.model.SkaterRoleChangeResponse;
import com.fantasy.bff.service.mapping.PlayerIdMapping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleChangeServiceTest {

    private static final int NHL_ID = 8481000;
    private static final int UNKNOWN_NHL_ID = 8489999;
    private static final int PLATFORM_ID = 77;

    @Mock private ProjectionServiceClient projectionServiceClient;
    @Mock private PlayerSplitContextProvider contextProvider;

    private RoleChangeService service;

    @BeforeEach
    void setUp() {
        service = new RoleChangeService(
                projectionServiceClient, contextProvider, new RoleChangesAvailability(new RoleChangesProperties(true)));
        PlayerResponse identity = new PlayerResponse();
        identity.setNhlId(NHL_ID);
        identity.setFullName("Vasily Podkolzin");
        when(contextProvider.context()).thenReturn(new PlayerSplitContextProvider.Context(
                new PlayerIdMapping(Map.of((long) NHL_ID, PLATFORM_ID), List.of(), 1, 0, 0),
                Map.of((long) NHL_ID, identity),
                Set.of(),
                Optional.empty(),
                List.of(),
                Map.of(PLATFORM_ID, "EDM")));
    }

    private static SkaterRoleChangeResponse skater(int nhlId) {
        return new SkaterRoleChangeResponse()
                .nhlId(nhlId)
                .team("EDM")
                .recent(new RoleWindowResponse()
                        .games(4)
                        .toiPerGame(new BigDecimal("1080"))
                        .ppToiPerGame(new BigDecimal("240"))
                        .ppShare(new BigDecimal("0.85")))
                .baseline(new RoleWindowResponse().games(70).toiPerGame(new BigDecimal("720")))
                .baselineSource(SkaterRoleChangeResponse.BaselineSourceEnum.LAST_SEASON)
                .firstRecentGameDate(LocalDate.of(2026, 9, 29))
                .lastRecentGameDate(LocalDate.of(2026, 10, 4))
                .listedNow(new LineupListingResponse()
                        .seenOn(LocalDate.of(2026, 10, 4))
                        .line("f2")
                        .powerPlayUnit(1)
                        .outOfLineup(false));
    }

    @Test
    void joinsEachMeasuredSkaterToThePoolsPlayerAndLeavesOutWhoItCannotPlace() {
        when(projectionServiceClient.skaterRoleChanges(null, 5)).thenReturn(new RoleChangesResponse()
                .season(2026)
                .recentGames(5)
                .skaters(List.of(skater(NHL_ID), skater(UNKNOWN_NHL_ID))));

        RoleChangeListResponse answer = service.roleChanges(null, 5);

        assertThat(answer.season()).isEqualTo(2026);
        assertThat(answer.players()).hasSize(1);
        PlayerRoleChangeResponse podkolzin = answer.players().getFirst();
        assertThat(podkolzin.playerId()).isEqualTo(PLATFORM_ID);
        assertThat(podkolzin.name()).isEqualTo("Vasily Podkolzin");
        assertThat(podkolzin.teamAbbrev()).isEqualTo("EDM");
        assertThat(podkolzin.defence()).isFalse();
        assertThat(podkolzin.recent().toiPerGame()).isEqualTo(1080.0);
        assertThat(podkolzin.recent().ppShare()).isEqualTo(0.85);
        assertThat(podkolzin.baseline().ppShare()).isNull();
        assertThat(podkolzin.baselineSource()).isEqualTo("LAST_SEASON");
        assertThat(podkolzin.listedNow().powerPlayUnit()).isEqualTo(1);
        assertThat(podkolzin.listedBefore()).isNull();
    }

    @Test
    void offRefusesWithoutAskingTheModel() {
        RoleChangeService off = new RoleChangeService(
                projectionServiceClient, contextProvider, new RoleChangesAvailability(new RoleChangesProperties(null)));

        assertThatThrownBy(() -> off.roleChanges(null, 5)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(projectionServiceClient, contextProvider);
    }
}
