package vn.smartassessment.api.identity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.RoleEntity;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByRoleCode(String roleCode);
}
