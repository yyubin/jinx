package org.jinx.util;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ColumnBuilderFactory")
class ColumnBuilderFactoryTest {

    @Mock
    private VariableElement mockField;
    @Mock
    private TypeMirror mockFieldType;
    @Mock
    private ProcessingContext mockContext;
    @Mock
    private Name mockFieldName;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockField.getSimpleName()).thenReturn(mockFieldName);
        when(mockFieldName.toString()).thenReturn("testField");
        when(mockField.asType()).thenReturn(mockFieldType);
        when(mockFieldType.toString()).thenReturn("java.lang.String");
    }

    @Test
    @DisplayName("기본 필드 정보를 사용하여 빌더를 생성해야 한다")
    void from_withBasicField_createsBuilderWithDefaults() {
        // Arrange: 애너테이션, 오버라이드, 타입 힌트가 없는 가장 기본적인 상황
        String defaultColumnName = "testField";

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, defaultColumnName, mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert
        assertThat(model.getColumnName()).isEqualTo(defaultColumnName);
        assertThat(model.getJavaType()).isEqualTo("java.lang.String");
        assertThat(model.isNullable()).isTrue(); // @Column이 없으면 nullable=true
        assertThat(model.getLength()).isEqualTo(255); // 기본 길이
        assertThat(model.isPrimaryKey()).isFalse();
    }

    @Test
    @DisplayName("@Column 애너테이션 정보를 빌더에 반영해야 한다")
    void from_withColumnAnnotation_reflectsAnnotationValues() {
        // Arrange: @Column 애너테이션이 설정된 필드
        Column mockColumn = mock(Column.class);
        when(mockColumn.name()).thenReturn("custom_name");
        when(mockColumn.nullable()).thenReturn(false);
        when(mockColumn.length()).thenReturn(100);
        when(mockColumn.columnDefinition()).thenReturn("anything");
        when(mockField.getAnnotation(Column.class)).thenReturn(mockColumn);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, "testField", mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert
        assertThat(model.getColumnName()).isEqualTo("custom_name");
        assertThat(model.isNullable()).isFalse();
        assertThat(model.getLength()).isEqualTo(100);
    }

    @Test
    @DisplayName("override 맵에 이름이 있으면 최우선으로 적용해야 한다")
    void from_withOverride_usesOverrideName() {
        // Arrange: @Column 애너테이션과 overrides가 모두 있는 상황
        Column mockColumn = mock(Column.class);
        when(mockColumn.name()).thenReturn("ignored_column_name");
        when(mockField.getAnnotation(Column.class)).thenReturn(mockColumn);
        when(mockColumn.columnDefinition()).thenReturn("ignored_column_definition");
        Map<String, String> overrides = Map.of("testField", "overridden_name");

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, "testField", mockContext, overrides
        );
        ColumnModel model = builder.build();

        // Assert: overrides 맵의 값이 @Column 값보다 우선함
        assertThat(model.getColumnName()).isEqualTo("overridden_name");
    }

    @Test
    @DisplayName("typeHint가 제공되면 field 타입 대신 사용해야 한다")
    void from_withTypeHint_usesHintAsJavaType() {
        // Arrange: 필드 자체의 타입(String)과 다른 타입 힌트(LocalDate)를 제공
        TypeMirror mockTypeHint = mock(TypeMirror.class);
        when(mockTypeHint.toString()).thenReturn("java.time.LocalDate");

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, mockTypeHint, "testField", mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert: javaType이 field.asType()이 아닌 typeHint로 설정됨
        assertThat(model.getJavaType()).isEqualTo("java.time.LocalDate");
    }

    @Test
    @DisplayName("@Id 애너테이션이 있으면 isPrimaryKey를 true로 설정해야 한다")
    void from_withIdAnnotation_setsPrimaryKey() {
        // Arrange: @Id 애너테이션이 있는 필드
        when(mockField.getAnnotation(Id.class)).thenReturn(mock(Id.class));

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, "id", mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert
        assertThat(model.isPrimaryKey()).isTrue();
    }
}