package dev.seatline.api.security;

import dev.seatline.api.account.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Issues the signed access tokens clients send as {@code Authorization: Bearer ...}. */
@Service
public class TokenService {

    public record Token(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final SeatlineSettings settings;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, SeatlineSettings settings, Clock clock) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
    }

    public Token issue(User user) {
        Instant now = clock.instant();
        Instant expires = now.plus(settings.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("seatline")
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim("roles", List.of(user.role().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new Token(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expires);
    }
}
