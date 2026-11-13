package com.fantasy.bff.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fantasy.bff.config.AiProjectionProperties;
import com.fantasy.bff.config.PlayerScoutProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerScoutAvailabilityTest {

    @ParameterizedTest(name = "player-scout={0}, position-adjusted={1}, ai-projection={2} -> {3}, {4}")
    @CsvSource({
        "true,  true,  true,  true,  true",
        "true,  false, true,  true,  false",
        "false, true,  true,  false, false",
        "true,  true,  false, false, false"
    })
    void positionAdjustedNeedsItsOwnSwitchOnAServedPage(
            boolean scout, boolean positionAdjusted, boolean aiProjection,
            boolean expectedScout, boolean expectedPositionAdjusted) {
        PlayerScoutAvailability availability = new PlayerScoutAvailability(
                new PlayerScoutProperties(scout, positionAdjusted),
                new AiProjectionAvailability(new AiProjectionProperties(aiProjection)));

        assertThat(availability.available()).isEqualTo(expectedScout);
        assertThat(availability.positionAdjustedAvailable()).isEqualTo(expectedPositionAdjusted);
    }

    @Test
    void anUnsetSwitchIsOff() {
        PlayerScoutProperties properties = new PlayerScoutProperties(null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.positionAdjustedEnabled()).isFalse();
    }
}
