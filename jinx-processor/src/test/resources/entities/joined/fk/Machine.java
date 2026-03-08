package entities.joined.fk;

import jakarta.persistence.*;

/**
 * @ForeignKey 설정 merge 검증용 JOINED 상속 루트.
 */
@Entity
@Table(name = "machines")
@Inheritance(strategy = InheritanceType.JOINED)
public class Machine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String model;

    public Long getId() { return id; }
    public String getModel() { return model; }
}
