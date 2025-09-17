package org.jinx.processor;

import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedIdProcessingTest extends AbstractProcessorTest {

    @Test
    void embeddedId_is_flattened_into_primary_key_columns() {
        var compilation = compile(
                source("entities/embeddedId/OrderId.java"),
                source("entities/embeddedId/Order.java")
        );

        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();

        SchemaModel schema = schemaOpt.get();
        EntityModel order = schema.getEntities().get("entities.embeddedId.Order");
        assertThat(order).isNotNull();

        // @EmbeddedId의 필드가 PK 컬럼으로 펼쳐졌는지 확인
        assertThat(order.getColumns().keySet().stream().map(Object::toString))
                .contains("Orders::id_orderNumber", "Orders::id_shopId", "Orders::total");

        assertThat(order.findColumn("Orders", "id_orderNumber").isPrimaryKey()).isTrue();
        assertThat(order.findColumn("Orders", "id_shopId").isPrimaryKey()).isTrue();

        // 일반 컬럼도 존재
        assertThat(order.findColumn("Orders", "total")).isNotNull();
    }
}
