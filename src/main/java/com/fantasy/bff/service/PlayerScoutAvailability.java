package com.fantasy.bff.service;

import com.fantasy.bff.config.PlayerScoutProperties;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * Whether this environment serves Player Scout: its own switch, and the AI projection's, since
 * every line it shows is the model's. The one answer its endpoints enforce and
 * {@code GET /api/v1/features} reports.
 */
@Component
public class PlayerScoutAvailability {

    private final boolean available;
    private final boolean positionAdjusted;
    private final boolean trades;

    public PlayerScoutAvailability(PlayerScoutProperties properties, AiProjectionAvailability aiProjection) {
        this.available = properties.enabled() && aiProjection.available();
        this.positionAdjusted = available && properties.positionAdjustedEnabled();
        this.trades = available && properties.tradesEnabled();
    }

    public boolean available() {
        return available;
    }

    /**
     * Whether the page may offer its Position-adjusted ranking: its own switch, on a served page. The
     * ranking is computed in the browser from the rows the page already has, so nothing here
     * enforces it; it is what {@code GET /api/v1/features} reports.
     */
    public boolean positionAdjustedAvailable() {
        return positionAdjusted;
    }

    /**
     * Whether the page's lineup column may price players other teams hold as a trade: its own
     * switch, on a served page. Priced in the browser from the rows the page already has, so, like
     * the ranking above, nothing here enforces it.
     */
    public boolean tradesAvailable() {
        return trades;
    }

    /** Refuses with a 404, so an environment without Player Scout has none of its routes. */
    public void require() {
        if (!available) {
            throw new NoSuchElementException("Player Scout is not available");
        }
    }
}
