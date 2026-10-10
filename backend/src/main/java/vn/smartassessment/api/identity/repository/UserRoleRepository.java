package vn.smartassessment.api.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.UserRoleEntity;
import vn.smartassessment.api.identity.entity.UserRoleId;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleId> {}
