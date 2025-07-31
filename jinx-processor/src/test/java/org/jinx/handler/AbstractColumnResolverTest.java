package org.jinx.handler;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.JdbcType;
import org.jinx.util.ColumnUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

import static org.mockito.Mockito.*;

@DisplayName("AbstractColumnResolver")
class AbstractColumnResolverTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private VariableElement mockField;
    @Mock
    private TypeMirror mockType;
    @Mock
    private ColumnModel.ColumnModelBuilder mockBuilder;

    private TestableColumnResolver resolver;
    private MockedStatic<ColumnUtils> columnUtilsMockedStatic;

    static class TestableColumnResolver extends AbstractColumnResolver {
        TestableColumnResolver(ProcessingContext context) {
            super(context);
        }

        @Override
        public void applyCommonAnnotations(ColumnModel.ColumnModelBuilder builder, VariableElement field, TypeMirror type) {
            super.applyCommonAnnotations(builder, field, type);
        }

        @Override
        public ColumnModel resolve(VariableElement field, TypeMirror typeHint, String columnName, Map<String, String> overrides) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new TestableColumnResolver(context);
        columnUtilsMockedStatic = Mockito.mockStatic(ColumnUtils.class);
    }

    @AfterEach
    void tearDown() {
        // static mock은 매 테스트 후 해제해야 함
        columnUtilsMockedStatic.close();
    }

    @Test
    @DisplayName("@Lob 애너테이션이 String 타입에 있으면 CLOB으로 처리해야 한다")
    void applyCommonAnnotations_shouldHandleLobOnStringAsClob() {
        // Arrange
        when(mockField.getAnnotation(Lob.class)).thenReturn(mock(Lob.class));
        when(mockType.toString()).thenReturn("java.lang.String");

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder).isLob(true);
        verify(mockBuilder).jdbcType(JdbcType.CLOB);
    }

    @Test
    @DisplayName("@Lob 애너테이션이 byte[] 타입에 있으면 BLOB으로 처리해야 한다")
    void applyCommonAnnotations_shouldHandleLobOnByteArrayAsBlob() {
        // Arrange
        when(mockField.getAnnotation(Lob.class)).thenReturn(mock(Lob.class));
        when(mockType.toString()).thenReturn("byte[]"); // String이 아닌 타입

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder).isLob(true);
        verify(mockBuilder).jdbcType(JdbcType.BLOB);
    }

    @Test
    @DisplayName("@Enumerated(STRING)이 있으면 enumValues를 설정해야 한다")
    void applyCommonAnnotations_shouldHandleEnumeratedString() {
        // Arrange
        Enumerated enumerated = mock(Enumerated.class);
        when(enumerated.value()).thenReturn(EnumType.STRING);
        when(mockField.getAnnotation(Enumerated.class)).thenReturn(enumerated);

        String[] enumConstants = {"A", "B"};
        columnUtilsMockedStatic.when(() -> ColumnUtils.getEnumConstants(mockType)).thenReturn(enumConstants);

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder).enumStringMapping(true);
        verify(mockBuilder).enumValues(enumConstants);
    }

    @Test
    @DisplayName("@Enumerated(ORDINAL)이 있으면 enumValues를 설정하지 않아야 한다")
    void applyCommonAnnotations_shouldHandleEnumeratedOrdinal() {
        // Arrange
        Enumerated enumerated = mock(Enumerated.class);
        when(enumerated.value()).thenReturn(EnumType.ORDINAL);
        when(mockField.getAnnotation(Enumerated.class)).thenReturn(enumerated);
        // isStringMapping이 false이므로 getEnumConstants는 호출되지 않아야 함

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder).enumStringMapping(false);
        verify(mockBuilder, never()).enumValues(any());
    }

    @Test
    @DisplayName("@Temporal 애너테이션이 있으면 temporalType을 설정해야 한다")
    void applyCommonAnnotations_shouldHandleTemporal() {
        // Arrange
        Temporal temporal = mock(Temporal.class);
        when(temporal.value()).thenReturn(TemporalType.DATE);
        when(mockField.getAnnotation(Temporal.class)).thenReturn(temporal);

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder).temporalType(TemporalType.DATE);
    }

    @Test
    @DisplayName("관련 애너테이션이 없으면 아무것도 설정하지 않아야 한다")
    void applyCommonAnnotations_withNoAnnotations_shouldDoNothing() {
        // Arrange: 모든 getAnnotation 호출이 null을 반환하도록 설정 (Mockito의 기본 동작)

        // Act
        resolver.applyCommonAnnotations(mockBuilder, mockField, mockType);

        // Assert
        verify(mockBuilder, never()).isLob(anyBoolean());
        verify(mockBuilder, never()).jdbcType(any());
        verify(mockBuilder, never()).enumStringMapping(anyBoolean());
        verify(mockBuilder, never()).enumValues(any());
        verify(mockBuilder, never()).temporalType(any());
    }
}