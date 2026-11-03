package dev.seatline.api.account;

import dev.seatline.api.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
class AuthController {

    record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(min = 12, max = 128) String password,
                           @NotBlank @Size(max = 60) String displayName) {}

    record RegisterResponse(UUID id) {}

    record VerifyRequest(@NotBlank @Size(max = 100) String token) {}

    record LoginRequest(@NotBlank String email, @NotBlank String password, String code) {}

    record LoginResponse(String token, Instant expiresAt) {}

    private final AccountService accounts;

    AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest r) {
        return new RegisterResponse(accounts.register(r.email(), r.password(), r.displayName()).id());
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@Valid @RequestBody VerifyRequest r) {
        accounts.verifyEmail(r.token());
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest http) {
        TokenService.Token t = accounts.login(r.email(), r.password(), r.code(), http.getRemoteAddr());
        return new LoginResponse(t.value(), t.expiresAt());
    }
}
