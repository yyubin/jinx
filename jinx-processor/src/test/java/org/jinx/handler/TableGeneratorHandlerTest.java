package org.jinx.handler;

import jakarta.persistence.TableGenerator;
import jakarta.persistence.TableGenerators;
import org.jinx.context.ProcessingContext;
import org.jinx.model.SchemaModel;
import org.jinx.model.TableGeneratorModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import static com.google.common.truth.Truth.assertThat;

@DisplayName("TableGeneratorHandler 테스트")
class TableGeneratorHandlerTest {

    private ProcessingContext ctx;
    private TableGeneratorHandler handler;
    private Element dummyElement;
    private TypeElement dummyTypeElement;

    @BeforeEach
    void setUp() {
        ProcessingEnvironment env = Mockito.mock(ProcessingEnvironment.class);
        Messager messager = Mockito.mock(Messager.class);
        Mockito.when(env.getMessager()).thenReturn(messager);

        ctx = new ProcessingContext(env, SchemaModel.builder().build());
        handler = new TableGeneratorHandler(ctx);
        dummyElement = Mockito.mock(Element.class);
        dummyTypeElement = Mockito.mock(TypeElement.class);
    }

    // 모든 속성이 설정된 유효한 TableGenerator
    @TableGenerator(
            name = "gen_valid",
            table = "id_generators",
            schema = "public",
            catalog = "mydb",
            pkColumnName = "gen_name",
            pkColumnValue = "user_gen",
            valueColumnName = "gen_value",
            initialValue = 100,
            allocationSize = 10
    )
    private static class ValidFull {}

    // 기본값을 사용하는 TableGenerator
    @TableGenerator(name = "gen_defaults")
    private static class ValidDefaults {}

    // 중복 이름
    @TableGenerator(name = "gen_valid")
    private static class Duplicate {}

    // Blank name
    @TableGenerator(name = "")
    private static class BlankName {}

    // 빈 문자열 속성들
    @TableGenerator(
            name = "gen_empty",
            table = "",
            schema = "",
            catalog = "",
            pkColumnName = "",
            pkColumnValue = "",
            valueColumnName = ""
    )
    private static class EmptyStrings {}

    // 여러 개의 TableGenerator
    @TableGenerators({
            @TableGenerator(name = "gen1", table = "table1"),
            @TableGenerator(name = "gen2", table = "table2"),
            @TableGenerator(name = "gen3", table = "table3")
    })
    private static class MultipleGenerators {}

    // 단일 + 복수 모두 사용
    @TableGenerator(name = "gen_single", table = "single_table")
    @TableGenerators({
            @TableGenerator(name = "gen_multi1", table = "multi_table1"),
            @TableGenerator(name = "gen_multi2", table = "multi_table2")
    })
    private static class SingleAndMultiple {}

    @Test
    @DisplayName("모든 속성이 설정된 TableGenerator를 스키마에 추가")
    void addsNewTableGeneratorWithAllProperties() {
        TableGenerator tg = ValidFull.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_valid");
        assertThat(model).isNotNull();
        assertThat(model.getName()).isEqualTo("gen_valid");
        assertThat(model.getTable()).isEqualTo("id_generators");
        assertThat(model.getSchema()).isEqualTo("public");
        assertThat(model.getCatalog()).isEqualTo("mydb");
        assertThat(model.getPkColumnName()).isEqualTo("gen_name");
        assertThat(model.getPkColumnValue()).isEqualTo("user_gen");
        assertThat(model.getValueColumnName()).isEqualTo("gen_value");
        assertThat(model.getInitialValue()).isEqualTo(100);
        assertThat(model.getAllocationSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("기본값을 사용하는 TableGenerator 추가")
    void addsTableGeneratorWithDefaults() {
        TableGenerator tg = ValidDefaults.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_defaults");
        assertThat(model).isNotNull();
        assertThat(model.getName()).isEqualTo("gen_defaults");
        assertThat(model.getTable()).isEqualTo("gen_defaults"); // name과 동일
        assertThat(model.getSchema()).isNull();
        assertThat(model.getCatalog()).isNull();
        assertThat(model.getPkColumnName()).isEqualTo("sequence_name"); // 기본값
        assertThat(model.getPkColumnValue()).isEqualTo("gen_defaults"); // name과 동일
        assertThat(model.getValueColumnName()).isEqualTo("next_val"); // 기본값
        assertThat(model.getInitialValue()).isEqualTo(0);
        assertThat(model.getAllocationSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("빈 문자열 속성은 기본값으로 처리")
    void handlesEmptyStringsAsDefaults() {
        TableGenerator tg = EmptyStrings.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_empty");
        assertThat(model).isNotNull();
        assertThat(model.getTable()).isEqualTo("gen_empty"); // blank이므로 name 사용
        assertThat(model.getSchema()).isNull(); // isEmpty이므로 null
        assertThat(model.getCatalog()).isNull();
        assertThat(model.getPkColumnName()).isEqualTo("sequence_name"); // blank이므로 기본값
        assertThat(model.getPkColumnValue()).isEqualTo("gen_empty"); // blank이므로 name 사용
        assertThat(model.getValueColumnName()).isEqualTo("next_val"); // blank이므로 기본값
    }

    @Test
    @DisplayName("중복된 이름의 TableGenerator는 무시")
    void ignoresDuplicateGeneratorName() {
        TableGenerator first = ValidFull.class.getAnnotation(TableGenerator.class);
        TableGenerator dup = Duplicate.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(first, dummyElement);
        handler.processSingleGenerator(dup, dummyElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(1);
        // 첫 번째 것이 유지되어야 함
        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_valid");
        assertThat(model.getTable()).isEqualTo("id_generators");
    }

    @Test
    @DisplayName("Blank name은 거부되고 스키마에 추가되지 않음")
    void blankNameIsRejected() {
        TableGenerator blank = BlankName.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(blank, dummyElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).isEmpty();
    }

    @Test
    @DisplayName("여러 개의 @TableGenerators 처리")
    void processesMultipleTableGenerators() {
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class)).thenReturn(null);
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class))
                .thenReturn(MultipleGenerators.class.getAnnotation(TableGenerators.class));

        handler.processTableGenerators(dummyTypeElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(3);
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen1")).isNotNull();
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen2")).isNotNull();
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen3")).isNotNull();

        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen1").getTable()).isEqualTo("table1");
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen2").getTable()).isEqualTo("table2");
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen3").getTable()).isEqualTo("table3");
    }

    @Test
    @DisplayName("단일 @TableGenerator와 @TableGenerators 모두 처리")
    void processesSingleAndMultipleGenerators() {
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class))
                .thenReturn(SingleAndMultiple.class.getAnnotation(TableGenerator.class));
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class))
                .thenReturn(SingleAndMultiple.class.getAnnotation(TableGenerators.class));

        handler.processTableGenerators(dummyTypeElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(3);
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_single")).isNotNull();
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_multi1")).isNotNull();
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_multi2")).isNotNull();

        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_single").getTable()).isEqualTo("single_table");
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_multi1").getTable()).isEqualTo("multi_table1");
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_multi2").getTable()).isEqualTo("multi_table2");
    }

    @Test
    @DisplayName("애노테이션이 없는 TypeElement 처리")
    void handlesTypeElementWithNoAnnotations() {
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class)).thenReturn(null);
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class)).thenReturn(null);

        handler.processTableGenerators(dummyTypeElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).isEmpty();
    }

    @Test
    @DisplayName("단일 @TableGenerator만 있는 TypeElement 처리")
    void processesSingleGeneratorOnTypeElement() {
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class))
                .thenReturn(ValidFull.class.getAnnotation(TableGenerator.class));
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class)).thenReturn(null);

        handler.processTableGenerators(dummyTypeElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(1);
        assertThat(ctx.getSchemaModel().getTableGenerators().get("gen_valid")).isNotNull();
    }

    @Test
    @DisplayName("@TableGenerators만 있는 TypeElement 처리")
    void processesMultipleGeneratorsOnlyOnTypeElement() {
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class)).thenReturn(null);
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class))
                .thenReturn(MultipleGenerators.class.getAnnotation(TableGenerators.class));

        handler.processTableGenerators(dummyTypeElement);

        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(3);
    }

    @Test
    @DisplayName("중복된 이름을 포함한 @TableGenerators 처리")
    void processesMultipleGeneratorsWithDuplicates() {
        // gen1과 gen2를 먼저 추가
        handler.processSingleGenerator(
                MultipleGenerators.class.getAnnotation(TableGenerators.class).value()[0],
                dummyElement
        );

        // 모든 제너레이터 처리 (gen1은 중복, gen2와 gen3은 새로운 것)
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerator.class)).thenReturn(null);
        Mockito.when(dummyTypeElement.getAnnotation(TableGenerators.class))
                .thenReturn(MultipleGenerators.class.getAnnotation(TableGenerators.class));

        handler.processTableGenerators(dummyTypeElement);

        // gen1은 이미 있으므로 무시, gen2와 gen3만 추가되어 총 3개
        assertThat(ctx.getSchemaModel().getTableGenerators()).hasSize(3);
    }

    @Test
    @DisplayName("InitialValue와 AllocationSize 값 확인")
    void verificesInitialValueAndAllocationSize() {
        TableGenerator tg = ValidFull.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_valid");
        assertThat(model.getInitialValue()).isEqualTo(100);
        assertThat(model.getAllocationSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("Table 이름이 blank일 때 name을 사용")
    void usesNameWhenTableIsBlank() {
        TableGenerator tg = ValidDefaults.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_defaults");
        assertThat(model.getTable()).isEqualTo("gen_defaults");
    }

    @Test
    @DisplayName("PkColumnValue가 blank일 때 name을 사용")
    void usesNameWhenPkColumnValueIsBlank() {
        TableGenerator tg = ValidDefaults.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_defaults");
        assertThat(model.getPkColumnValue()).isEqualTo("gen_defaults");
    }

    @Test
    @DisplayName("Schema와 Catalog가 empty일 때 null로 저장")
    void storesNullForEmptySchemaAndCatalog() {
        TableGenerator tg = ValidDefaults.class.getAnnotation(TableGenerator.class);

        handler.processSingleGenerator(tg, dummyElement);

        TableGeneratorModel model = ctx.getSchemaModel().getTableGenerators().get("gen_defaults");
        assertThat(model.getSchema()).isNull();
        assertThat(model.getCatalog()).isNull();
    }
}
