package entities;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class User extends BaseEntity {
    private String username;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<Order> orders = new ArrayList<>();
}
