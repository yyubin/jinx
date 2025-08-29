package org.jinx.handler.builtins;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.SequenceHandler;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;
import org.jinx.util.ColumnBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("EntityFieldResolver")
class EntityFieldResolverTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private SequenceHandler sequenceHandler;
    @Mock
    private VariableElement mockField;
    @Mock
    private ColumnModel.ColumnModelBuilder mockBuilder;
    @Mock
    private TypeMirror mockFieldType;

    private MockedStatic<ColumnBuilderFactory> factoryMock;
    private EntityFieldResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factoryMock = Mockito.mockStatic(ColumnBuilderFactory.class);
        factoryMock.when(() -> ColumnBuilderFactory.from(any(), any(), any(), any(), any()))
                .thenReturn(mockBuilder);
        lenient().when(mockBuilder.build()).thenReturn(mock(ColumnModel.class));
        lenient().when(mockBuilder.fetchType(any())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.isOptional(anyBoolean())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.isVersion(anyBoolean())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.conversionClass(anyString())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.generationStrategy(any())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.sequenceName(anyString())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.tableGeneratorName(anyString())).thenReturn(mockBuilder);

        resolver = new EntityFieldResolver(context, sequenceHandler);

        Name mockName = mock(Name.class);
        when(mockField.getSimpleName()).thenReturn(mockName);
        when(mockName.toString()).thenReturn("testField");

        lenient().when(mockField.asType()).thenReturn(mockFieldType);
        lenient().when(mockFieldType.toString()).thenReturn("java.lang.Object");
    }

    @AfterEach
    void tearDown() {
        factoryMock.close();
    }

    @Test
    @DisplayName("@Basic 애너테이션을 처리하여 fetch 타입과 optional을 설정해야 한다")
    void resolve_shouldHandleBasicAnnotation() {
        // Arrange
        Basic basic = mock(Basic.class);
        when(basic.fetch()).thenReturn(FetchType.LAZY);
        when(basic.optional()).thenReturn(false);
        when(mockField.getAnnotation(Basic.class)).thenReturn(basic);

        // Act
        resolver.resolve(mockField, null, "testField", Collections.emptyMap());

        // Assert
        verify(mockBuilder).fetchType(FetchType.LAZY);
        verify(mockBuilder).isOptional(false);
    }

    @Test
    @DisplayName("@Version 애너테이션을 처리하여 isVersion을 true로 설정해야 한다")
    void resolve_shouldHandleVersionAnnotation() {
        // Arrange
        when(mockField.getAnnotation(Version.class)).thenReturn(mock(Version.class));

        // Act
        resolver.resolve(mockField, null, "testField", Collections.emptyMap());

        // Assert
        verify(mockBuilder).isVersion(true);
    }

    @Test
    @DisplayName("필드 레벨 @Convert 애너테이션을 처리해야 한다")
    void resolve_shouldHandleFieldLevelConvertAnnotation() {
        // Arrange
        Convert convert = mock(Convert.class);
        // 복잡한 TypeMirror 모의를 피하기 위해 간단한 클래스 사용
        class DummyConverter {}
        when(convert.converter()).thenAnswer(invocation -> DummyConverter.class);
        when(mockField.getAnnotation(Convert.class)).thenReturn(convert);

        // Act
        resolver.resolve(mockField, null, "testField", Collections.emptyMap());

        // Assert
        verify(mockBuilder).conversionClass(DummyConverter.class.getName());
    }

    @Test
    @DisplayName("Auto-apply @Converter를 처리해야 한다")
    void resolve_shouldHandleAutoApplyConverter() {
        // Arrange
        when(mockField.getAnnotation(Convert.class)).thenReturn(null); // 필드 레벨 컨버터는 없음
        when(mockField.asType()).thenReturn(mockFieldType);
        when(mockFieldType.toString()).thenReturn("com.example.MyType");
        when(context.getAutoApplyConverters()).thenReturn(Map.of("com.example.MyType", "com.example.MyTypeConverter"));

        // Act
        resolver.resolve(mockField, null, "testField", Collections.emptyMap());

        // Assert
        verify(mockBuilder).conversionClass("com.example.MyTypeConverter");
    }

    @Test
    @DisplayName("@GeneratedValue(SEQUENCE)를 처리하고 SequenceHandler를 호출해야 한다")
    void resolve_shouldHandleGeneratedValueSequence() {
        // Arrange
        GeneratedValue gv = mock(GeneratedValue.class);
        when(gv.strategy()).thenReturn(GenerationType.SEQUENCE);
        when(gv.generator()).thenReturn("my_seq_gen");
        when(mockField.getAnnotation(GeneratedValue.class)).thenReturn(gv);

        SequenceGenerator sg = mock(SequenceGenerator.class);
        when(mockField.getAnnotation(SequenceGenerator.class)).thenReturn(sg);

        // Act
        resolver.resolve(mockField, null, "id", Collections.emptyMap());

        // Assert
        verify(mockBuilder).generationStrategy(GenerationStrategy.SEQUENCE);
        verify(mockBuilder).sequenceName("my_seq_gen");
        verify(sequenceHandler).processSingleGenerator(sg, mockField);
    }

    @Test
    @DisplayName("@GeneratedValue(SEQUENCE)에 generator가 없으면 에러를 기록하고 null을 반환해야 한다")
    void resolve_shouldReturnNullAndLogErrorWhenSequenceGeneratorIsMissing() {
        // Arrange
        GeneratedValue gv = mock(GeneratedValue.class);
        when(gv.strategy()).thenReturn(GenerationType.SEQUENCE);
        when(gv.generator()).thenReturn(""); // 비어있는 generator 이름

        when(mockField.getAnnotation(GeneratedValue.class)).thenReturn(gv);

        Messager messager = mock(Messager.class);
        when(context.getMessager()).thenReturn(messager);

        // Act
        ColumnModel result = resolver.resolve(mockField, null, "id", Collections.emptyMap());

        // Assert
        assertThat(result).isNull();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), eq(mockField));
    }
}