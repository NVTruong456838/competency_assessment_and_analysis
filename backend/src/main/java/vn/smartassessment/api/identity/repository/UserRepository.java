package vn.smartassessment.api.identity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    @EntityGraph(attributePaths = "userRoles.role")
    Optional<UserEntity> findWithRolesByEmail(String email);
}
