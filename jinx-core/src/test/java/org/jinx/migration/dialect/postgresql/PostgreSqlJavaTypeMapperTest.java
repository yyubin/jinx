package org.jinx.migration.dialect.postgresql;

import org.assertj.core.api.Assertions;
import org.jinx.migration.spi.JavaTypeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PostgreSqlJavaTypeMapperTest {

    private final PostgreSqlJavaTypeMapper mapper = new PostgreSqlJavaTypeMapper();

    @Nested
    @DisplayName("MySQL과 다른 PG 고유 타입 매핑")
    class PgSpecificTypes {

        @Test @DisplayName("Boolean → BOOLEAN (MySQL의 TINYINT(1)과 다름), default 'false'")
        void booleanMapsToBoolean() {
            JavaTypeMapper.JavaType t = mapper.map("java.lang.Boolean");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("BOOLEAN");
            Assertions.assertThat(t.getDefaultValue()).isEqualTo("false");
            Assertions.assertThat(t.needsQuotes()).isFalse();
        }

        @Test @DisplayName("primitive boolean → BOOLEAN")
        void primitiveBooleanMapsToBoolean() {
            JavaTypeMapper.JavaType t = mapper.map("boolean");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("BOOLEAN");
            Assertions.assertThat(t.getDefaultValue()).isEqualTo("false");
        }

        @Test @DisplayName("Double → DOUBLE PRECISION (MySQL의 DOUBLE과 다름)")
        void doubleMapsToDoublePrecision() {
            Assertions.assertThat(mapper.map("java.lang.Double").getSqlType(0, 0, 0)).isEqualTo("DOUBLE PRECISION");
            Assertions.assertThat(mapper.map("double").getSqlType(0, 0, 0)).isEqualTo("DOUBLE PRECISION");
        }

        @Test @DisplayName("Float → REAL (MySQL의 FLOAT과 다름)")
        void floatMapsToReal() {
            Assertions.assertThat(mapper.map("java.lang.Float").getSqlType(0, 0, 0)).isEqualTo("REAL");
            Assertions.assertThat(mapper.map("float").getSqlType(0, 0, 0)).isEqualTo("REAL");
        }

        @Test @DisplayName("UUID → uuid (MySQL의 CHAR(36)과 다름), needsQuotes=false")
        void uuidMapsToNativeUuid() {
            JavaTypeMapper.JavaType t = mapper.map("java.util.UUID");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("uuid");
            Assertions.assertThat(t.needsQuotes()).isFalse();
        }

        @Test @DisplayName("Integer → INTEGER (MySQL의 INT와 유사)")
        void integerMapsToInteger() {
            Assertions.assertThat(mapper.map("java.lang.Integer").getSqlType(0, 0, 0)).isEqualTo("INTEGER");
            Assertions.assertThat(mapper.map("int").getSqlType(0, 0, 0)).isEqualTo("INTEGER");
        }

        @Test @DisplayName("LocalDateTime → TIMESTAMP (PG는 microsecond 기본 지원)")
        void localDateTimeMapsToTimestamp() {
            Assertions.assertThat(mapper.map("java.time.LocalDateTime").getSqlType(0, 0, 0)).isEqualTo("TIMESTAMP");
        }
    }

    @Nested
    @DisplayName("MySQL과 동일한 타입 매핑")
    class SharedTypes {

        @Test @DisplayName("Long → BIGINT")
        void longMapsToBigInt() {
            Assertions.assertThat(mapper.map("java.lang.Long").getSqlType(0, 0, 0)).isEqualTo("BIGINT");
            Assertions.assertThat(mapper.map("long").getSqlType(0, 0, 0)).isEqualTo("BIGINT");
        }

        @Test @DisplayName("BigDecimal → NUMERIC(precision, scale)")
        void bigDecimalMapsToNumeric() {
            Assertions.assertThat(mapper.map("java.math.BigDecimal").getSqlType(0, 0, 0)).isEqualTo("NUMERIC(10,2)");
            Assertions.assertThat(mapper.map("java.math.BigDecimal").getSqlType(0, 18, 4)).isEqualTo("NUMERIC(18,4)");
        }

        @Test @DisplayName("BigInteger → BIGINT")
        void bigIntegerMapsToBigInt() {
            Assertions.assertThat(mapper.map("java.math.BigInteger").getSqlType(0, 0, 0)).isEqualTo("BIGINT");
        }

        @Test @DisplayName("String → VARCHAR(n), needsQuotes=true")
        void stringMapsToVarchar() {
            JavaTypeMapper.JavaType t = mapper.map("java.lang.String");
            Assertions.assertThat(t.getSqlType(100, 0, 0)).isEqualTo("VARCHAR(100)");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("VARCHAR(255)");
            Assertions.assertThat(t.needsQuotes()).isTrue();
        }

        @Test @DisplayName("LocalDate → DATE")
        void localDateMapsToDate() {
            Assertions.assertThat(mapper.map("java.time.LocalDate").getSqlType(0, 0, 0)).isEqualTo("DATE");
        }

        @Test @DisplayName("byte, short → SMALLINT")
        void smallIntTypes() {
            Assertions.assertThat(mapper.map("byte").getSqlType(0, 0, 0)).isEqualTo("SMALLINT");
            Assertions.assertThat(mapper.map("short").getSqlType(0, 0, 0)).isEqualTo("SMALLINT");
        }

        @Test @DisplayName("char → CHAR(1)")
        void charMapsToChar1() {
            JavaTypeMapper.JavaType t = mapper.map("char");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("CHAR(1)");
            Assertions.assertThat(t.needsQuotes()).isTrue();
        }
    }

    @Test @DisplayName("알 수 없는 타입은 TEXT + needsQuotes=true")
    void unknownTypeFallsBackToText() {
        JavaTypeMapper.JavaType unknown = mapper.map("com.example.CustomType");
        Assertions.assertThat(unknown.getSqlType(0, 0, 0)).isEqualTo("TEXT");
        Assertions.assertThat(unknown.needsQuotes()).isTrue();
    }
}
