package entities.embeddedId;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "Orders")
public class Order {

    @EmbeddedId
    private OrderId id;

    @Column(nullable = false)
    private BigDecimal total;

    public Order() {}

    public Order(OrderId id, BigDecimal total) {
        this.id = id;
        this.total = total;
    }

    public OrderId getId() { return id; }
    public BigDecimal getTotal() { return total; }
}
