package vn.smartassessment.api.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.ClassEntity;

public interface ClassRepository extends JpaRepository<ClassEntity, Long> {}
