package dev.seatline.api.account;

import dev.seatline.api.security.SeatlineSettings;
import dev.seatline.api.security.TokenService;
import dev.seatline.api.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

    private static final Duration VERIFY_TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final LoginLimiter limiter;
    private final Mailer mailer;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String publicUrl;
    // hashed once at startup so a login for an unknown email costs as much as a real one
    private final String dummyHash;

    public AccountService(UserRepository users, PasswordEncoder passwords, TokenService tokens, LoginLimiter limiter,
                          Mailer mailer, JdbcTemplate jdbc, Clock clock, SeatlineSettings settings) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.limiter = limiter;
        this.mailer = mailer;
        this.jdbc = jdbc;
        this.clock = clock;
        this.publicUrl = settings.publicUrl() == null ? "http://localhost:3000" : settings.publicUrl().replaceAll("/+$", "");
        this.dummyHash = passwords.encode("not a real password");
    }

    /** New accounts are always fans; an admin promotes organizers and door staff. */
    @Transactional
    public User register(String email, String password, String displayName) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken");
        }
        User user = users.saveAndFlush(new User(normalized, passwords.encode(password), displayName.strip(), Role.FAN));

        String token = randomToken();
        jdbc.update("insert into email_verifications (token_hash, user_id, expires_at) values (?, ?, ?)",
                sha256(token), user.id(), Timestamp.from(clock.instant().plus(VERIFY_TTL)));
        mailer.send(normalized, "Confirm your Seatline email",
                "Your confirmation code: " + token + "\n"
                        + "Or open " + publicUrl + "/verify?token=" + token + "\n"
                        + "It works for 24 hours.");
        return user;
    }

    @Transactional
    public void verifyEmail(String token) {
        var rows = jdbc.queryForList(
                "delete from email_verifications where token_hash = ? and expires_at > ? returning user_id",
                UUID.class, sha256(token), Timestamp.from(clock.instant()));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_or_expired_token");
        }
        users.findById(rows.getFirst()).ifPresent(User::markEmailVerified);
    }

    /**
     * Checks the password (and the two-factor code if it's on) and issues a token. Wrong email and
     * wrong password give the same answer, so the API doesn't reveal who has an account.
     */
    @Transactional
    public TokenService.Token login(String email, String password, String code, String address) {
        String normalized = normalize(email);
        if (!limiter.allowed(normalized, address)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }
        User user = users.findByEmail(normalized).orElse(null);
        boolean passwordOk = passwords.matches(password, user != null ? user.passwordHash() : dummyHash);
        if (user == null || !passwordOk) {
            limiter.failed(normalized, address);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials");
        }
        if (user.twoFactorEnabled()) {
            if (code == null || code.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "code_required");
            }
            long step = Totp.match(user.totpSecret(), code, clock);
            if (step < 0 || step <= user.totpLastStep()) {
                limiter.failed(normalized, address);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_code");
            }
            user.setTotpLastStep(step);
        }
        if (!user.emailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified");
        }
        limiter.succeeded(normalized);
        return tokens.issue(user);
    }

    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown_user"));
    }

    /** Hands out a new secret for the authenticator app. Two-factor stays off until {@link #enableTwoFactor}. */
    @Transactional
    public String startTwoFactor(UUID userId) {
        User user = get(userId);
        if (user.twoFactorEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "already_enabled");
        }
        String secret = Totp.newSecret();
        user.setTotpPending(secret);
        return secret;
    }

    @Transactional
    public void enableTwoFactor(UUID userId, String code) {
        User user = get(userId);
        if (user.totpPending() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "no_setup_in_progress");
        }
        long step = Totp.match(user.totpPending(), code, clock);
        if (step < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_code");
        }
        user.confirmTotp(step);
    }

    @Transactional
    public void disableTwoFactor(UUID userId, String code) {
        User user = get(userId);
        long step = user.twoFactorEnabled() ? Totp.match(user.totpSecret(), code, clock) : -1;
        if (step < 0 || step <= user.totpLastStep()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_code");
        }
        user.disableTotp();
    }

    @Transactional
    public void setRole(UUID userId, Role role) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_user"));
        user.setRole(role);
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
