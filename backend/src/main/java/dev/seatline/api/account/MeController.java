package dev.seatline.api.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/me")
class MeController {

    record Me(UUID id, String email, String displayName, Role role, boolean twoFactor) {}

    record TwoFactorSetup(String secret, String uri) {}

    record CodeRequest(@NotBlank String code) {}

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
    void enable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r) {
        accounts.enableTwoFactor(UUID.fromString(jwt.getSubject()), r.code());
    }

    @PostMapping("/2fa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r) {
        accounts.disableTwoFactor(UUID.fromString(jwt.getSubject()), r.code());
    }
}
