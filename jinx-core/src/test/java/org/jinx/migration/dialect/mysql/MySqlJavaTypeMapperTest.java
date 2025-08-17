package org.jinx.migration.dialect.mysql;

import org.assertj.core.api.Assertions;
import org.jinx.migration.spi.JavaTypeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MySqlJavaTypeMapperTest {

    private final MySqlJavaTypeMapper mapper = new MySqlJavaTypeMapper();

    @Nested
    @DisplayName("기본 매핑 확인")
    class BasicMapping {

        @Test
        @DisplayName("Integer → INT")
        void integerMapsToInt() {
            JavaTypeMapper.JavaType t = mapper.map("java.lang.Integer");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("INT");
            Assertions.assertThat(t.needsQuotes()).isFalse();
        }

        @Test
        @DisplayName("Long → BIGINT")
        void longMapsToBigInt() {
            JavaTypeMapper.JavaType t = mapper.map("java.lang.Long");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("BIGINT");
        }

        @Test
        @DisplayName("Boolean → TINYINT(1) ; default 0")
        void booleanMapsToTinyint() {
            JavaTypeMapper.JavaType t = mapper.map("java.lang.Boolean");
            Assertions.assertThat(t.getSqlType(0, 0, 0)).isEqualTo("TINYINT(1)");
            Assertions.assertThat(t.getDefaultValue()).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("가변 길이 & Precision/Scale 형식")
    class VariableTypes {

        @Test
        @DisplayName("String 길이 기본값 255, 사용자 지정 가능")
        void varcharLength() {
            JavaTypeMapper.JavaType str = mapper.map("java.lang.String");
            Assertions.assertThat(str.getSqlType(0, 0, 0)).isEqualTo("VARCHAR(255)");
            Assertions.assertThat(str.getSqlType(50, 0, 0)).isEqualTo("VARCHAR(50)");
        }

        @Test
        @DisplayName("BigDecimal precision/scale 기본(10,2) 및 지정")
        void decimalPrecisionScale() {
            JavaTypeMapper.JavaType dec = mapper.map("java.math.BigDecimal");
            Assertions.assertThat(dec.getSqlType(0, 0, 0)).isEqualTo("DECIMAL(10,2)");
            Assertions.assertThat(dec.getSqlType(0, 18, 4)).isEqualTo("DECIMAL(18,4)");
        }
    }

    @Test
    @DisplayName("정의되지 않은 타입은 TEXT + needsQuotes=true")
    void unknownTypeFallsBackToText() {
        JavaTypeMapper.JavaType unknown = mapper.map("com.foo.Bar");
        Assertions.assertThat(unknown.getSqlType(0,0,0)).isEqualTo("TEXT");
        Assertions.assertThat(unknown.needsQuotes()).isTrue();
    }
}
