package vn.smartassessment.api.identity.service;

import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartassessment.api.identity.dto.AuthDtos;
import vn.smartassessment.api.identity.entity.RoleEntity;
import vn.smartassessment.api.identity.entity.UserEntity;
import vn.smartassessment.api.identity.entity.UserRoleEntity;
import vn.smartassessment.api.identity.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserView getCurrentUser(String identifier) {
        UserEntity user = (identifier.contains("@")
                        ? userRepository.findWithRolesByEmailIgnoreCase(identifier)
                        : userRepository.findWithRolesByPhoneNumber(identifier))
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản đang hoạt động."));

        List<String> roleCodes = user.getUserRoles().stream()
                .map(UserRoleEntity::getRole)
                .map(RoleEntity::getRoleCode)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (roleCodes.isEmpty()) {
            throw new UsernameNotFoundException("Tài khoản chưa được gán vai trò.");
        }

        return AuthService.userView(user, roleCodes);
    }
}
