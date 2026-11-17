package com.fantasy.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether this environment serves Player Scout, the page that says how the model's view of every
 * player in a league has moved since a chosen day, and what the ice says about why. Off unless the
 * configuration says otherwise: the feature is new, and an environment that forgets the variable
 * keeps it dark. {@link com.fantasy.bff.service.PlayerScoutAvailability} combines it with the AI
 * projection's own switch.
 *
 * <p>{@code positionAdjustedEnabled} switches the page's Position-adjusted ranking on its own, off
 * unless set, so the ranking can stay dark while the rest of the page is served.
 *
 * <p>{@code tradesEnabled} lets the page's lineup column price players other teams hold as a
 * one-for-one trade, both sides of it, off unless set, the same way.
 */
@ConfigurationProperties(prefix = "player-scout")
public record PlayerScoutProperties(
        Boolean enabled, Boolean positionAdjustedEnabled, Boolean tradesEnabled) {

    public PlayerScoutProperties {
        enabled = enabled != null && enabled;
        positionAdjustedEnabled = positionAdjustedEnabled != null && positionAdjustedEnabled;
        tradesEnabled = tradesEnabled != null && tradesEnabled;
    }
}
