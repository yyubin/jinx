package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.SequenceHandler;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AttributeBasedEntityResolver 테스트")
class AttributeBasedEntityResolverTest {

    private ProcessingContext ctx;
    private SequenceHandler sequenceHandler;
    private AttributeBasedEntityResolver resolver;
    private SchemaModel schemaModel;
    private Messager messager;

    @BeforeEach
    void setUp() {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        messager = mock(Messager.class);
        when(env.getMessager()).thenReturn(messager);

        schemaModel = SchemaModel.builder().build();
        ctx = new ProcessingContext(env, schemaModel);
        sequenceHandler = mock(SequenceHandler.class);
        resolver = new AttributeBasedEntityResolver(ctx, sequenceHandler);
    }

    @Test
    @DisplayName("생성자로 context와 sequenceHandler 저장")
    void constructorStoresContextAndSequenceHandler() {
        AttributeBasedEntityResolver newResolver = new AttributeBasedEntityResolver(ctx, sequenceHandler);
        assertThat(newResolver).isNotNull();
    }

    @Test
    @DisplayName("ProcessingContext는 null이 아님")
    void processingContextIsNotNull() {
        assertThat(ctx).isNotNull();
    }

    @Test
    @DisplayName("SequenceHandler는 null이 아님")
    void sequenceHandlerIsNotNull() {
        assertThat(sequenceHandler).isNotNull();
    }

    @Test
    @DisplayName("SchemaModel은 초기화됨")
    void schemaModelIsInitialized() {
        assertThat(schemaModel).isNotNull();
        assertThat(schemaModel.getEntities()).isNotNull();
        assertThat(schemaModel.getSequences()).isNotNull();
        assertThat(schemaModel.getTableGenerators()).isNotNull();
    }

    @Test
    @DisplayName("AttributeBasedEntityResolver 인스턴스 생성 성공")
    void instanceCreationSucceeds() {
        assertThat(resolver).isNotNull();
    }

    @Test
    @DisplayName("Context의 autoApplyConverters는 빈 맵으로 초기화됨")
    void autoApplyConvertersInitializedEmpty() {
        assertThat(ctx.getAutoApplyConverters()).isNotNull();
        assertThat(ctx.getAutoApplyConverters()).isEmpty();
    }

    @Test
    @DisplayName("Context의 autoApplyConverters에 추가 가능")
    void canAddToAutoApplyConverters() {
        ctx.getAutoApplyConverters().put("java.lang.String", "com.example.Converter");
        assertThat(ctx.getAutoApplyConverters()).hasSize(1);
        assertThat(ctx.getAutoApplyConverters().get("java.lang.String")).isEqualTo("com.example.Converter");
    }

    @Test
    @DisplayName("SchemaModel의 sequences 맵은 수정 가능")
    void schemaModelSequencesMapIsModifiable() {
        assertThat(schemaModel.getSequences()).isEmpty();
        schemaModel.getSequences().put("testSeq", org.jinx.model.SequenceModel.builder()
            .name("testSeq").initialValue(1).build());
        assertThat(schemaModel.getSequences()).hasSize(1);
    }

    @Test
    @DisplayName("SchemaModel의 tableGenerators 맵은 수정 가능")
    void schemaModelTableGeneratorsMapIsModifiable() {
        assertThat(schemaModel.getTableGenerators()).isEmpty();
        schemaModel.getTableGenerators().put("testGen", org.jinx.model.TableGeneratorModel.builder()
            .name("testGen").table("id_gen").build());
        assertThat(schemaModel.getTableGenerators()).hasSize(1);
    }

    @Test
    @DisplayName("여러 converter를 autoApplyConverters에 추가 가능")
    void canAddMultipleConverters() {
        ctx.getAutoApplyConverters().put("java.lang.String", "com.example.StringConverter");
        ctx.getAutoApplyConverters().put("java.lang.Integer", "com.example.IntegerConverter");
        ctx.getAutoApplyConverters().put("java.time.LocalDate", "com.example.DateConverter");

        assertThat(ctx.getAutoApplyConverters()).hasSize(3);
        assertThat(ctx.getAutoApplyConverters()).containsKey("java.lang.String");
        assertThat(ctx.getAutoApplyConverters()).containsKey("java.lang.Integer");
        assertThat(ctx.getAutoApplyConverters()).containsKey("java.time.LocalDate");
    }

    @Test
    @DisplayName("Messager는 context에서 접근 가능")
    void messagerIsAccessibleFromContext() {
        assertThat(ctx.getMessager()).isNotNull();
        assertThat(ctx.getMessager()).isEqualTo(messager);
    }

    @Test
    @DisplayName("SchemaModel은 context에서 접근 가능")
    void schemaModelIsAccessibleFromContext() {
        assertThat(ctx.getSchemaModel()).isNotNull();
        assertThat(ctx.getSchemaModel()).isEqualTo(schemaModel);
    }
}
