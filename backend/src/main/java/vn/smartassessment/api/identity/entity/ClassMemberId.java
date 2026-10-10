package vn.smartassessment.api.identity.entity;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ClassMemberId implements Serializable {
    @Column(name = "class_id")
    private Long classId;

    @Column(name = "user_id")
    private Long userId;

    protected ClassMemberId() {}

    public ClassMemberId(Long classId, Long userId) {
        this.classId = classId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ClassMemberId that)) return false;
        return Objects.equals(classId, that.classId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classId, userId);
    }
}
