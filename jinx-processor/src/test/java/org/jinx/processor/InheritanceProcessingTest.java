package org.jinx.processor;

import org.jinx.model.ColumnKey;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InheritanceProcessingTest extends AbstractProcessorTest {

    @Test
    void testMappedSuperclass() {
        var compilation = compile(
                source("entities/BaseEntity.java"),
                source("entities/Product.java")
        );

        Optional<SchemaModel> schemaModelOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaModelOpt).isPresent();
        SchemaModel schema = schemaModelOpt.get();

        // MappedSuperclass 자체는 Entity가 아니어야 함
        assertThat(schema.getEntities().get("entities.BaseEntity")).isNull();

        EntityModel productEntity = schema.getEntities().get("entities.Product");
        assertThat(productEntity).isNotNull();

        // BaseEntity의 필드(id, createdAt)와 Product의 필드(productName)가 모두 포함되었는지 확인
        assertThat(productEntity.getColumns()).hasSize(3);
        assertThat(productEntity.getColumns().keySet().stream().map(Object::toString))
            .contains("Product::id", "Product::createdAt", "Product::productName");

        // PK가 잘 상속되었는지 확인
        assertThat(productEntity.findColumn("Product", "id").isPrimaryKey()).isTrue();
    }
}