package entities;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class SimpleUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 50)
    private String name;

    private String email;
}