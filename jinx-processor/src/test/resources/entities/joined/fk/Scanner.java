package entities.joined.fk;

import jakarta.persistence.*;

/**
 * @PrimaryKeyJoinColumn에 NO_CONSTRAINT가 설정된 JOINED 자식.
 * 처리 순서에 관계없이 noConstraint=true 로 모델에 반영돼야 한다.
 */
@Entity
@Table(name = "scanners")
@PrimaryKeyJoinColumn(
    name = "id",
    foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT)
)
public class Scanner extends Machine {

    @Column(nullable = false)
    private int dpi;

    public int getDpi() { return dpi; }
}
