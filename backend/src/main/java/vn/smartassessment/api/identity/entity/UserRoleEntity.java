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
@Table(name = "user_roles")
public class UserRoleEntity {
    @EmbeddedId
    private UserRoleId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    protected UserRoleEntity() {}

    public UserRoleEntity(UserEntity user, RoleEntity role) {
        this.id = new UserRoleId(user.getUserId(), role.getRoleId());
        this.user = user;
        this.role = role;
        this.assignedAt = Instant.now();
    }

    public RoleEntity getRole() {
        return role;
    }
}
