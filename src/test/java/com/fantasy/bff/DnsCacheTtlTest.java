package com.fantasy.bff;

import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BFF re-resolves a downstream's name within seconds, so a redeployed service is reached at
 * its new container rather than dialled at the removed one for the JVM's default 30 seconds.
 */
class DnsCacheTtlTest {

    @Test
    void startupHoldsAResolvedAddressForFiveSecondsAtMost() {
        Application.holdResolvedAddressesBriefly();

        assertThat(Integer.parseInt(Security.getProperty(Application.DNS_CACHE_TTL_PROPERTY)))
                .isBetween(0, 5);
    }
}
