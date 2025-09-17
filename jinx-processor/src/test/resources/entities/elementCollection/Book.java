package entities.elementCollection;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @ElementCollection
    @CollectionTable(name = "book_tags", joinColumns = @JoinColumn(name = "book_id"))
    private List<Tag> tags = new ArrayList<>();

    public Book() {}

    public Book(String title, List<Tag> tags) {
        this.title = title;
        if (tags != null) this.tags = tags;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public List<Tag> getTags() { return tags; }
}
