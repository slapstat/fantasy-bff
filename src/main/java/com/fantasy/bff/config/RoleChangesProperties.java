package com.fantasy.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether this environment serves Role Changes. Off unless the configuration says otherwise: the
 * feature is a draft, and an environment that forgets the variable keeps it dark. The one answer its
 * endpoint enforces and {@code GET /api/v1/features} reports.
 */
@ConfigurationProperties(prefix = "role-changes")
public record RoleChangesProperties(Boolean enabled) {

    public RoleChangesProperties {
        enabled = enabled != null && enabled;
    }
}
