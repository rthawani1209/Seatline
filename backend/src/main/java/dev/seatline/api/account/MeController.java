package dev.seatline.api.account;

import dev.seatline.api.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/me")
class MeController {

    record Me(UUID id, String email, String displayName, Role role, boolean twoFactor) {}

    record TwoFactorSetup(String secret, String uri) {}

    record CodeRequest(@NotBlank String code) {}

    record EnableRequest(@NotBlank String code, @NotBlank String password) {}

    record PasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 128) String newPassword) {}

    record NewToken(String token, Instant expiresAt) {}

    private final AccountService accounts;

    MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    Me me(@AuthenticationPrincipal Jwt jwt) {
        User u = accounts.get(UUID.fromString(jwt.getSubject()));
        return new Me(u.id(), u.email(), u.displayName(), u.role(), u.twoFactorEnabled());
    }

    @PostMapping("/2fa/setup")
    TwoFactorSetup setup(@AuthenticationPrincipal Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        String secret = accounts.startTwoFactor(id);
        return new TwoFactorSetup(secret, Totp.uri(secret, accounts.get(id).email()));
    }

    @PostMapping("/2fa/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void enable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EnableRequest r) {
        accounts.enableTwoFactor(UUID.fromString(jwt.getSubject()), r.code(), r.password());
    }

    @PostMapping("/2fa/disable")
    NewToken disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r, HttpServletRequest http) {
        TokenService.Token t = accounts.disableTwoFactor(UUID.fromString(jwt.getSubject()), r.code(), http.getRemoteAddr());
        return new NewToken(t.value(), t.expiresAt());
    }

    @PostMapping("/password")
    NewToken password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordRequest r,
                      HttpServletRequest http) {
        TokenService.Token t = accounts.changePassword(UUID.fromString(jwt.getSubject()), r.currentPassword(),
                r.newPassword(), http.getRemoteAddr());
        return new NewToken(t.value(), t.expiresAt());
    }

    /** Ends every session of this account, on every device, this one included. */
    @PostMapping("/sign-out-everywhere")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOutEverywhere(@AuthenticationPrincipal Jwt jwt) {
        accounts.signOutEverywhere(UUID.fromString(jwt.getSubject()));
    }
}
