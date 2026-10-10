package vn.smartassessment.api.identity.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.smartassessment.api.config.JwtConfig;
import vn.smartassessment.api.identity.dto.AuthDtos;
import vn.smartassessment.api.identity.entity.RoleEntity;
import vn.smartassessment.api.identity.entity.UserEntity;
import vn.smartassessment.api.identity.entity.UserRoleEntity;
import vn.smartassessment.api.identity.repository.RoleRepository;
import vn.smartassessment.api.identity.repository.UserRepository;
import vn.smartassessment.api.identity.repository.UserRoleRepository;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtConfig jwtConfig;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            JwtConfig jwtConfig) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtConfig = jwtConfig;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String fullName = request.fullName().trim();
        if (fullName.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Họ tên cần có ít nhất 2 ký tự.");
        }
        validatePasswordLength(request.password());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được sử dụng.");
        }

        RoleEntity studentRole = roleRepository.findByRoleCode("STUDENT")
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Database chưa có role STUDENT. Hãy nạp dữ liệu role chuẩn trước."));
        String username = uniqueUsername(email);
        UserEntity user = userRepository.save(new UserEntity(
                username,
                email,
                passwordEncoder.encode(request.password()),
                fullName));
        userRoleRepository.save(new UserRoleEntity(user, studentRole));
        return issueToken(user, studentRole);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        validatePasswordLength(request.password());
        UserEntity user = userRepository.findWithRolesByEmail(email)
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        RoleEntity role = user.getUserRoles().stream()
                .map(UserRoleEntity::getRole)
                .findFirst()
                .orElseThrow(this::invalidCredentials);
        return issueToken(user, role);
    }

    private AuthDtos.AuthResponse issueToken(UserEntity user, RoleEntity role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtConfig.issuer())
                .subject(user.getEmail())
                .issuedAt(now)
                .expiresAt(now.plus(8, ChronoUnit.HOURS))
                .claim("uid", user.getUserId())
                .claim("fullName", user.getFullName())
                .claim("username", user.getUsername())
                .claim("role", role.getRoleCode())
                .claim("roles", List.of(role.getRoleCode()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims)).getTokenValue();
        return new AuthDtos.AuthResponse(
                token,
                "Bearer",
                new AuthDtos.UserView(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getUsername(),
                        role.getRoleCode()));
    }

    private String uniqueUsername(String email) {
        String localPart = email.substring(0, email.indexOf('@'));
        String sanitized = localPart.replaceAll("[^a-zA-Z0-9_.-]", "");
        String base = sanitized.substring(0, Math.min(sanitized.length(), 80));
        if (base.isBlank()) base = "student";
        String suffix;
        try {
            suffix = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for deterministic usernames.", exception);
        }
        for (int length = 12; length <= suffix.length(); length += 4) {
            String boundedBase = base.substring(0, Math.min(base.length(), 99 - length));
            String candidate = boundedBase + "-" + suffix.substring(0, length);
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Không thể tạo username duy nhất cho email này.");
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không chính xác.");
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }
    }
}
