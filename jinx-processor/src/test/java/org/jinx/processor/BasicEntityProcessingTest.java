package org.jinx.processor;

import org.jinx.model.EntityModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BasicEntityProcessingTest extends AbstractProcessorTest {

    @Test
    void testSimpleEntity() {
        // 1. 컴파일 실행
        var compilation = compile(source("entities/SimpleUser.java"));

        // 2. 결과 검증 및 SchemaModel 로드
        Optional<SchemaModel> schemaModelOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaModelOpt).isPresent();
        SchemaModel schema = schemaModelOpt.get();

        // 3. SchemaModel 내용 상세 검증 (AssertJ 사용)
        assertThat(schema.getEntities()).hasSize(1);

        EntityModel userEntity = schema.getEntities().get("entities.SimpleUser");
        assertThat(userEntity).isNotNull();
        assertThat(userEntity.getTableName()).isEqualTo("users");
        assertThat(userEntity.getColumns()).hasSize(3);

        // ID 컬럼 검증
        assertThat(userEntity.findColumn("users", "id")).isNotNull()
                .satisfies(col -> {
                    assertThat(col.getColumnName()).isEqualTo("id");
                    assertThat(col.isPrimaryKey()).isTrue();
                    assertThat(col.getGenerationStrategy()).isEqualTo(GenerationStrategy.IDENTITY);
                });

        // Name 컬럼 검증
        assertThat(userEntity.findColumn("users", "user_name")).isNotNull()
                .satisfies(col -> {
                    assertThat(col.getColumnName()).isEqualTo("user_name");
                    assertThat(col.isNullable()).isFalse();
                    assertThat(col.getLength()).isEqualTo(50);
                });

        // Email 컬럼 검증 (기본값)
        assertThat(userEntity.findColumn("users", "email")).isNotNull()
                .satisfies(col -> {
                    assertThat(col.getColumnName()).isEqualTo("email");
                    assertThat(col.isNullable()).isTrue(); // @Column(nullable=false)가 없으므로 true
                });
    }
}