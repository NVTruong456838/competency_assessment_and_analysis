package vn.smartassessment.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    private final String issuer;

    public JwtConfig(@Value("${app.jwt.issuer}") String issuer) {
        this.issuer = issuer;
    }

    public String issuer() {
        return issuer;
    }
}
