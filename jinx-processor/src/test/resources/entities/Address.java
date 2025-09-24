package entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record Address(
    @Column(name = "street_name")
    String street,

    String city,

    @Column(length = 10)
    String zipCode
) {
}