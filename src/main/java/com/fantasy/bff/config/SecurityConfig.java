package com.fantasy.bff.config;

import com.fantasy.bff.security.JwtAuthenticationFilter;
import com.fantasy.bff.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Duration PREFLIGHT_CACHE = Duration.ofMinutes(30);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final SecurityProperties securityProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitFilter rateLimitFilter,
                          SecurityProperties securityProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.securityProperties = securityProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth
                            // The container's error dispatch renders an error the request already
                            // got; it carries no token, so judging it again turned every one into a
                            // 401. A request made to /error directly is still a REQUEST and denied.
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(securityProperties.permittedUrls().toArray(String[]::new)).permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/players/skaters").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/players/goalies").permitAll()
                            // An <img> carries no Authorization header, so the headshots have to
                            // open for anyone the player lists already open for.
                            .requestMatchers(HttpMethod.GET, "/api/v1/players/*/headshot").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/players/rookies").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/players/injuries").permitAll()
                            // A share link has to open for someone who has never signed in — that is
                            // the whole point of it. GET only: publishing and taking a link down stay
                            // with the owner under /api/v1/projections/{id}/share.
                            .requestMatchers(HttpMethod.GET, "/api/v1/shared/**").permitAll()
                            // The Premium page describes the AI projection to signed-out visitors,
                            // so what an environment serves has to be readable before sign-in.
                            .requestMatchers(HttpMethod.GET, "/api/v1/features").permitAll()
                            .requestMatchers("/api/v1/account", "/api/v1/account/**").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/feedback").authenticated()
                            .requestMatchers("/api/v1/projections", "/api/v1/projections/**").authenticated()
                            .requestMatchers("/api/v1/projection-model", "/api/v1/projection-model/**")
                                    .authenticated()
                            .requestMatchers("/api/v1/yahoo", "/api/v1/yahoo/**").authenticated()
                            .requestMatchers("/api/v1/espn", "/api/v1/espn/**").authenticated()
                            .requestMatchers("/api/v1/streamer-planner/**").authenticated()
                            .requestMatchers("/api/v1/player-scout/**").authenticated()
                            .requestMatchers("/api/v1/start-bench/**").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/role-changes").authenticated()
                            .requestMatchers("/api/v1/draft-analysis/**").authenticated()
                            .requestMatchers("/api/v1/league-summaries/**").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/leagues/rostered-players").authenticated()
                            .requestMatchers("/api/v1/admin", "/api/v1/admin/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/v1/billing/webhook").permitAll()
                            .requestMatchers("/api/v1/billing/mock/**").permitAll()
                            .requestMatchers("/api/v1/billing", "/api/v1/billing/**").authenticated()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((_, response, _) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * An account store with no accounts in it. Without any {@link UserDetailsService} bean, Spring
     * Boot creates an in-memory {@code user} with a generated password and prints that password in
     * the startup log. Identity here is the JWT and neither form nor basic login is configured, so
     * that user could not be used today; this makes sure it does not exist at all, even if a login
     * mechanism were ever switched on by mistake.
     */
    @Bean
    public UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(securityProperties.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        // Without this Spring sends no Access-Control-Max-Age at all, and the browser falls back
        // to its own default of a few seconds — so every authenticated call pays for a preflight
        // of its own, and a page that varies a query parameter (Who's hot moving its game range)
        // doubles its request count for nothing. The cache is keyed per URL, so this only ever
        // saves the second ask for a URL already cleared; half an hour is what Spring itself uses
        // for its permit-default configuration.
        config.setMaxAge(PREFLIGHT_CACHE);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
