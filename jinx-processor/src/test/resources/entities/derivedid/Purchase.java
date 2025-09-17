package entities.derivedid;

import jakarta.persistence.*;

@Entity
@Table(name = "purchases")
public class Purchase {

    @EmbeddedId
    private PurchaseId id;

    @MapsId("customerId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @MapsId("productId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected Purchase() {}
}