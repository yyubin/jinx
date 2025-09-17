package entities.elementCollection;

import jakarta.persistence.Embeddable;

@Embeddable
public class Tag {
    private String name;

    public Tag() {}

    public Tag(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
