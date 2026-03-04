package entities.joined;

import jakarta.persistence.*;

/**
 * 다단계 JOINED 상속 검증용 (Car의 자식).
 * sports_cars 테이블은 id(FK) + topSpeed 만 가져야 하며
 * Car의 numDoors, Vehicle의 manufacturer 는 포함되어서는 안 된다.
 */
@Entity
@Table(name = "sports_cars")
public class SportsCar extends Car {

    @Column(nullable = false)
    private int topSpeed;

    public int getTopSpeed() { return topSpeed; }
}
