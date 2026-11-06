package dev.seatline.api.security;

import dev.seatline.ledger.server.Tls;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param jwtSecret base64 key for signing access tokens (at least 32 bytes). Empty in development,
 *     which makes the API generate one at startup, so tokens stop working after a restart.
 * @param ledger the seat ledger's client addresses, {@code 1=host:port,2=host:port,...}
 * @param ledgerTimeout how long one ledger call keeps retrying (through leader changes) before
 *     the API gives up and answers 503
 * @param ledgerTls the API's certificate for mutual TLS with the ledger (cert, key, ca), or unset
 *     for plain text on a local machine
 */
@ConfigurationProperties("seatline")
public record SeatlineSettings(String jwtSecret, Duration tokenTtl, String ledger, Duration ledgerTimeout,
                               Tls ledgerTls) {}
