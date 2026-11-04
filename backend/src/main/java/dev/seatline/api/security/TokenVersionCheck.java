package dev.seatline.api.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * Lets the API take back tokens before they expire. Each token carries the user's token version
 * from when it was issued; changing a user's role (or turning off two-factor) bumps the version,
 * so every older token is refused on its next request. Costs one primary-key lookup per request.
 */
class TokenVersionCheck implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult REVOKED =
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "token revoked", null));

    private final JdbcTemplate jdbc;

    TokenVersionCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object claim = jwt.getClaim("tv");
        UUID user;
        try {
            user = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return REVOKED;
        }
        if (!(claim instanceof Number issued)) {
            return REVOKED;
        }
        List<Integer> current = jdbc.queryForList("select token_version from users where id = ?", Integer.class, user);
        return !current.isEmpty() && current.get(0) == issued.intValue() ? OAuth2TokenValidatorResult.success() : REVOKED;
    }
}
