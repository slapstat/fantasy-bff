package com.fantasy.bff.service;

import com.fantasy.bff.config.RoleChangesProperties;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/** Whether this environment serves Role Changes: the one answer its endpoint enforces and
 * {@code GET /api/v1/features} reports. */
@Component
public class RoleChangesAvailability {

    private final boolean available;

    public RoleChangesAvailability(RoleChangesProperties properties) {
        this.available = properties.enabled();
    }

    public boolean available() {
        return available;
    }

    /** Refuses with a 404, so an environment without the feature has no route for it at all. */
    public void require() {
        if (!available) {
            throw new NoSuchElementException("Role changes are not available");
        }
    }
}
