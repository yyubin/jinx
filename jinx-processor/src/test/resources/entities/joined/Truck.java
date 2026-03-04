package entities.joined;

import jakarta.persistence.*;

/**
 * Vehicle의 또 다른 직계 JOINED 자식.
 * trucks 테이블은 id(FK) + payload 만 가져야 한다.
 */
@Entity
@Table(name = "trucks")
public class Truck extends Vehicle {

    @Column(nullable = false)
    private double payload;

    public double getPayload() { return payload; }
}
