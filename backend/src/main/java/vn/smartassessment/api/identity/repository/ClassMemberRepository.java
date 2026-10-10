package vn.smartassessment.api.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.smartassessment.api.identity.entity.ClassMemberEntity;
import vn.smartassessment.api.identity.entity.ClassMemberId;

public interface ClassMemberRepository extends JpaRepository<ClassMemberEntity, ClassMemberId> {}
