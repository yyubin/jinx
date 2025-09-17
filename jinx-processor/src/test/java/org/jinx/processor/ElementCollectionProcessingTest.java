package org.jinx.processor;

import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ElementCollectionProcessingTest extends AbstractProcessorTest {

    @Test
    void elementCollection_creates_secondary_table() {
        var compilation = compile(
                source("entities/elementCollection/Tag.java"),
                source("entities/elementCollection/Book.java")
        );

        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();

        SchemaModel schema = schemaOpt.get();
        EntityModel book = schema.getEntities().get("entities.elementCollection.Book");
        assertThat(book).isNotNull();

        // Book 기본 테이블 확인
        assertThat(book.getTableName()).isEqualTo("books");

        // tags는 별도의 CollectionTable(book_tags)에 매핑되어야 함
        assertThat(schema.getEntities().values().stream()
                .anyMatch(e -> "book_tags".equalsIgnoreCase(e.getTableName())))
                .isTrue();
    }
}
