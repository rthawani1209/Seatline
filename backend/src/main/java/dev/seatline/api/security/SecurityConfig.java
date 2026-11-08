package dev.seatline.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Who may call what. Every route is listed here with the roles allowed; anything not listed is
 * refused, so a new endpoint is closed until someone decides who can use it. RouteAccessTest
 * checks every route against every role.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                // bearer tokens, not cookies, so there's nothing for a cross-site request to ride on
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/verify", "/auth/login",
                                "/auth/forgot", "/auth/reset").permitAll()
                        .requestMatchers(HttpMethod.GET, "/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/me/2fa/setup", "/me/2fa/enable", "/me/2fa/disable",
                                "/me/password", "/me/sign-out-everywhere").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/admin/users/*/role").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/admin/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/admin/ledger").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/admin/ledger/*/crash", "/admin/ledger/*/revive",
                                "/admin/ledger/*/cut-off", "/admin/ledger/heal").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/venues/*", "/events", "/events/*", "/events/*/seats", "/events/*/best-seats").permitAll()
                        .requestMatchers(HttpMethod.POST, "/venues", "/events").hasAnyRole("ORGANIZER", "ADMIN")
                        // ownership of the event is checked in EventService
                        .requestMatchers(HttpMethod.POST, "/events/*/publish", "/events/*/promo-codes", "/events/*/cancel")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/organizer/events", "/events/*/stats", "/events/*/promo-codes")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/events/*/quote", "/events/*/holds", "/holds/*/checkout",
                                "/tickets/*/transfer", "/tickets/*/return").authenticated()
                        .requestMatchers(HttpMethod.GET, "/holds/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/holds/*").authenticated()
                        .requestMatchers("/events/*/queue").authenticated()
                        .requestMatchers(HttpMethod.GET, "/me/tickets", "/me/tickets/*/qr").authenticated()
                        .requestMatchers(HttpMethod.POST, "/scan").hasAnyRole("DOOR_STAFF", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/door/events").hasAnyRole("DOOR_STAFF", "ADMIN")
                        // ownership of the event is checked in DoorService
                        .requestMatchers(HttpMethod.GET, "/events/*/door-staff").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/events/*/door-staff").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/events/*/door-staff/*").hasAnyRole("ORGANIZER", "ADMIN")
                        // Spring's error page, so a 500 reaches the client as a 500 and not a 403
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromToken()))
                        .authenticationEntryPoint(SecurityConfig::tokenProblem))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * A bad or revoked token is a 401. But if the token couldn't be checked at all (the database was
     * too busy to look up its version), that's the server's problem: 503, so the app says "try again"
     * instead of signing the fan out in the middle of a sale.
     */
    private static void tokenProblem(jakarta.servlet.http.HttpServletRequest req,
                                     jakarta.servlet.http.HttpServletResponse res,
                                     org.springframework.security.core.AuthenticationException e) throws java.io.IOException {
        boolean busy = e instanceof org.springframework.security.authentication.AuthenticationServiceException;
        res.setStatus(busy ? HttpStatus.SERVICE_UNAVAILABLE.value() : HttpStatus.UNAUTHORIZED.value());
        res.setContentType("application/json");
        res.getWriter().write(busy ? "{\"error\":\"try_again\"}" : "{\"error\":\"invalid_token\"}");
    }

    private static JwtAuthenticationConverter rolesFromToken() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    SecretKey jwtKey(SeatlineSettings settings, @Value("${seatline.production:false}") boolean production) {
        byte[] bytes;
        if (settings.jwtSecret() == null || settings.jwtSecret().isBlank()) {
            if (production) {
                // a random key would sign everyone out and change every ticket's QR key on each restart
                throw new IllegalStateException("SEATLINE_JWT_SECRET is required in production");
            }
            log.warn("SEATLINE_JWT_SECRET is not set; using a random key, so tokens won't survive a restart");
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = Base64.getDecoder().decode(settings.jwtSecret());
            if (bytes.length < 32) {
                throw new IllegalStateException("SEATLINE_JWT_SECRET must be at least 32 bytes");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, JdbcTemplate jdbc) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new TokenVersionCheck(jdbc)));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
