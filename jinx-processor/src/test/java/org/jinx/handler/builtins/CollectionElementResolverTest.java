package org.jinx.handler.builtins;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.util.ColumnUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CollectionElementResolver")
class CollectionElementResolverTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private VariableElement mockField;
    @Mock
    private TypeMirror mockType;

    private CollectionElementResolver resolver;
    private MockedStatic<ColumnUtils> columnUtilsMockedStatic;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new CollectionElementResolver(context);
        columnUtilsMockedStatic = Mockito.mockStatic(ColumnUtils.class);
    }

    @AfterEach
    void tearDown() {
        // static mock은 매 테스트 후 반드시 해제
        columnUtilsMockedStatic.close();
    }

    @Test
    @DisplayName("애너테이션이 없는 기본 요소는 기본값으로 ColumnModel을 생성해야 한다")
    void resolve_withNoAnnotations_shouldCreateDefaultColumnModel() {
        // Arrange
        when(mockType.toString()).thenReturn("java.lang.String");
        // @Column 애너테이션이 없으므로 getAnnotation은 null을 반환
        when(mockField.getAnnotation(Column.class)).thenReturn(null);

        // Act
        ColumnModel result = resolver.resolve(mockField, mockType, "items", Collections.emptyMap());

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getColumnName()).isEqualTo("items");
        assertThat(result.getJavaType()).isEqualTo("java.lang.String");
        assertThat(result.isPrimaryKey()).isFalse(); // CollectionElementResolver의 핵심 동작
        assertThat(result.isNullable()).isTrue();    // @Column이 없으면 nullable=true
        assertThat(result.getLength()).isEqualTo(255);   // @Column이 없으면 length=255
    }

    @Test
    @DisplayName("@Column 애너테이션이 있으면 해당 속성을 반영해야 한다")
    void resolve_withColumnAnnotation_shouldReflectAttributes() {
        // Arrange
        Column mockColumn = mock(Column.class);
        when(mockColumn.nullable()).thenReturn(false);
        when(mockColumn.unique()).thenReturn(true);
        when(mockColumn.length()).thenReturn(500);
        when(mockColumn.columnDefinition()).thenReturn("anything");
        when(mockField.getAnnotation(Column.class)).thenReturn(mockColumn);
        when(mockType.toString()).thenReturn("java.lang.String");

        // Act
        ColumnModel result = resolver.resolve(mockField, mockType, "items", Collections.emptyMap());

        // Assert
        assertThat(result.isNullable()).isFalse();
        assertThat(result.isUnique()).isTrue();
        assertThat(result.getLength()).isEqualTo(500);
    }

    @Test
    @DisplayName("공통 애너테이션(@Enumerated) 처리를 위해 상위 클래스 메서드를 호출해야 한다")
    void resolve_shouldInvokeSuperclassMethodForCommonAnnotations() {
        // Arrange: @Enumerated(EnumType.STRING) 설정
        Enumerated enumerated = mock(Enumerated.class);
        when(enumerated.value()).thenReturn(EnumType.STRING);
        when(mockField.getAnnotation(Enumerated.class)).thenReturn(enumerated);
        when(mockType.toString()).thenReturn("com.example.Status");

        String[] enumConstants = {"ACTIVE", "INACTIVE"};
        // static 메서드인 ColumnUtils.getEnumConstants 모의 설정
        columnUtilsMockedStatic.when(() -> ColumnUtils.getEnumConstants(mockType)).thenReturn(enumConstants);

        // Act
        ColumnModel result = resolver.resolve(mockField, mockType, "status", Collections.emptyMap());

        // Assert
        assertThat(result.isEnumStringMapping()).isTrue();
        assertThat(result.getEnumValues()).isEqualTo(enumConstants);
    }
}