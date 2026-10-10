package vn.smartassessment.api.identity.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "class_members")
public class ClassMemberEntity {
    @EmbeddedId
    private ClassMemberId id;

    @MapsId("classId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private ClassEntity classroom;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "joined_at", nullable = false, insertable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    protected ClassMemberEntity() {}
}
