package org.jinx.util;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.spi.naming.JinxNamingStrategy;
import org.jinx.spi.naming.impl.NoOpNamingStrategy;
import org.jinx.spi.naming.impl.SnakeCaseNamingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @Test
    @DisplayName("@Column(columnDefinition)이 있으면 sqlTypeOverride로 설정해야 한다")
    void from_withColumnDefinition_setsSqlTypeOverride() {
        // Arrange: @Column(columnDefinition) 애너테이션이 있는 필드
        Column mockColumn = mock(Column.class);
        when(mockColumn.columnDefinition()).thenReturn("varchar(42)");
        when(mockColumn.name()).thenReturn("");
        when(mockField.getAnnotation(Column.class)).thenReturn(mockColumn);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, "testField", mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert
        assertThat(model.getSqlTypeOverride()).isEqualTo("varchar(42)");
        assertThat(model.getDefaultValue()).isNull(); // defaultValue는 null이어야 함
    }

    @Test
    @DisplayName("@Column(columnDefinition)이 빈 문자열이면 sqlTypeOverride를 null로 설정해야 한다")
    void from_withEmptyColumnDefinition_setsNullSqlTypeOverride() {
        // Arrange: @Column(columnDefinition="") 애너테이션이 있는 필드
        Column mockColumn = mock(Column.class);
        when(mockColumn.columnDefinition()).thenReturn("");
        when(mockColumn.name()).thenReturn("");
        when(mockField.getAnnotation(Column.class)).thenReturn(mockColumn);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.from(
                mockField, null, "testField", mockContext, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert
        assertThat(model.getSqlTypeOverride()).isNull();
        assertThat(model.getDefaultValue()).isNull();
    }

    @Test
    @DisplayName("NamingStrategy - NoOpNamingStrategy는 카멜케이스를 그대로 유지해야 한다")
    void fromAttributeDescriptor_withNoOpNamingStrategy_preservesCamelCase() {
        // Arrange: NoOpNamingStrategy를 사용하는 ProcessingContext
        ProcessingContext contextWithNoOp = mock(ProcessingContext.class);
        when(contextWithNoOp.getNamingStrategy()).thenReturn(new NoOpNamingStrategy());

        AttributeDescriptor mockAttribute = mock(AttributeDescriptor.class);
        when(mockAttribute.name()).thenReturn("maxLevel");
        when(mockAttribute.type()).thenReturn(mockFieldType);
        when(mockAttribute.getAnnotation(Column.class)).thenReturn(null);
        when(mockAttribute.hasAnnotation(Id.class)).thenReturn(false);
        when(mockAttribute.hasAnnotation(jakarta.persistence.EmbeddedId.class)).thenReturn(false);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                mockAttribute, null, null, contextWithNoOp, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert: 카멜케이스가 그대로 유지됨
        assertThat(model.getColumnName()).isEqualTo("maxLevel");
    }

    @Test
    @DisplayName("NamingStrategy - SnakeCaseNamingStrategy는 카멜케이스를 스네이크케이스로 변환해야 한다")
    void fromAttributeDescriptor_withSnakeCaseNamingStrategy_convertsToSnakeCase() {
        // Arrange: SnakeCaseNamingStrategy를 사용하는 ProcessingContext
        ProcessingContext contextWithSnakeCase = mock(ProcessingContext.class);
        when(contextWithSnakeCase.getNamingStrategy()).thenReturn(new SnakeCaseNamingStrategy());

        AttributeDescriptor mockAttribute = mock(AttributeDescriptor.class);
        when(mockAttribute.name()).thenReturn("maxLevel");
        when(mockAttribute.type()).thenReturn(mockFieldType);
        when(mockAttribute.getAnnotation(Column.class)).thenReturn(null);
        when(mockAttribute.hasAnnotation(Id.class)).thenReturn(false);
        when(mockAttribute.hasAnnotation(jakarta.persistence.EmbeddedId.class)).thenReturn(false);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                mockAttribute, null, null, contextWithSnakeCase, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert: 카멜케이스가 스네이크케이스로 변환됨
        assertThat(model.getColumnName()).isEqualTo("max_level");
    }

    @Test
    @DisplayName("NamingStrategy - @Column.name()이 있으면 NamingStrategy보다 우선해야 한다")
    void fromAttributeDescriptor_withColumnAnnotation_overridesNamingStrategy() {
        // Arrange: SnakeCaseNamingStrategy를 사용하지만 @Column.name()이 설정됨
        ProcessingContext contextWithSnakeCase = mock(ProcessingContext.class);
        when(contextWithSnakeCase.getNamingStrategy()).thenReturn(new SnakeCaseNamingStrategy());

        Column mockColumn = mock(Column.class);
        when(mockColumn.name()).thenReturn("CUSTOM_NAME");
        when(mockColumn.nullable()).thenReturn(true);
        when(mockColumn.length()).thenReturn(255);
        when(mockColumn.precision()).thenReturn(0);
        when(mockColumn.scale()).thenReturn(0);
        when(mockColumn.columnDefinition()).thenReturn("");
        when(mockColumn.table()).thenReturn("");

        AttributeDescriptor mockAttribute = mock(AttributeDescriptor.class);
        when(mockAttribute.name()).thenReturn("maxLevel");
        when(mockAttribute.type()).thenReturn(mockFieldType);
        when(mockAttribute.getAnnotation(Column.class)).thenReturn(mockColumn);
        when(mockAttribute.hasAnnotation(Id.class)).thenReturn(false);
        when(mockAttribute.hasAnnotation(jakarta.persistence.EmbeddedId.class)).thenReturn(false);

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                mockAttribute, null, null, contextWithSnakeCase, Collections.emptyMap()
        );
        ColumnModel model = builder.build();

        // Assert: @Column.name()이 NamingStrategy보다 우선함
        assertThat(model.getColumnName()).isEqualTo("CUSTOM_NAME");
    }

    @Test
    @DisplayName("NamingStrategy - overrides가 있으면 NamingStrategy와 @Column.name()보다 우선해야 한다")
    void fromAttributeDescriptor_withOverride_overridesNamingStrategyAndColumn() {
        // Arrange: SnakeCaseNamingStrategy와 @Column.name()이 있지만 overrides가 설정됨
        ProcessingContext contextWithSnakeCase = mock(ProcessingContext.class);
        when(contextWithSnakeCase.getNamingStrategy()).thenReturn(new SnakeCaseNamingStrategy());

        Column mockColumn = mock(Column.class);
        when(mockColumn.name()).thenReturn("IGNORED_NAME");
        when(mockColumn.nullable()).thenReturn(true);
        when(mockColumn.length()).thenReturn(255);
        when(mockColumn.precision()).thenReturn(0);
        when(mockColumn.scale()).thenReturn(0);
        when(mockColumn.columnDefinition()).thenReturn("");
        when(mockColumn.table()).thenReturn("");

        AttributeDescriptor mockAttribute = mock(AttributeDescriptor.class);
        when(mockAttribute.name()).thenReturn("maxLevel");
        when(mockAttribute.type()).thenReturn(mockFieldType);
        when(mockAttribute.getAnnotation(Column.class)).thenReturn(mockColumn);
        when(mockAttribute.hasAnnotation(Id.class)).thenReturn(false);
        when(mockAttribute.hasAnnotation(jakarta.persistence.EmbeddedId.class)).thenReturn(false);

        Map<String, String> overrides = Map.of("maxLevel", "OVERRIDE_NAME");

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                mockAttribute, null, null, contextWithSnakeCase, overrides
        );
        ColumnModel model = builder.build();

        // Assert: overrides가 모든 것보다 우선함
        assertThat(model.getColumnName()).isEqualTo("OVERRIDE_NAME");
    }

    @Test
    @DisplayName("NamingStrategy - explicit columnName이 있으면 최우선으로 적용해야 한다")
    void fromAttributeDescriptor_withExplicitColumnName_takesHighestPriority() {
        // Arrange: 모든 네이밍 소스가 있지만 explicit columnName이 제공됨
        ProcessingContext contextWithSnakeCase = mock(ProcessingContext.class);
        when(contextWithSnakeCase.getNamingStrategy()).thenReturn(new SnakeCaseNamingStrategy());

        Column mockColumn = mock(Column.class);
        when(mockColumn.name()).thenReturn("IGNORED_NAME");
        when(mockColumn.nullable()).thenReturn(true);
        when(mockColumn.length()).thenReturn(255);
        when(mockColumn.precision()).thenReturn(0);
        when(mockColumn.scale()).thenReturn(0);
        when(mockColumn.columnDefinition()).thenReturn("");
        when(mockColumn.table()).thenReturn("");

        AttributeDescriptor mockAttribute = mock(AttributeDescriptor.class);
        when(mockAttribute.name()).thenReturn("maxLevel");
        when(mockAttribute.type()).thenReturn(mockFieldType);
        when(mockAttribute.getAnnotation(Column.class)).thenReturn(mockColumn);
        when(mockAttribute.hasAnnotation(Id.class)).thenReturn(false);
        when(mockAttribute.hasAnnotation(jakarta.persistence.EmbeddedId.class)).thenReturn(false);

        Map<String, String> overrides = Map.of("maxLevel", "IGNORED_OVERRIDE");

        // Act
        ColumnModel.ColumnModelBuilder builder = ColumnBuilderFactory.fromAttributeDescriptor(
                mockAttribute, null, "EXPLICIT_NAME", contextWithSnakeCase, overrides
        );
        ColumnModel model = builder.build();

        // Assert: explicit columnName이 최우선
        assertThat(model.getColumnName()).isEqualTo("EXPLICIT_NAME");
    }
}