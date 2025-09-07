package org.jinx.processor;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.EntityHandler;
import org.jinx.handler.InheritanceHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaSqlGeneratorProcessorTest {

    // @InjectMocks: 테스트 대상 클래스. @Mock 객체들을 주입받으려 시도합니다.
    // @Spy: 실제 객체를 사용하되, 일부 메소드의 행동을 제어(stub)할 수 있습니다.
    @Spy
    @InjectMocks
    private JpaSqlGeneratorProcessor processor;

    // --- Mock 객체들 ---
    @Mock
    private ProcessingEnvironment processingEnv;
    @Mock
    private RoundEnvironment roundEnv;
    @Mock
    private Elements elements;
    @Mock
    private Types types;
    @Mock
    private Messager messager;

    // `init` 메소드 내에서 생성되는 객체들을 테스트에서 제어하기 위한 Mock 객체들
    @Mock
    private ProcessingContext context;
    @Mock
    private EntityHandler entityHandler;
    @Mock
    private InheritanceHandler inheritanceHandler;


    @BeforeEach
    void setUp() throws Exception {
        // Mockito가 만든 @Mock 객체들을 반환하도록 설정
        when(processingEnv.getElementUtils()).thenReturn(elements);
        when(processingEnv.getTypeUtils()).thenReturn(types);
        when(processingEnv.getMessager()).thenReturn(messager);

        // processor.init()을 먼저 호출하여 실제 핸들러들이 생성되게 함
        processor.init(processingEnv);

        // --- 리플렉션을 사용하여 내부 private 필드를 Mock 객체로 교체 ---
        // 이유: JpaSqlGeneratorProcessor가 내부에서 `new`로 직접 의존성을 생성하기 때문에,
        // 테스트에서 이들의 행동을 제어하려면 Mock 객체로 강제 교체가 필요합니다.
        setField(processor, "context", context);
        setField(processor, "entityHandler", entityHandler);
        setField(processor, "inheritanceHandler", inheritanceHandler);
    }

    @Test
    @DisplayName("init() 메소드는 모든 핸들러 필드를 초기화해야 한다")
    void init_ShouldInitializeAllHandlers() throws Exception {
        // GIVEN: 테스트 대상 객체와 리플렉션으로 접근할 필드 목록
        JpaSqlGeneratorProcessor freshProcessor = new JpaSqlGeneratorProcessor();

        // WHEN: init 메소드 호출
        freshProcessor.init(processingEnv);

        // THEN: 모든 핸들러 필드들이 null이 아닌지 검증
        assertThat(getField(freshProcessor, "context")).isNotNull();
        assertThat(getField(freshProcessor, "entityHandler")).isNotNull();
        assertThat(getField(freshProcessor, "relationshipHandler")).isNotNull();
        assertThat(getField(freshProcessor, "inheritanceHandler")).isNotNull();
        assertThat(getField(freshProcessor, "sequenceHandler")).isNotNull();
        assertThat(getField(freshProcessor, "embeddedHandler")).isNotNull();
        assertThat(getField(freshProcessor, "constraintHandler")).isNotNull();
        assertThat(getField(freshProcessor, "elementCollectionHandler")).isNotNull();
        assertThat(getField(freshProcessor, "tableGeneratorHandler")).isNotNull();
    }

    @Test
    @DisplayName("라운드 진행 중 @Entity가 발견되면 entityHandler.handle()을 호출해야 한다")
    void process_WhenProcessingEntities_ShouldDelegateToEntityHandler() {
        // GIVEN
        // 1. @Entity 어노테이션이 붙은 클래스 TypeElement 모의 객체 생성
        TypeElement entityElement = mock(TypeElement.class);
        when(entityElement.getKind()).thenReturn(ElementKind.CLASS);

        // 2. roundEnv가 @Entity로 어노테이트된 요소를 요청받으면 위에서 만든 모의 객체를 반환하도록 설정
        doReturn(Set.of(entityElement)).when(roundEnv).getElementsAnnotatedWith(Entity.class);
        when(roundEnv.getElementsAnnotatedWith(Converter.class)).thenReturn(Collections.emptySet());
        when(roundEnv.getElementsAnnotatedWith(MappedSuperclass.class)).thenReturn(Collections.emptySet());
        when(roundEnv.getElementsAnnotatedWith(Embeddable.class)).thenReturn(Collections.emptySet());

        // 3. 아직 라운드가 끝나지 않았다고 설정
        when(roundEnv.processingOver()).thenReturn(false);

        // WHEN: process 메소드 실행
        processor.process(Collections.emptySet(), roundEnv);

        // THEN: entityHandler의 handle 메소드가 entityElement를 인자로 받아 정확히 1번 호출되었는지 검증
        verify(entityHandler, times(1)).handle(entityElement);
    }

    @Test
    @DisplayName("마지막 라운드에서 processingOver가 true이면 최종 처리 작업을 수행해야 한다")
    void process_WhenProcessingOver_ShouldPerformFinalizationTasks() {
        // GIVEN
        // 1. 마지막 라운드임을 설정
        when(roundEnv.processingOver()).thenReturn(true);
        // 2. PK 검증 로직에서 엔티티가 없어서 조기 종료되는 것을 방지
        when(context.getSchemaModel()).thenReturn(mock());
        when(context.getSchemaModel().getEntities()).thenReturn(Map.of("some.Entity", mock()));
        when(context.getSchemaModel().getEntities().get("some.Entity").isValid()).thenReturn(true);
        when(context.getMessager()).thenReturn(mock(Messager.class));
        when(context.getSchemaModel().getEntities().get("some.Entity").getFqcn()).thenReturn("some.Entity");

        // 3. Elements 유틸리티 모의 설정
        when(context.getElementUtils()).thenReturn(elements);
        when(context.getDeferredEntities()).thenReturn(new LinkedBlockingDeque<>() {});
        when(context.getElementUtils().getTypeElement("some.Entity")).thenReturn(mock(TypeElement.class));

        // WHEN: process 메소드 실행
        processor.process(Collections.emptySet(), roundEnv);

        // THEN: 최종 처리와 관련된 메소드들이 호출되었는지 검증
        // 1. 상속 해석 로직 호출 검증 (상속 해석기는 mock으로 교체했으므로)
        verify(inheritanceHandler, atLeastOnce()).resolveInheritance(any(), any());

        // 2. 지연된 작업 처리 로직 호출 검증
        verify(entityHandler, atLeastOnce()).runDeferredPostProcessing();

        // 3. 최종 결과물 저장 로직 호출 검증
        verify(context, times(1)).saveModelToJson();
    }

    // --- 리플렉션을 위한 헬퍼 메소드들 ---
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}