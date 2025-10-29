package org.jinx.migration.liquibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jinx.migration.liquibase.model.ColumnConfig;
import org.jinx.migration.liquibase.model.Constraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("기본 필드 설정")
    class BasicFieldTests {

        @Test
        @DisplayName("name과 type만 설정")
        void basicNameAndType() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("user_id")
                    .type("BIGINT")
                    .build();

            assertEquals("user_id", config.getName());
            assertEquals("BIGINT", config.getType());
            assertNull(config.getConstraints());
            assertNull(config.getAutoIncrement());
        }

        @Test
        @DisplayName("모든 기본 필드 설정")
        void allBasicFields() {
            Constraints constraints = Constraints.builder()
                    .nullable(false)
                    .primaryKey(true)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("VARCHAR(36)")
                    .constraints(constraints)
                    .autoIncrement(true)
                    .build();

            assertEquals("id", config.getName());
            assertEquals("VARCHAR(36)", config.getType());
            assertNotNull(config.getConstraints());
            assertFalse(config.getConstraints().getNullable());
            assertTrue(config.getConstraints().getPrimaryKey());
            assertTrue(config.getAutoIncrement());
        }

        @Test
        @DisplayName("빌더는 null 값을 허용한다")
        void builderAllowsNullValues() {
            ColumnConfig config = ColumnConfig.builder()
                    .name(null)
                    .type(null)
                    .constraints(null)
                    .autoIncrement(null)
                    .build();

            assertNull(config.getName());
            assertNull(config.getType());
            assertNull(config.getConstraints());
            assertNull(config.getAutoIncrement());
        }
    }

    @Nested
    @DisplayName("빌더 체이닝 및 유연성")
    class BuilderChainingTests {

        @Test
        @DisplayName("빌더 메서드는 체이닝 가능하다")
        void builderMethodChaining() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("column1")
                    .type("INT")
                    .defaultValue("0")
                    .autoIncrement(false)
                    .build();

            assertEquals("column1", config.getName());
            assertEquals("INT", config.getType());
            assertEquals("0", config.getDefaultValue());
            assertFalse(config.getAutoIncrement());
        }

        @Test
        @DisplayName("빌더는 순서에 관계없이 동작한다")
        void builderOrderIndependent() {
            // 순서 1: name -> type -> defaultValue
            ColumnConfig config1 = ColumnConfig.builder()
                    .name("test")
                    .type("VARCHAR(50)")
                    .defaultValue("default")
                    .build();

            // 순서 2: defaultValue -> type -> name
            ColumnConfig config2 = ColumnConfig.builder()
                    .defaultValue("default")
                    .type("VARCHAR(50)")
                    .name("test")
                    .build();

            assertEquals(config1.getName(), config2.getName());
            assertEquals(config1.getType(), config2.getType());
            assertEquals(config1.getDefaultValue(), config2.getDefaultValue());
        }

        @Test
        @DisplayName("빌더는 여러 번 사용 가능하다")
        void builderReusable() {
            ColumnConfig.ColumnConfigBuilder builder = ColumnConfig.builder();

            ColumnConfig config1 = builder.name("col1").type("INT").build();
            ColumnConfig config2 = builder.name("col2").type("VARCHAR").build();

            // 두 번째 build()는 첫 번째 설정을 재사용
            assertEquals("col2", config2.getName());
            assertEquals("VARCHAR", config2.getType());
        }
    }

    @Nested
    @DisplayName("Constraints 설정")
    class ConstraintsTests {

        @Test
        @DisplayName("Constraints를 설정할 수 있다")
        void setConstraints() {
            Constraints constraints = Constraints.builder()
                    .nullable(false)
                    .unique(true)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("email")
                    .type("VARCHAR(255)")
                    .constraints(constraints)
                    .build();

            assertNotNull(config.getConstraints());
            assertFalse(config.getConstraints().getNullable());
            assertTrue(config.getConstraints().getUnique());
        }

        @Test
        @DisplayName("Constraints 없이도 생성 가능하다")
        void noConstraints() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("description")
                    .type("TEXT")
                    .build();

            assertNull(config.getConstraints());
        }

        @Test
        @DisplayName("Primary Key Constraints 설정")
        void primaryKeyConstraints() {
            Constraints constraints = Constraints.builder()
                    .primaryKey(true)
                    .nullable(false)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("BIGINT")
                    .constraints(constraints)
                    .autoIncrement(true)
                    .build();

            assertTrue(config.getConstraints().getPrimaryKey());
            assertFalse(config.getConstraints().getNullable());
            assertTrue(config.getAutoIncrement());
        }
    }

    @Nested
    @DisplayName("AutoIncrement 설정")
    class AutoIncrementTests {

        @Test
        @DisplayName("autoIncrement를 true로 설정")
        void autoIncrementTrue() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("BIGINT")
                    .autoIncrement(true)
                    .build();

            assertTrue(config.getAutoIncrement());
        }

        @Test
        @DisplayName("autoIncrement를 false로 설정")
        void autoIncrementFalse() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("BIGINT")
                    .autoIncrement(false)
                    .build();

            assertFalse(config.getAutoIncrement());
        }

        @Test
        @DisplayName("autoIncrement 기본값은 null")
        void autoIncrementDefaultNull() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("BIGINT")
                    .build();

            assertNull(config.getAutoIncrement());
        }
    }

    @Nested
    @DisplayName("InsertData 값 설정")
    class InsertDataTests {

        @Test
        @DisplayName("value 단독 설정")
        void valueOnly() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("name")
                    .value("John Doe")
                    .build();

            assertEquals("John Doe", config.getValue());
            assertNull(config.getValueNumeric());
            assertNull(config.getValueComputed());
        }

        @Test
        @DisplayName("valueNumeric 단독 설정")
        void valueNumericOnly() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("age")
                    .valueNumeric("25")
                    .build();

            assertNull(config.getValue());
            assertEquals("25", config.getValueNumeric());
            assertNull(config.getValueComputed());
        }

        @Test
        @DisplayName("valueComputed 단독 설정")
        void valueComputedOnly() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("created_at")
                    .valueComputed("NOW()")
                    .build();

            assertNull(config.getValue());
            assertNull(config.getValueNumeric());
            assertEquals("NOW()", config.getValueComputed());
        }
    }

    @Nested
    @DisplayName("복합 시나리오")
    class ComplexScenarioTests {

        @Test
        @DisplayName("PK 컬럼 생성 시나리오")
        void primaryKeyColumnScenario() {
            Constraints constraints = Constraints.builder()
                    .primaryKey(true)
                    .nullable(false)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("BIGINT")
                    .constraints(constraints)
                    .autoIncrement(true)
                    .build();

            assertEquals("id", config.getName());
            assertEquals("BIGINT", config.getType());
            assertTrue(config.getConstraints().getPrimaryKey());
            assertFalse(config.getConstraints().getNullable());
            assertTrue(config.getAutoIncrement());
        }

        @Test
        @DisplayName("UUID 컬럼 생성 시나리오")
        void uuidColumnScenario() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("VARCHAR(36)")
                    .defaultValueComputed("UUID()")
                    .build();

            assertEquals("id", config.getName());
            assertEquals("VARCHAR(36)", config.getType());
            assertEquals("UUID()", config.getDefaultValueComputed());
            assertNull(config.getDefaultValue());
            assertNull(config.getDefaultValueSequenceNext());
        }

        @Test
        @DisplayName("Timestamp 컬럼 생성 시나리오")
        void timestampColumnScenario() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("created_at")
                    .type("TIMESTAMP")
                    .defaultValueComputed("CURRENT_TIMESTAMP")
                    .build();

            assertEquals("created_at", config.getName());
            assertEquals("TIMESTAMP", config.getType());
            assertEquals("CURRENT_TIMESTAMP", config.getDefaultValueComputed());
        }

        @Test
        @DisplayName("Nullable 컬럼 생성 시나리오")
        void nullableColumnScenario() {
            Constraints constraints = Constraints.builder()
                    .nullable(true)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("user_id")
                    .type("BIGINT")
                    .constraints(constraints)
                    .build();

            assertEquals("user_id", config.getName());
            assertEquals("BIGINT", config.getType());
            assertTrue(config.getConstraints().getNullable());
        }

        @Test
        @DisplayName("Sequence 기반 컬럼 생성 시나리오")
        void sequenceColumnScenario() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("order_number")
                    .type("BIGINT")
                    .defaultValueSequenceNext("order_seq")
                    .build();

            assertEquals("order_number", config.getName());
            assertEquals("BIGINT", config.getType());
            assertEquals("order_seq", config.getDefaultValueSequenceNext());
            assertNull(config.getDefaultValue());
            assertNull(config.getDefaultValueComputed());
        }
    }

    @Nested
    @DisplayName("JSON 직렬화")
    class JsonSerializationTests {

        @Test
        @DisplayName("@JsonInclude: null 필드는 직렬화에서 제외")
        void jsonIncludeNonNull() throws Exception {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("VARCHAR(50)")
                    .build();

            String json = objectMapper.writeValueAsString(config);

            // null 필드들은 JSON에 포함되지 않아야 함
            assertFalse(json.contains("defaultValue"));
            assertFalse(json.contains("defaultValueSequenceNext"));
            assertFalse(json.contains("defaultValueComputed"));
            assertFalse(json.contains("value"));
            assertFalse(json.contains("valueNumeric"));
            assertFalse(json.contains("valueComputed"));
        }

        @Test
        @DisplayName("JSON 직렬화 후 역직렬화")
        void jsonSerializationDeserialization() throws Exception {
            ColumnConfig original = ColumnConfig.builder()
                    .name("username")
                    .type("VARCHAR(100)")
                    .defaultValue("anonymous")
                    .build();

            String json = objectMapper.writeValueAsString(original);
            ColumnConfig deserialized = objectMapper.readValue(json, ColumnConfig.class);

            assertEquals(original.getName(), deserialized.getName());
            assertEquals(original.getType(), deserialized.getType());
            assertEquals(original.getDefaultValue(), deserialized.getDefaultValue());
        }

        @Test
        @DisplayName("복잡한 객체 JSON 직렬화")
        void complexObjectJsonSerialization() throws Exception {
            Constraints constraints = Constraints.builder()
                    .nullable(false)
                    .unique(true)
                    .build();

            ColumnConfig config = ColumnConfig.builder()
                    .name("email")
                    .type("VARCHAR(255)")
                    .constraints(constraints)
                    .defaultValue("user@example.com")
                    .build();

            String json = objectMapper.writeValueAsString(config);

            // JSON 직렬화만 검증 (Constraints 역직렬화는 별도 테스트 필요)
            assertTrue(json.contains("\"name\":\"email\""));
            assertTrue(json.contains("\"type\":\"VARCHAR(255)\""));
            assertTrue(json.contains("\"defaultValue\":\"user@example.com\""));
        }
    }

    @Nested
    @DisplayName("Getter/Setter 테스트")
    class GetterSetterTests {

        @Test
        @DisplayName("Name getter/setter")
        void nameGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setName("test_column");
            assertEquals("test_column", config.getName());
        }

        @Test
        @DisplayName("Type getter/setter")
        void typeGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setType("INTEGER");
            assertEquals("INTEGER", config.getType());
        }

        @Test
        @DisplayName("DefaultValue getter/setter")
        void defaultValueGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setDefaultValue("default");
            assertEquals("default", config.getDefaultValue());
        }

        @Test
        @DisplayName("DefaultValueSequenceNext getter/setter")
        void defaultValueSequenceNextGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setDefaultValueSequenceNext("seq_name");
            assertEquals("seq_name", config.getDefaultValueSequenceNext());
        }

        @Test
        @DisplayName("DefaultValueComputed getter/setter")
        void defaultValueComputedGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setDefaultValueComputed("NOW()");
            assertEquals("NOW()", config.getDefaultValueComputed());
        }

        @Test
        @DisplayName("Value getter/setter")
        void valueGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setValue("value1");
            assertEquals("value1", config.getValue());
        }

        @Test
        @DisplayName("ValueNumeric getter/setter")
        void valueNumericGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setValueNumeric("123");
            assertEquals("123", config.getValueNumeric());
        }

        @Test
        @DisplayName("ValueComputed getter/setter")
        void valueComputedGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setValueComputed("CURRENT_TIMESTAMP");
            assertEquals("CURRENT_TIMESTAMP", config.getValueComputed());
        }

        @Test
        @DisplayName("AutoIncrement getter/setter")
        void autoIncrementGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            config.setAutoIncrement(true);
            assertTrue(config.getAutoIncrement());
        }

        @Test
        @DisplayName("Constraints getter/setter")
        void constraintsGetterSetter() {
            ColumnConfig config = new ColumnConfig();
            Constraints constraints = Constraints.builder().build();
            config.setConstraints(constraints);
            assertNotNull(config.getConstraints());
        }
    }

    @Nested
    @DisplayName("에지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("빈 문자열 처리")
        void emptyStrings() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("")
                    .type("")
                    .defaultValue("")
                    .build();

            assertEquals("", config.getName());
            assertEquals("", config.getType());
            assertEquals("", config.getDefaultValue());
        }

        @Test
        @DisplayName("매우 긴 문자열 처리")
        void veryLongStrings() {
            String longString = "A".repeat(1000);

            ColumnConfig config = ColumnConfig.builder()
                    .name(longString)
                    .type(longString)
                    .defaultValue(longString)
                    .build();

            assertEquals(longString, config.getName());
            assertEquals(longString, config.getType());
            assertEquals(longString, config.getDefaultValue());
        }

        @Test
        @DisplayName("특수 문자 포함 문자열")
        void specialCharacters() {
            String specialChars = "!@#$%^&*()_+-=[]{}|;':\",./<>?";

            ColumnConfig config = ColumnConfig.builder()
                    .name("column_" + specialChars)
                    .defaultValue(specialChars)
                    .build();

            assertTrue(config.getName().contains(specialChars));
            assertEquals(specialChars, config.getDefaultValue());
        }

        @Test
        @DisplayName("SQL 예약어 사용")
        void sqlReservedWords() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("SELECT")
                    .type("FROM")
                    .defaultValue("WHERE")
                    .build();

            assertEquals("SELECT", config.getName());
            assertEquals("FROM", config.getType());
            assertEquals("WHERE", config.getDefaultValue());
        }
    }
}
