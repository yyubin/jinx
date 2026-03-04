package entities.joined;

import jakarta.persistence.*;

/**
 * Vehicle의 직계 JOINED 자식.
 * 자식 테이블(cars)은 id(FK) + numDoors 만 가져야 하며
 * Vehicle의 manufacturer 컬럼은 포함되어서는 안 된다.
 */
@Entity
@Table(name = "cars")
public class Car extends Vehicle {

    @Column(nullable = false)
    private int numDoors;

    public int getNumDoors() { return numDoors; }
}
