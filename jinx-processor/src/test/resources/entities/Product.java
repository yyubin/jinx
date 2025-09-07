package entities;
import jakarta.persistence.*;

@Entity
public class Product extends BaseEntity {
    private String productName;
}