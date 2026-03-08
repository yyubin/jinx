package entities.joined.fk;

import jakarta.persistence.*;

/**
 * @PrimaryKeyJoinColumn에 명시적 FK 이름이 설정된 JOINED 자식.
 * 처리 순서에 관계없이 "fk_printer_machine" 이름이 적용돼야 한다.
 */
@Entity
@Table(name = "printers")
@PrimaryKeyJoinColumn(
    name = "id",
    foreignKey = @ForeignKey(name = "fk_printer_machine")
)
public class Printer extends Machine {

    @Column(nullable = false)
    private int ppm;

    public int getPpm() { return ppm; }
}
