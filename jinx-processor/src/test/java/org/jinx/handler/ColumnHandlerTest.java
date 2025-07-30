package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.annotation.Identity;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.model.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ColumnHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private SequenceHandler sequenceHandler;
    @Mock
    private VariableElement fieldElement;
    @Mock
    private Name fieldName;
    @Mock
    private TypeMirror fieldType;
    @Mock
    private Messager messager;

    private ColumnHandler columnHandler;

    @BeforeEach
    void setUp() {
        // Initialize mocks created with @Mock annotation
        MockitoAnnotations.openMocks(this);
        columnHandler = new ColumnHandler(context, sequenceHandler);

        // Common mock setup
        when(fieldElement.getSimpleName()).thenReturn(fieldName);
        when(fieldName.toString()).thenReturn("testField");
        when(fieldElement.asType()).thenReturn(fieldType);
        when(fieldType.toString()).thenReturn("java.lang.String");
        when(context.getMessager()).thenReturn(messager);
    }

    @Test
    @DisplayName("Should create a basic ColumnModel with default values")
    void createFrom_WithSimpleField_ReturnsDefaultColumnModel() {
        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getColumnName()).isEqualTo("testField");
        assertThat(result.getJavaType()).isEqualTo("java.lang.String");
        assertThat(result.isPrimaryKey()).isFalse();
        assertThat(result.isNullable()).isTrue();
        assertThat(result.isUnique()).isFalse();
        assertThat(result.getLength()).isEqualTo(255);
        assertThat(result.getGenerationStrategy()).isEqualTo(GenerationStrategy.NONE);
    }

    @Test
    @DisplayName("Should correctly handle @Id annotation")
    void createFrom_WithIdAnnotation_SetsPrimaryKey() {
        // Given
        Id idAnnotation = mock(Id.class);
        when(fieldElement.getAnnotation(Id.class)).thenReturn(idAnnotation);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.isPrimaryKey()).isTrue();
        assertThat(result.isManualPrimaryKey()).isTrue();
    }

    @Test
    @DisplayName("Should correctly handle @Column annotation properties")
    void createFrom_WithColumnAnnotation_AppliesProperties() {
        // Given
        Column columnAnnotation = mock(Column.class);
        when(columnAnnotation.name()).thenReturn("custom_name");
        when(columnAnnotation.nullable()).thenReturn(false);
        when(columnAnnotation.unique()).thenReturn(true);
        when(columnAnnotation.length()).thenReturn(500);
        when(columnAnnotation.precision()).thenReturn(10);
        when(columnAnnotation.scale()).thenReturn(2);
        when(columnAnnotation.columnDefinition()).thenReturn("");
        when(fieldElement.getAnnotation(Column.class)).thenReturn(columnAnnotation);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.getColumnName()).isEqualTo("custom_name");
        assertThat(result.isNullable()).isFalse();
        assertThat(result.isUnique()).isTrue();
        assertThat(result.getLength()).isEqualTo(500);
        assertThat(result.getPrecision()).isEqualTo(10);
        assertThat(result.getScale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should prioritize override map for column name")
    void createFrom_WithOverrideMap_UsesOverrideName() {
        // Given
        Column columnAnnotation = mock(Column.class);
        when(columnAnnotation.name()).thenReturn("annotation_name");
        when(columnAnnotation.columnDefinition()).thenReturn("");
        when(fieldElement.getAnnotation(Column.class)).thenReturn(columnAnnotation);
        Map<String, String> overrides = Map.of("testField", "override_name");

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, overrides);

        // Then
        assertThat(result.getColumnName()).isEqualTo("override_name");
    }

    @Test
    @DisplayName("Should correctly handle @Enumerated(STRING)")
    void createFrom_WithEnumeratedString_SetsEnumProperties() {
        // Given
        Enumerated enumeratedAnnotation = mock(Enumerated.class);
        when(enumeratedAnnotation.value()).thenReturn(EnumType.STRING);
        when(fieldElement.getAnnotation(Enumerated.class)).thenReturn(enumeratedAnnotation);

        // Mocking the enum type
        DeclaredType enumType = mock(DeclaredType.class);
        Element enumElement = mock(Element.class);
        Element constant1 = mock(Element.class);
        Element constant2 = mock(Element.class);
        Name const1Name = mock(Name.class);
        Name const2Name = mock(Name.class);

        when(fieldElement.asType()).thenReturn(enumType);
        when(enumType.asElement()).thenReturn(enumElement);
        when(enumElement.getKind()).thenReturn(ElementKind.ENUM);
        doReturn(List.of(constant1, constant2)).when(enumElement).getEnclosedElements();
        when(constant1.getKind()).thenReturn(ElementKind.ENUM_CONSTANT);
        when(constant2.getKind()).thenReturn(ElementKind.ENUM_CONSTANT);
        when(constant1.getSimpleName()).thenReturn(const1Name);
        when(constant2.getSimpleName()).thenReturn(const2Name);
        when(const1Name.toString()).thenReturn("VALUE_A");
        when(const2Name.toString()).thenReturn("VALUE_B");

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.isEnumStringMapping()).isTrue();
        assertThat(result.getEnumValues()).containsExactly("VALUE_A", "VALUE_B");
    }

    @Test
    @DisplayName("Should correctly handle @Lob for CLOB type")
    void createFrom_WithLobAndStringType_SetsClob() {
        // Given
        Lob lobAnnotation = mock(Lob.class);
        when(fieldElement.getAnnotation(Lob.class)).thenReturn(lobAnnotation);
        when(fieldType.toString()).thenReturn("java.lang.String");

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.isLob()).isTrue();
        assertThat(result.getJdbcType()).isEqualTo(JdbcType.CLOB);
    }

    @Test
    @DisplayName("Should correctly handle @Lob for BLOB type")
    void createFrom_WithLobAndByteArrayType_SetsBlob() {
        // Given
        Lob lobAnnotation = mock(Lob.class);
        when(fieldElement.getAnnotation(Lob.class)).thenReturn(lobAnnotation);
        when(fieldType.toString()).thenReturn("[B"); // byte[]

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.isLob()).isTrue();
        assertThat(result.getJdbcType()).isEqualTo(JdbcType.BLOB);
    }

    @Test
    @DisplayName("Should correctly handle @GeneratedValue(IDENTITY)")
    void createFrom_WithGeneratedValueIdentity_SetsStrategy() {
        // Given
        GeneratedValue gv = mock(GeneratedValue.class);
        when(gv.strategy()).thenReturn(GenerationType.IDENTITY);
        when(fieldElement.getAnnotation(GeneratedValue.class)).thenReturn(gv);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.getGenerationStrategy()).isEqualTo(GenerationStrategy.IDENTITY);
    }

    @Test
    @DisplayName("Should correctly handle @Identity annotation")
    void createFrom_WithCustomIdentityAnnotation_SetsIdentityProperties() {
        // Given
        Identity identity = mock(Identity.class);
        when(identity.start()).thenReturn(100L);
        when(identity.increment()).thenReturn(5);
        when(identity.cache()).thenReturn(20);
        when(identity.min()).thenReturn(1L);
        when(identity.max()).thenReturn(1000L);
        when(identity.options()).thenReturn(new String[]{"CYCLE"});
        when(fieldElement.getAnnotation(Identity.class)).thenReturn(identity);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.getGenerationStrategy()).isEqualTo(GenerationStrategy.IDENTITY);
        assertThat(result.getIdentityStartValue()).isEqualTo(100L);
        assertThat(result.getIdentityIncrement()).isEqualTo(5L);
        assertThat(result.getIdentityCache()).isEqualTo(20);
        assertThat(result.getIdentityMinValue()).isEqualTo(1L);
        assertThat(result.getIdentityMaxValue()).isEqualTo(1000L);
        assertThat(result.getIdentityOptions()).containsExactly("CYCLE");
    }

    @Test
    @DisplayName("Should correctly handle @GeneratedValue(SEQUENCE)")
    void createFrom_WithGeneratedValueSequence_SetsStrategyAndCallsHandler() {
        // Given
        GeneratedValue gv = mock(GeneratedValue.class);
        when(gv.strategy()).thenReturn(GenerationType.SEQUENCE);
        when(gv.generator()).thenReturn("my_seq_gen");
        when(fieldElement.getAnnotation(GeneratedValue.class)).thenReturn(gv);

        SequenceGenerator sg = mock(SequenceGenerator.class);
        when(fieldElement.getAnnotation(SequenceGenerator.class)).thenReturn(sg);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result.getGenerationStrategy()).isEqualTo(GenerationStrategy.SEQUENCE);
        assertThat(result.getSequenceName()).isEqualTo("my_seq_gen");
        // Verify that the sequence handler was called to process the generator definition
        verify(sequenceHandler).processSingleGenerator(sg, fieldElement);
    }

    @Test
    @DisplayName("Should report error if @GeneratedValue(SEQUENCE) has no generator name")
    void createFrom_WithSequenceAndNoGenerator_ReportsError() {
        // Given
        GeneratedValue gv = mock(GeneratedValue.class);
        when(gv.strategy()).thenReturn(GenerationType.SEQUENCE);
        when(gv.generator()).thenReturn(""); // Blank generator name
        when(fieldElement.getAnnotation(GeneratedValue.class)).thenReturn(gv);

        // When
        ColumnModel result = columnHandler.createFrom(fieldElement, Collections.emptyMap());

        // Then
        assertThat(result).isNull();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), eq(fieldElement));
    }
}
