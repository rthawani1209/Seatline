package dev.seatline.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param jwtSecret base64 key for signing access tokens (at least 32 bytes). Empty in development,
 *     which makes the API generate one at startup, so tokens stop working after a restart.
 */
@ConfigurationProperties("seatline")
public record SeatlineSettings(String jwtSecret, Duration tokenTtl) {}
