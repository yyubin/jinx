package org.jinx.processor;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.EntityHandler;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("JpaSqlGeneratorProcessor 커버리지 추가 테스트")
class JpaSqlGeneratorProcessorCoverageTest {

    @Test
    @DisplayName("processRetryTasks 호출 검증")
    void testProcessRetryTasks() {
        // GIVEN
        JpaSqlGeneratorProcessor processor = new JpaSqlGeneratorProcessor();
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);
        
        when(processingEnv.getElementUtils()).thenReturn(elements);
        when(processingEnv.getTypeUtils()).thenReturn(types);
        when(processingEnv.getMessager()).thenReturn(mock());
        
        processor.init(processingEnv);
        
        // EntityHandler를 mock으로 교체
        EntityHandler mockHandler = mock(EntityHandler.class);
        try {
            java.lang.reflect.Field field = JpaSqlGeneratorProcessor.class.getDeclaredField("entityHandler");
            field.setAccessible(true);
            field.set(processor, mockHandler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        // WHEN
        processor.processRetryTasks();
        
        // THEN
        verify(mockHandler, times(1)).runDeferredPostProcessing();
    }
    
    @Test
    @DisplayName("init 메서드 모든 필드 초기화 검증")
    void testInitInitializesAllFields() throws Exception {
        // GIVEN
        JpaSqlGeneratorProcessor processor = new JpaSqlGeneratorProcessor();
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);
        
        when(processingEnv.getElementUtils()).thenReturn(elements);
        when(processingEnv.getTypeUtils()).thenReturn(types);
        when(processingEnv.getMessager()).thenReturn(mock());
        
        // WHEN
        processor.init(processingEnv);
        
        // THEN
        assertThat(getField(processor, "context")).isNotNull();
        assertThat(getField(processor, "entityHandler")).isNotNull();
        assertThat(getField(processor, "relationshipHandler")).isNotNull();
        assertThat(getField(processor, "inheritanceHandler")).isNotNull();
        assertThat(getField(processor, "sequenceHandler")).isNotNull();
        assertThat(getField(processor, "embeddedHandler")).isNotNull();
        assertThat(getField(processor, "constraintHandler")).isNotNull();
        assertThat(getField(processor, "elementCollectionHandler")).isNotNull();
        assertThat(getField(processor, "tableGeneratorHandler")).isNotNull();
    }
    
    @Test
    @DisplayName("process에서 context.beginRound 호출 검증")
    void testProcessCallsBeginRound() throws Exception {
        // GIVEN
        JpaSqlGeneratorProcessor processor = new JpaSqlGeneratorProcessor();
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);
        
        when(processingEnv.getElementUtils()).thenReturn(elements);
        when(processingEnv.getTypeUtils()).thenReturn(types);
        when(processingEnv.getMessager()).thenReturn(mock());
        
        processor.init(processingEnv);
        
        // Mock context
        ProcessingContext mockContext = mock(ProcessingContext.class);
        SchemaModel schemaModel = mock(SchemaModel.class);
        when(mockContext.getSchemaModel()).thenReturn(schemaModel);
        when(schemaModel.getEntities()).thenReturn(Collections.emptyMap());
        
        setField(processor, "context", mockContext);
        
        when(roundEnv.processingOver()).thenReturn(false);
        when(roundEnv.getElementsAnnotatedWith(any(Class.class))).thenReturn(Collections.emptySet());
        
        // WHEN
        processor.process(Collections.emptySet(), roundEnv);
        
        // THEN
        verify(mockContext, times(1)).beginRound();
    }
    
    private Object getField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
    
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
