package entities.joined;

import jakarta.persistence.*;

/**
 * Bug 2 검증용 JOINED 상속 루트 엔티티.
 * Car, Truck, SportsCar 테이블에 이 테이블의 컬럼이 중복되어서는 안 된다.
 */
@Entity
@Table(name = "vehicles")
@Inheritance(strategy = InheritanceType.JOINED)
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String manufacturer;

    public Long getId() { return id; }
    public String getManufacturer() { return manufacturer; }
}
