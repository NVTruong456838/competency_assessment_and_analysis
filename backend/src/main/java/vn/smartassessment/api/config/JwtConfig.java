package vn.smartassessment.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    private final String issuer;
    private final long expirationMs;

    public JwtConfig(
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.expiration-ms:86400000}") long expirationMs) {
        this.issuer = issuer;
        this.expirationMs = expirationMs;
    }

    public String issuer() {
        return issuer;
    }

    public long expirationMs() {
        return expirationMs;
    }
}
