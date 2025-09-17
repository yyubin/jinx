package entities.derivedid;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class PurchaseId implements Serializable {
    private Long customerId;
    private Long productId;

    public PurchaseId() {}
    public PurchaseId(Long customerId, Long productId) {
        this.customerId = customerId;
        this.productId = productId;
    }

    public Long getCustomerId() { return customerId; }
    public Long getProductId() { return productId; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseId that)) return false;
        return Objects.equals(customerId, that.customerId) &&
                Objects.equals(productId, that.productId);
    }
    @Override public int hashCode() { return Objects.hash(customerId, productId); }
}