package org.jinx.processor;

import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexEntityProcessingTest extends AbstractProcessorTest {

    @Test
    void testComplexEntities() {
        var compilation = compile(
                source("entities/BaseEntity.java"),
                source("entities/User.java"),
                source("entities/Order.java"),
                source("entities/Product.java"),
                source("entities/OrderStatus.java")
        );

        Optional<SchemaModel> schemaModelOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaModelOpt).isPresent();
        SchemaModel schema = schemaModelOpt.get();

        // BaseEntity는 엔티티 아님
        assertThat(schema.getEntities().get("entities.BaseEntity")).isNull();

        // User 엔티티 검증
        EntityModel userEntity = schema.getEntities().get("entities.User");
        assertThat(userEntity).isNotNull();
        assertThat(userEntity.getColumns().keySet().toString())
                .contains("User::id", "User::createdAt", "User::username");

        // Order 엔티티 검증
        EntityModel orderEntity = schema.getEntities().get("entities.Order");
        assertThat(orderEntity).isNotNull();
        assertThat(orderEntity.getColumns().keySet().toString())
                .contains("orders::status", "orders::user_id");

        // Product 엔티티 검증
        EntityModel productEntity = schema.getEntities().get("entities.Product");
        assertThat(productEntity).isNotNull();
        assertThat(productEntity.getColumns().keySet().toString())
                .contains("Product::name", "Product::price");

        // 연관관계 및 Enum 매핑 확인
        assertThat(orderEntity.getColumns().keySet().toString())
                .contains("orders::status"); // Enum 확인
    }
}
