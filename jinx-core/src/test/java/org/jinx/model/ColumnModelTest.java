package org.jinx.model;

import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.TemporalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ColumnModel 테스트")
class ColumnModelTest {

    @Nested
    @DisplayName("빌더 및 기본값")
    class BuilderAndDefaults {

        @Test
        @DisplayName("빌더로 생성 시 모든 필드가 올바르게 설정됨")
        void builderSetsAllFields() {
            ColumnModel column = ColumnModel.builder()
                    .tableName("users")
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .comment("사용자 ID")
                    .isPrimaryKey(true)
                    .isNullable(false)
                    .length(255)
                    .precision(10)
                    .scale(2)
                    .defaultValue("0")
                    .generationStrategy(GenerationStrategy.IDENTITY)
                    .build();

            assertThat(column.getTableName()).isEqualTo("users");
            assertThat(column.getColumnName()).isEqualTo("user_id");
            assertThat(column.getJavaType()).isEqualTo("java.lang.Long");
            assertThat(column.getComment()).isEqualTo("사용자 ID");
            assertThat(column.isPrimaryKey()).isTrue();
            assertThat(column.isNullable()).isFalse();
            assertThat(column.getLength()).isEqualTo(255);
            assertThat(column.getPrecision()).isEqualTo(10);
            assertThat(column.getScale()).isEqualTo(2);
            assertThat(column.getDefaultValue()).isEqualTo("0");
            assertThat(column.getGenerationStrategy()).isEqualTo(GenerationStrategy.IDENTITY);
        }

        @Test
        @DisplayName("기본값이 올바르게 설정됨")
        void defaultValues() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("test_column")
                    .javaType("java.lang.String")
                    .build();

            assertThat(column.getTableName()).isEqualTo("");
            assertThat(column.getComment()).isNull();
            assertThat(column.isPrimaryKey()).isFalse();
            assertThat(column.isNullable()).isTrue();
            assertThat(column.getLength()).isEqualTo(255);
            assertThat(column.getPrecision()).isEqualTo(0);
            assertThat(column.getScale()).isEqualTo(0);
            assertThat(column.getDefaultValue()).isNull();
            assertThat(column.getGenerationStrategy()).isEqualTo(GenerationStrategy.NONE);
            assertThat(column.isManualPrimaryKey()).isFalse();
            assertThat(column.isLob()).isFalse();
            assertThat(column.isVersion()).isFalse();
            assertThat(column.isOptional()).isTrue();
            assertThat(column.getFetchType()).isEqualTo(FetchType.EAGER);
        }

        @Test
        @DisplayName("NoArgsConstructor로 생성 가능")
        void noArgsConstructor() {
            ColumnModel column = new ColumnModel();
            assertThat(column).isNotNull();
            assertThat(column.getTableName()).isEqualTo("");
            assertThat(column.getLength()).isEqualTo(255);
        }
    }

    @Nested
    @DisplayName("Enum 관련 필드")
    class EnumFields {

        @Test
        @DisplayName("Enum STRING 매핑이 올바르게 설정됨")
        void enumStringMapping() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("status")
                    .javaType("com.example.Status")
                    .enumStringMapping(true)
                    .enumValues(new String[]{"ACTIVE", "INACTIVE", "PENDING"})
                    .enumerationType(EnumType.STRING)
                    .length(20)
                    .build();

            assertThat(column.isEnumStringMapping()).isTrue();
            assertThat(column.getEnumValues()).containsExactly("ACTIVE", "INACTIVE", "PENDING");
            assertThat(column.getEnumerationType()).isEqualTo(EnumType.STRING);
            assertThat(column.getLength()).isEqualTo(20);
        }

        @Test
        @DisplayName("Enum ORDINAL 매핑이 올바르게 설정됨")
        void enumOrdinalMapping() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("priority")
                    .javaType("com.example.Priority")
                    .enumStringMapping(false)
                    .enumValues(new String[]{"LOW", "MEDIUM", "HIGH"})
                    .enumerationType(EnumType.ORDINAL)
                    .build();

            assertThat(column.isEnumStringMapping()).isFalse();
            assertThat(column.getEnumValues()).containsExactly("LOW", "MEDIUM", "HIGH");
            assertThat(column.getEnumerationType()).isEqualTo(EnumType.ORDINAL);
        }
    }

    @Nested
    @DisplayName("Identity 및 Generation 전략")
    class IdentityAndGeneration {

        @Test
        @DisplayName("IDENTITY 전략이 올바르게 설정됨")
        void identityStrategy() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("id")
                    .javaType("java.lang.Long")
                    .isPrimaryKey(true)
                    .generationStrategy(GenerationStrategy.IDENTITY)
                    .identityStartValue(1)
                    .identityIncrement(1)
                    .build();

            assertThat(column.getGenerationStrategy()).isEqualTo(GenerationStrategy.IDENTITY);
            assertThat(column.getIdentityStartValue()).isEqualTo(1);
            assertThat(column.getIdentityIncrement()).isEqualTo(1);
        }

        @Test
        @DisplayName("SEQUENCE 전략이 올바르게 설정됨")
        void sequenceStrategy() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("id")
                    .javaType("java.lang.Long")
                    .isPrimaryKey(true)
                    .generationStrategy(GenerationStrategy.SEQUENCE)
                    .sequenceName("user_seq")
                    .build();

            assertThat(column.getGenerationStrategy()).isEqualTo(GenerationStrategy.SEQUENCE);
            assertThat(column.getSequenceName()).isEqualTo("user_seq");
        }

        @Test
        @DisplayName("TABLE 전략이 올바르게 설정됨")
        void tableStrategy() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("id")
                    .javaType("java.lang.Long")
                    .isPrimaryKey(true)
                    .generationStrategy(GenerationStrategy.TABLE)
                    .tableGeneratorName("id_generator")
                    .build();

            assertThat(column.getGenerationStrategy()).isEqualTo(GenerationStrategy.TABLE);
            assertThat(column.getTableGeneratorName()).isEqualTo("id_generator");
        }
    }

    @Nested
    @DisplayName("특수 타입 (LOB, Version, Temporal)")
    class SpecialTypes {

        @Test
        @DisplayName("LOB 타입이 올바르게 설정됨")
        void lobType() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("content")
                    .javaType("java.lang.String")
                    .isLob(true)
                    .build();

            assertThat(column.isLob()).isTrue();
        }

        @Test
        @DisplayName("Version 컬럼이 올바르게 설정됨")
        void versionColumn() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("version")
                    .javaType("java.lang.Long")
                    .isVersion(true)
                    .build();

            assertThat(column.isVersion()).isTrue();
        }

        @Test
        @DisplayName("Temporal 타입이 올바르게 설정됨")
        void temporalType() {
            ColumnModel dateColumn = ColumnModel.builder()
                    .columnName("birth_date")
                    .javaType("java.util.Date")
                    .temporalType(TemporalType.DATE)
                    .build();

            ColumnModel timeColumn = ColumnModel.builder()
                    .columnName("start_time")
                    .javaType("java.util.Date")
                    .temporalType(TemporalType.TIME)
                    .build();

            ColumnModel timestampColumn = ColumnModel.builder()
                    .columnName("created_at")
                    .javaType("java.util.Date")
                    .temporalType(TemporalType.TIMESTAMP)
                    .build();

            assertThat(dateColumn.getTemporalType()).isEqualTo(TemporalType.DATE);
            assertThat(timeColumn.getTemporalType()).isEqualTo(TemporalType.TIME);
            assertThat(timestampColumn.getTemporalType()).isEqualTo(TemporalType.TIMESTAMP);
        }
    }

    @Nested
    @DisplayName("SQL 타입 오버라이드 및 변환")
    class SqlTypeOverride {

        @Test
        @DisplayName("SQL 타입 오버라이드가 올바르게 설정됨")
        void sqlTypeOverride() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("custom_column")
                    .javaType("java.lang.String")
                    .sqlTypeOverride("TEXT NOT NULL DEFAULT ''")
                    .build();

            assertThat(column.getSqlTypeOverride()).isEqualTo("TEXT NOT NULL DEFAULT ''");
        }

        @Test
        @DisplayName("Conversion 클래스가 올바르게 설정됨")
        void conversionClass() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("encrypted_data")
                    .javaType("java.lang.String")
                    .conversionClass("com.example.EncryptionConverter")
                    .build();

            assertThat(column.getConversionClass()).isEqualTo("com.example.EncryptionConverter");
        }
    }

    @Nested
    @DisplayName("해시 계산")
    class HashCalculation {

        @Test
        @DisplayName("getAttributeHash가 컬럼 속성 기반으로 해시를 계산함")
        void attributeHash() {
            ColumnModel column1 = ColumnModel.builder()
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .length(255)
                    .isNullable(false)
                    .build();

            ColumnModel column2 = ColumnModel.builder()
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .length(255)
                    .isNullable(false)
                    .build();

            assertThat(column1.getAttributeHash()).isEqualTo(column2.getAttributeHash());
        }

        @Test
        @DisplayName("다른 속성을 가진 컬럼은 다른 해시를 가짐")
        void differentAttributeHash() {
            ColumnModel column1 = ColumnModel.builder()
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .length(255)
                    .isNullable(false)
                    .build();

            ColumnModel column2 = ColumnModel.builder()
                    .columnName("user_id")
                    .javaType("java.lang.Integer") // 다른 타입
                    .length(255)
                    .isNullable(false)
                    .build();

            assertThat(column1.getAttributeHash()).isNotEqualTo(column2.getAttributeHash());
        }

        @Test
        @DisplayName("getAttributeHashExceptName이 이름을 제외한 해시를 계산함")
        void attributeHashExceptName() {
            ColumnModel column = ColumnModel.builder()
                    .tableName("users")
                    .columnName("user_id")
                    .javaType("java.lang.Long")
                    .length(255)
                    .build();

            long hash = column.getAttributeHashExceptName();
            assertThat(hash).isNotZero();
        }
    }

    @Nested
    @DisplayName("Discriminator 관련")
    class Discriminator {

        @Test
        @DisplayName("Discriminator 컬럼이 올바르게 설정됨")
        void discriminatorColumn() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("dtype")
                    .javaType("java.lang.String")
                    .columnKind(ColumnModel.ColumnKind.DISCRIMINATOR)
                    .discriminatorType(jakarta.persistence.DiscriminatorType.STRING)
                    .columnDefinition("VARCHAR(31) NOT NULL")
                    .build();

            assertThat(column.getColumnKind()).isEqualTo(ColumnModel.ColumnKind.DISCRIMINATOR);
            assertThat(column.getDiscriminatorType()).isEqualTo(jakarta.persistence.DiscriminatorType.STRING);
            assertThat(column.getColumnDefinition()).isEqualTo("VARCHAR(31) NOT NULL");
        }
    }

    @Nested
    @DisplayName("Primitive 타입 지원")
    class PrimitiveTypeSupport {

        @Test
        @DisplayName("int primitive 타입이 올바르게 설정됨")
        void intPrimitiveType() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("age")
                    .javaType("int")
                    .isNullable(false)
                    .build();

            assertThat(column.getJavaType()).isEqualTo("int");
            assertThat(column.isNullable()).isFalse();
        }

        @Test
        @DisplayName("boolean primitive 타입이 올바르게 설정됨")
        void booleanPrimitiveType() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("is_active")
                    .javaType("boolean")
                    .defaultValue("false")
                    .build();

            assertThat(column.getJavaType()).isEqualTo("boolean");
            assertThat(column.getDefaultValue()).isEqualTo("false");
        }

        @Test
        @DisplayName("double primitive 타입이 올바르게 설정됨")
        void doublePrimitiveType() {
            ColumnModel column = ColumnModel.builder()
                    .columnName("price")
                    .javaType("double")
                    .precision(10)
                    .scale(2)
                    .build();

            assertThat(column.getJavaType()).isEqualTo("double");
            assertThat(column.getPrecision()).isEqualTo(10);
            assertThat(column.getScale()).isEqualTo(2);
        }
    }
}
