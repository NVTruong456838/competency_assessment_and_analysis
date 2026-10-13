package vn.smartassessment.api.identity.service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
        String email = request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT);
        String fullName = request.fullName().trim();
        String phoneNumber = null;
        if (fullName.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Họ tên cần có ít nhất 2 ký tự.");
        }
        if (email != null && email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email không được để trống.");
        }
        if (email == null && (request.phoneNumber() == null || request.phoneNumber().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hãy đăng ký bằng email hoặc số điện thoại.");
        }
        if (request.phoneNumber() != null) {
            try {
                phoneNumber = normalizePhoneNumber(request.phoneNumber());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Số điện thoại phải gồm 8 đến 15 chữ số, có thể bắt đầu bằng dấu +.");
            }
        }
        validatePasswordLength(request.password());
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được sử dụng.");
        }
        if (phoneNumber != null && userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Số điện thoại này đã được sử dụng.");
        }

        RoleEntity studentRole = roleRepository.findByRoleCode("STUDENT")
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Database chưa có role STUDENT. Hãy nạp dữ liệu role chuẩn trước."));
        UserEntity user = userRepository.save(new UserEntity(
                uniqueUsername(email != null ? email : phoneNumber),
                email,
                phoneNumber,
                passwordEncoder.encode(request.password()),
                fullName));
        userRoleRepository.save(new UserRoleEntity(user, studentRole));
        return issueToken(user, List.of(studentRole));
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String identifier = request.emailOrPhone().trim();
        validatePasswordLength(request.password());
        Optional<UserEntity> candidate;
        if (identifier.contains("@")) {
            candidate = userRepository.findWithRolesByEmailIgnoreCase(identifier);
        } else {
            try {
                candidate = userRepository.findWithRolesByPhoneNumber(normalizePhoneNumber(identifier));
            } catch (IllegalArgumentException exception) {
                throw invalidCredentials();
            }
        }
        UserEntity user = candidate
                .filter(userCandidate -> "ACTIVE".equals(userCandidate.getStatus()))
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<RoleEntity> roles = user.getUserRoles().stream()
                .map(UserRoleEntity::getRole)
                .sorted(Comparator.comparing(RoleEntity::getRoleCode))
                .toList();
        if (roles.isEmpty()) {
            throw invalidCredentials();
        }
        return issueToken(user, roles);
    }

    private AuthDtos.AuthResponse issueToken(UserEntity user, List<RoleEntity> roles) {
        List<String> roleCodes = roles.stream()
                .map(RoleEntity::getRoleCode)
                .distinct()
                .toList();
        if (roleCodes.isEmpty()) {
            throw invalidCredentials();
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtConfig.issuer())
                .subject(user.getEmail() != null ? user.getEmail() : user.getPhoneNumber())
                .issuedAt(now)
                .expiresAt(now.plusMillis(jwtConfig.expirationMs()))
                .claim("uid", user.getUserId())
                .claim("fullName", user.getFullName())
                .claim("username", user.getUsername())
                .claim("role", roleCodes.get(0))
                .claim("roles", roleCodes)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims)).getTokenValue();
        return new AuthDtos.AuthResponse(
                token,
                "Bearer",
                jwtConfig.expirationMs() / 1000,
                userView(user, roleCodes));
    }

    static AuthDtos.UserView userView(UserEntity user, List<String> roleCodes) {
        return new AuthDtos.UserView(
                user.getUserId(),
                user.getUserId(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getFullName(),
                user.getUsername(),
                roleCodes.get(0),
                user.getStatus(),
                roleCodes);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Email/số điện thoại hoặc mật khẩu không chính xác, hoặc tài khoản không được phép đăng nhập.");
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        String normalized = phoneNumber.trim().replaceAll("[\\s().-]", "");
        if (!normalized.matches("\\+?[0-9]{8,15}")) {
            throw new IllegalArgumentException("Invalid phone number.");
        }
        return normalized;
    }

    private String uniqueUsername(String identifier) {
        String localPart = identifier.contains("@")
                ? identifier.substring(0, identifier.indexOf('@'))
                : identifier.replaceAll("[^a-zA-Z0-9_.-]", "");
        String sanitized = localPart.replaceAll("[^a-zA-Z0-9_.-]", "");
        String base = sanitized.substring(0, Math.min(sanitized.length(), 80));
        if (base.isBlank()) base = "student";
        String suffix;
        try {
            suffix = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for deterministic usernames.", exception);
        }
        for (int length = 12; length <= suffix.length(); length += 4) {
            String boundedBase = base.substring(0, Math.min(base.length(), 99 - length));
            String candidate = boundedBase + "-" + suffix.substring(0, length);
            if (!userRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Không thể tạo username duy nhất cho thông tin đăng ký này.");
    }
}
