package dev.seatline.api.web;

import dev.seatline.api.account.Role;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/** The signed-in caller, read from their access token. */
public record CurrentUser(UUID id, Role role) {

    public static CurrentUser of(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(UUID.fromString(jwt.getSubject()), Role.valueOf(roles.getFirst()));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
