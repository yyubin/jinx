package entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record PersonName(
    @Column(nullable = false)
    String firstName,

    String lastName
) {
}