package entities.embeddedId;

import jakarta.persistence.Embeddable;
import java.util.Objects;

@Embeddable
public class OrderId {
    private String orderNumber;
    private Long shopId;

    public OrderId() {} // 기본 생성자

    public OrderId(String orderNumber, Long shopId) {
        this.orderNumber = orderNumber;
        this.shopId = shopId;
    }

    public String getOrderNumber() { return orderNumber; }
    public Long getShopId() { return shopId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderId)) return false;
        OrderId other = (OrderId) o;
        return Objects.equals(orderNumber, other.orderNumber)
                && Objects.equals(shopId, other.shopId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderNumber, shopId);
    }
}
