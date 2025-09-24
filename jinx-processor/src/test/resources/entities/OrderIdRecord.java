package entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record OrderIdRecord(
    @Column(name = "customer_id")
    Long customerId,

    @Column(name = "order_number")
    String orderNumber
) {
}