package vn.smartassessment.api.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
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
                new JwtConfig("https://smart-assessment.local"));
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
        when(userRepository.existsByEmail("student@example.com")).thenReturn(false);
        when(roleRepository.findByRoleCode("STUDENT")).thenReturn(Optional.of(studentRole));
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(passwordEncoder.encode("strongpass")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("signed-token")
                        .header("alg", "HS256")
                        .claim("sub", "student@example.com")
                        .build());

        AuthDtos.AuthResponse response = authService.register(
                new AuthDtos.RegisterRequest(" Student Name ", "Student@Example.com", "strongpass"));

        assertEquals("signed-token", response.accessToken());
        assertEquals("STUDENT", response.user().role());
        verify(userRepository).save(userCaptor.capture());
        assertEquals("student@example.com", userCaptor.getValue().getEmail());
        assertEquals("bcrypt-hash", userCaptor.getValue().getPasswordHash());
        verify(userRoleRepository).save(userRoleCaptor.capture());
        assertEquals("STUDENT", userRoleCaptor.getValue().getRole().getRoleCode());
    }

    @Test
    void registerRejectsDuplicateEmailBeforeWriting() {
        when(userRepository.existsByEmail("student@example.com")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest("Student Name", "Student@Example.com", "strongpass")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void registerRejectsPasswordsLongerThanBcryptByteLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register(
                        new AuthDtos.RegisterRequest("Student Name", "student@example.com", "ắ".repeat(25))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void loginReturnsSameUnauthorizedErrorForInactiveUsers() {
        UserEntity inactiveUser = org.mockito.Mockito.mock(UserEntity.class);
        when(inactiveUser.getStatus()).thenReturn("LOCKED");
        when(userRepository.findWithRolesByEmail("student@example.com"))
                .thenReturn(Optional.of(inactiveUser));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.login(new AuthDtos.LoginRequest("Student@Example.com", "strongpass")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(passwordEncoder, never()).matches(any(), any());
    }
}
