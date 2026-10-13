package vn.smartassessment.api.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.smartassessment.api.identity.dto.AuthDtos;
import vn.smartassessment.api.identity.entity.RoleEntity;
import vn.smartassessment.api.identity.entity.UserEntity;
import vn.smartassessment.api.identity.entity.UserRoleEntity;
import vn.smartassessment.api.identity.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository userRepository;

    @Test
    void getsCurrentProfileForPhoneOnlyAccount() {
        RoleEntity role = org.mockito.Mockito.mock(RoleEntity.class);
        UserRoleEntity userRole = org.mockito.Mockito.mock(UserRoleEntity.class);
        UserEntity user = org.mockito.Mockito.mock(UserEntity.class);
        when(userRepository.findWithRolesByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getUserRoles()).thenReturn(Set.of(userRole));
        when(userRole.getRole()).thenReturn(role);
        when(role.getRoleCode()).thenReturn("STUDENT");
        when(user.getUserId()).thenReturn(7L);
        when(user.getEmail()).thenReturn(null);
        when(user.getPhoneNumber()).thenReturn("+84912345678");
        when(user.getFullName()).thenReturn("Student Name");
        when(user.getUsername()).thenReturn("student-abc");

        AuthDtos.UserView profile = new UserService(userRepository).getCurrentUser("+84912345678");

        assertEquals(7L, profile.userId());
        assertNull(profile.email());
        assertEquals("+84912345678", profile.phoneNumber());
        assertEquals("STUDENT", profile.role());
    }
}
