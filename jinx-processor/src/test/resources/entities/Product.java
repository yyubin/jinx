package entities;
import jakarta.persistence.*;

@Entity
public class Product extends BaseEntity {
    private String productName;
    private String name;
    private int price;
}