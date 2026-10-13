package vn.smartassessment.api.identity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByUsernameIgnoreCase(String username);

    @EntityGraph(attributePaths = "userRoles.role")
    Optional<UserEntity> findWithRolesByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "userRoles.role")
    Optional<UserEntity> findWithRolesByPhoneNumber(String phoneNumber);
}
