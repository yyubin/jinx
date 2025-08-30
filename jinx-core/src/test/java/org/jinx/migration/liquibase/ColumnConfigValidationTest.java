package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.ColumnConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ColumnConfigValidationTest {

    @Nested
    @DisplayName("defaultValueComputed 우선 검증")
    class ComputedDefaultTests {

        @Test
        @DisplayName("computed만 지정하면 유효해야 한다")
        void validComputedOnly() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("id")
                    .type("varchar(36)")
                    .defaultValueComputed("uuid()")
                    .build();

            assertAll(
                    () -> assertEquals("uuid()", config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }

        @Test
        @DisplayName("computed가 literal을 덮어쓴다")
        void computedOverridesLiteral() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("varchar(36)")
                    .defaultValueComputed("uuid()")
                    .defaultValue("should_be_ignored")
                    .build();

            assertAll(
                    () -> assertEquals("uuid()", config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }

        @Test
        @DisplayName("computed가 sequence를 덮어쓴다 (computed 먼저 설정)")
        void computedOverridesSequence_ComputedFirst() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("varchar(36)")
                    .defaultValueComputed("uuid()")
                    .defaultValueSequenceNext("should_be_ignored")
                    .build();

            assertAll(
                    () -> assertEquals("uuid()", config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }

        @Test
        @DisplayName("computed가 sequence를 덮어쓴다 (sequence 먼저 설정)")
        void computedOverridesSequence_SequenceFirst() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("varchar(36)")
                    .defaultValueSequenceNext("should_be_ignored")
                    .defaultValueComputed("uuid()")
                    .build();

            assertAll(
                    () -> assertEquals("uuid()", config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }
    }

    @Nested
    @DisplayName("literal/sequence 기본값 검증")
    class LiteralAndSequenceTests {

        @Test
        @DisplayName("literal만 지정하면 유효해야 한다")
        void validLiteralOnly() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("status")
                    .type("varchar(20)")
                    .defaultValue("ACTIVE")
                    .build();

            assertAll(
                    () -> assertEquals("ACTIVE", config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }

        @Test
        @DisplayName("sequence가 literal을 덮어쓴다")
        void sequenceOverridesLiteral() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("varchar(36)")
                    .defaultValue("literal")
                    .defaultValueSequenceNext("seq")
                    .build();

            assertAll(
                    () -> assertEquals("seq", config.getDefaultValueSequenceNext()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueComputed())
            );
        }

        @Test
        @DisplayName("sequence는 computed에 의해 무시된다")
        void sequenceIgnoredByComputed() {
            ColumnConfig config = ColumnConfig.builder()
                    .name("test")
                    .type("varchar(36)")
                    .defaultValueSequenceNext("should_be_ignored")
                    .defaultValueComputed("uuid()")
                    .build();

            assertAll(
                    () -> assertEquals("uuid()", config.getDefaultValueComputed()),
                    () -> assertNull(config.getDefaultValue()),
                    () -> assertNull(config.getDefaultValueSequenceNext())
            );
        }
    }
}
