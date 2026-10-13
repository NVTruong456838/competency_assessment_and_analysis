package vn.smartassessment.api.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.config.JwtConfig;
import vn.smartassessment.api.identity.dto.AuthDtos;
import vn.smartassessment.api.identity.entity.RoleEntity;
import vn.smartassessment.api.identity.entity.UserEntity;
import vn.smartassessment.api.identity.entity.UserRoleEntity;
import vn.smartassessment.api.identity.repository.RoleRepository;
import vn.smartassessment.api.identity.repository.UserRepository;
import vn.smartassessment.api.identity.repository.UserRoleRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtEncoder jwtEncoder;

    @Captor
    private ArgumentCaptor<UserEntity> userCaptor;

    @Captor
    private ArgumentCaptor<UserRoleEntity> userRoleCaptor;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                roleRepository,
                userRoleRepository,
                passwordEncoder,
                jwtEncoder,
                new JwtConfig("https://smart-assessment.local", 86_400_000));
    }

    @Test
    void registerCreatesStudentWithHashedPasswordAndToken() {
        RoleEntity studentRole = org.mockito.Mockito.mock(RoleEntity.class);
        UserEntity savedUser = org.mockito.Mockito.mock(UserEntity.class);
        when(studentRole.getRoleId()).thenReturn(2L);
        when(studentRole.getRoleCode()).thenReturn("STUDENT");
        when(savedUser.getUserId()).thenReturn(41L);
        when(savedUser.getEmail()).thenReturn("student@example.com");
        when(savedUser.getFullName()).thenReturn("Student Name");
        when(savedUser.getUsername()).thenReturn("student-abc");
        when(userRepository.existsByEmailIgnoreCase("student@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(false);
        when(roleRepository.findByRoleCode("STUDENT")).thenReturn(Optional.of(studentRole));
        when(userRepository.existsByUsernameIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode("strongpass")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("signed-token")
                        .header("alg", "HS256")
                        .claim("sub", "student@example.com")
                        .build());

        AuthDtos.AuthResponse response = authService.register(
                new AuthDtos.RegisterRequest(
                        " Student Name ", "Student@Example.com", "+84 912 345 678", "strongpass"));

        assertEquals("signed-token", response.accessToken());
        assertEquals("STUDENT", response.user().role());
        assertEquals(List.of("STUDENT"), response.user().roles());
        assertEquals(86_400L, response.expiresIn());
        verify(userRepository).save(userCaptor.capture());
        assertEquals("student@example.com", userCaptor.getValue().getEmail());
        assertEquals("+84912345678", userCaptor.getValue().getPhoneNumber());
        assertEquals("bcrypt-hash", userCaptor.getValue().getPasswordHash());
        verify(userRoleRepository).save(userRoleCaptor.capture());
        assertEquals("STUDENT", userRoleCaptor.getValue().getRole().getRoleCode());
    }

    @Test
    void registerRejectsDuplicateEmailBeforeWriting() {
        when(userRepository.existsByEmailIgnoreCase("student@example.com")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest(
                                "Student Name", "Student@Example.com", "+84912345678", "strongpass")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void registerRejectsDuplicatePhoneBeforeWriting() {
        when(userRepository.existsByEmailIgnoreCase("student@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest(
                                "Student Name", "student@example.com", "+84 912 345 678", "strongpass")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void registerRejectsPasswordsLongerThanBcryptByteLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest(
                                "Student Name", "student@example.com", "+84912345678", "ắ".repeat(25))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
    }

    @Test
    void registerAllowsEmailWithoutPhoneNumber() {
        RoleEntity studentRole = org.mockito.Mockito.mock(RoleEntity.class);
        UserEntity savedUser = org.mockito.Mockito.mock(UserEntity.class);
        when(studentRole.getRoleCode()).thenReturn("STUDENT");
        when(savedUser.getUserId()).thenReturn(42L);
        when(savedUser.getEmail()).thenReturn("student@example.com");
        when(savedUser.getFullName()).thenReturn("Student Name");
        when(savedUser.getUsername()).thenReturn("student-abc");
        when(userRepository.existsByEmailIgnoreCase("student@example.com")).thenReturn(false);
        when(roleRepository.findByRoleCode("STUDENT")).thenReturn(Optional.of(studentRole));
        when(userRepository.existsByUsernameIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode("strongpass")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("signed-token")
                        .header("alg", "HS256")
                        .claim("sub", "student@example.com")
                        .build());

        authService.register(new AuthDtos.RegisterRequest(
                "Student Name", "student@example.com", null, "strongpass"));

        verify(userRepository).save(userCaptor.capture());
        assertEquals("student@example.com", userCaptor.getValue().getEmail());
        assertNull(userCaptor.getValue().getPhoneNumber());
    }

    @Test
    void registerAllowsPhoneNumberWithoutEmail() {
        RoleEntity studentRole = org.mockito.Mockito.mock(RoleEntity.class);
        UserEntity savedUser = org.mockito.Mockito.mock(UserEntity.class);
        when(studentRole.getRoleCode()).thenReturn("STUDENT");
        when(savedUser.getUserId()).thenReturn(43L);
        when(savedUser.getEmail()).thenReturn(null);
        when(savedUser.getPhoneNumber()).thenReturn("+84912345678");
        when(savedUser.getFullName()).thenReturn("Student Name");
        when(savedUser.getUsername()).thenReturn("student-abc");
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(false);
        when(roleRepository.findByRoleCode("STUDENT")).thenReturn(Optional.of(studentRole));
        when(userRepository.existsByUsernameIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode("strongpass")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("signed-token")
                        .header("alg", "HS256")
                        .claim("sub", "+84912345678")
                        .build());

        authService.register(new AuthDtos.RegisterRequest(
                "Student Name", null, "+84 912 345 678", "strongpass"));

        verify(userRepository).save(userCaptor.capture());
        assertNull(userCaptor.getValue().getEmail());
        assertEquals("+84912345678", userCaptor.getValue().getPhoneNumber());
    }

    @Test
    void registerRejectsMissingContactMethod() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest("Student Name", null, null, "strongpass")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void loginFindsPhoneOnlyUserUsingNormalizedPhoneNumber() {
        RoleEntity studentRole = org.mockito.Mockito.mock(RoleEntity.class);
        UserRoleEntity userRole = org.mockito.Mockito.mock(UserRoleEntity.class);
        UserEntity user = org.mockito.Mockito.mock(UserEntity.class);
        when(studentRole.getRoleCode()).thenReturn("STUDENT");
        when(userRole.getRole()).thenReturn(studentRole);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getPasswordHash()).thenReturn("bcrypt-hash");
        when(user.getUserRoles()).thenReturn(Set.of(userRole));
        when(user.getUserId()).thenReturn(44L);
        when(user.getEmail()).thenReturn(null);
        when(user.getPhoneNumber()).thenReturn("+84912345678");
        when(user.getFullName()).thenReturn("Student Name");
        when(user.getUsername()).thenReturn("student-abc");
        when(userRepository.findWithRolesByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("strongpass", "bcrypt-hash")).thenReturn(true);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("signed-token")
                        .header("alg", "HS256")
                        .claim("sub", "+84912345678")
                        .build());

        AuthDtos.AuthResponse response = authService.login(
                new AuthDtos.LoginRequest("+84 912 345 678", "strongpass"));

        assertEquals("signed-token", response.accessToken());
        assertEquals("+84912345678", response.user().phoneNumber());
        verify(userRepository).findWithRolesByPhoneNumber("+84912345678");
    }

    @Test
    void loginReturnsSameUnauthorizedErrorForInactiveUsers() {
        UserEntity inactiveUser = org.mockito.Mockito.mock(UserEntity.class);
        when(inactiveUser.getStatus()).thenReturn("LOCKED");
        when(userRepository.findWithRolesByEmailIgnoreCase("Student@Example.com"))
                .thenReturn(Optional.of(inactiveUser));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.login(new AuthDtos.LoginRequest("Student@Example.com", "strongpass")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(passwordEncoder, never()).matches(any(), any());
    }
}
