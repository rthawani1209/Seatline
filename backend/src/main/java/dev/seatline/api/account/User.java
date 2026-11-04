package dev.seatline.api.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_pending")
    private String totpPending;

    @Column(name = "totp_last_step", nullable = false)
    private long totpLastStep;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {}

    public User(String email, String passwordHash, String displayName, Role role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    public void setRole(Role role) {
        if (role != this.role) {
            this.role = role;
            tokenVersion++; // tokens carry the role, so the old ones must go
        }
    }

    /** Tokens issued at an older version are refused. */
    public int tokenVersion() {
        return tokenVersion;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public String totpSecret() {
        return totpSecret;
    }

    public boolean twoFactorEnabled() {
        return totpSecret != null;
    }

    public String totpPending() {
        return totpPending;
    }

    public void setTotpPending(String secret) {
        this.totpPending = secret;
    }

    /** Turns two-factor on with the pending secret (after the user proved their app has it). */
    public void confirmTotp(long step) {
        this.totpSecret = totpPending;
        this.totpPending = null;
        this.totpLastStep = step;
    }

    public void disableTotp() {
        this.totpSecret = null;
        this.totpPending = null;
        this.totpLastStep = 0;
        tokenVersion++;
    }

    public long totpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(long step) {
        this.totpLastStep = step;
    }
}
