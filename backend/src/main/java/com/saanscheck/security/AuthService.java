package com.saanscheck.security;

import com.saanscheck.common.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties props;
    private final Clock clock;
    private final LoginAttemptLimiter limiter;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtEncoder jwtEncoder,
                       SecurityProperties props, Clock clock, LoginAttemptLimiter limiter) {
        this.limiter = limiter;
        this.users = users;
        this.encoder = encoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse register(String email, String password) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new ConflictException("Email already registered");
        }
        String role = props.isAdminEmail(normalized) ? "ADMIN" : "USER";
        UserEntity user = users.save(new UserEntity(normalized, encoder.encode(password), role));
        return issue(user);
    }

    /** Same error for unknown email and wrong password, so the endpoint does not reveal which emails exist. */
    @Transactional
    public TokenResponse login(String email, String password) {
        String key = normalize(email);
        if (limiter.isBlocked(key)) {
            throw new LoginAttemptLimiter.TooManyAttemptsException();
        }
        UserEntity user = users.findByEmail(key)
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            limiter.recordFailure(key);
            throw new BadCredentialsException("bad credentials");
        }
        limiter.reset(key);
        user.markLogin(clock.instant());            // for the admin's "last login"; nothing else is recorded
        return issue(user);
    }

    private TokenResponse issue(UserEntity user) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.jwt().ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(exp)
                .claim("roles", List.of(user.getRole()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", exp, user.getRole());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, String role) {
    }
}
