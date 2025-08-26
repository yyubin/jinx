package org.jinx.processor;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.EntityHandler;
import org.jinx.handler.InheritanceHandler;
import org.jinx.handler.RelationshipHandler;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaSqlGeneratorProcessorTest {

    @Mock ProcessingEnvironment processingEnv;
    @Mock Messager messager;
    @Mock Elements elements;
    @Mock RoundEnvironment roundEnv;
    @Mock Types types;

    JpaSqlGeneratorProcessor processor;

    @BeforeEach
    void setup() {
        lenient().when(processingEnv.getMessager()).thenReturn(messager);
        lenient().when(processingEnv.getElementUtils()).thenReturn(elements);

        processor = new JpaSqlGeneratorProcessor();
        processor.init(processingEnv);
    }

    @Test
    void converter_autoApply_withGeneric_addsMapping() throws Exception {
        TypeElement converterType = mockTypeElement("com.example.MyLocalDateConverter");
        Converter converterAnno = mock(Converter.class);
        when(converterAnno.autoApply()).thenReturn(true);
        when(converterType.getAnnotation(Converter.class)).thenReturn(converterAnno);
        when(converterType.getKind()).thenReturn(ElementKind.CLASS);

        // AttributeConverter<java.time.LocalDate, String>
        DeclaredType attrConv = mock(DeclaredType.class);
        TypeMirror targetType = mockTypeMirror("java.time.LocalDate");
        TypeMirror stringType = mockTypeMirror("java.lang.String");
        doReturn(List.of(targetType, stringType)).when(attrConv).getTypeArguments();

        // 계층 탐색용: 직접 구현 + 상위클래스 없음
        doReturn(List.of(attrConv)).when(converterType).getInterfaces();
        TypeMirror none = mock(TypeMirror.class);
        when(none.getKind()).thenReturn(TypeKind.NONE);
        doReturn(none).when(converterType).getSuperclass();

        // AttributeConverter 심볼/erasure 준비
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);
        when(processingEnv.getElementUtils()).thenReturn(elements);
        when(processingEnv.getTypeUtils()).thenReturn(types);

        TypeElement acElement = mock(TypeElement.class);
        DeclaredType acDeclared = mock(DeclaredType.class);
        when(elements.getTypeElement("jakarta.persistence.AttributeConverter")).thenReturn(acElement);
        when(acElement.asType()).thenReturn(acDeclared);

        // 서로 다른 erasure 두 개 만들기
        TypeMirror attrConvErasure = mock(TypeMirror.class); // types.erasure(attrConv)
        TypeMirror acErasure      = mock(TypeMirror.class);  // types.erasure(acDeclared)
        when(types.erasure(attrConv)).thenReturn(attrConvErasure);
        when(types.erasure(acDeclared)).thenReturn(acErasure);

        // isSameType 인자 매칭을 "서로 다른 두 객체"로 정확히 스텁
        when(types.isSameType(same(attrConvErasure), same(acErasure))).thenReturn(true);

        // 라운드 환경
        doReturn(Set.of(converterType)).when(roundEnv).getElementsAnnotatedWith(Converter.class);
        when(roundEnv.getElementsAnnotatedWith(MappedSuperclass.class)).thenReturn(Set.of());
        when(roundEnv.getElementsAnnotatedWith(Embeddable.class)).thenReturn(Set.of());
        when(roundEnv.getElementsAnnotatedWith(Entity.class)).thenReturn(Set.of());
        when(roundEnv.processingOver()).thenReturn(false);

        DeclaredType converterDeclaredType = mock(DeclaredType.class);
        when(converterType.asType()).thenReturn(converterDeclaredType);
        when(converterDeclaredType.asElement()).thenReturn(converterType);

        TypeMirror converterErasure = mock(TypeMirror.class);
        when(types.erasure(converterDeclaredType)).thenReturn(converterErasure);
        when(types.isSameType(converterErasure, acErasure)).thenReturn(false);

        // 실행
        processor.process(Set.of(), roundEnv);

        // 검증
        ProcessingContext ctx = getPrivate(processor, "context");
        Map<String, String> map = ctx.getAutoApplyConverters();
        assertEquals("com.example.MyLocalDateConverter", map.get("java.time.LocalDate"));

        verify(messager, never()).printMessage(eq(Diagnostic.Kind.WARNING), anyString(), any());
    }



    @Test
    void converter_autoApply_missingGeneric_emitsWarning() {
        // Given: @Converter(autoApply=true), but no generic type args resolved
        TypeElement converterType = mockTypeElement("com.example.BadConverter");
        Converter converterAnno = mock(Converter.class);
        when(converterAnno.autoApply()).thenReturn(true);
        when(converterType.getAnnotation(Converter.class)).thenReturn(converterAnno);
        when(converterType.getKind()).thenReturn(ElementKind.CLASS);

        DeclaredType attrConv = mock(DeclaredType.class);
        lenient().when(attrConv.toString()).thenReturn("jakarta.persistence.AttributeConverter");
        lenient().when(attrConv.getTypeArguments()).thenReturn(List.of()); // empty -> unresolved
        lenient().doReturn(List.of(attrConv)).when(converterType).getInterfaces();

        doReturn(Set.of(converterType)).when(roundEnv).getElementsAnnotatedWith(Converter.class);
        when(roundEnv.getElementsAnnotatedWith(MappedSuperclass.class)).thenReturn(Set.of());
        when(roundEnv.getElementsAnnotatedWith(Embeddable.class)).thenReturn(Set.of());
        when(roundEnv.getElementsAnnotatedWith(Entity.class)).thenReturn(Set.of());
        when(roundEnv.processingOver()).thenReturn(false);

        when(processingEnv.getMessager()).thenReturn(messager);

        // When
        processor.process(Set.of(), roundEnv);

        // Then: WARNING logged, no crash
        verify(messager).printMessage(
                eq(Diagnostic.Kind.WARNING),
                ArgumentMatchers.contains("@Converter(autoApply=true) cannot resolve AttributeConverter<T, ?> target type across hierarchy"),
                eq(converterType)
        );
    }

    @Test
    void processingOver_relationshipResolution_skipsWhenTypeElementNull_andWarns() throws Exception {
        // Given: processingOver=true, one entity in schema, but elements.getTypeElement() returns null
        // Replace context with spy (to avoid saveModelToJson side effects)
        ProcessingContext realCtx = (ProcessingContext) getPrivate(processor, "context");
        ProcessingContext ctx = Mockito.spy(realCtx);
        doNothing().when(ctx).saveModelToJson();
        setPrivate(processor, "context", ctx);

        // Swap handlers with mocks (no side effects)
        InheritanceHandler inh = mock(InheritanceHandler.class);
        RelationshipHandler rel = mock(RelationshipHandler.class);
        EntityHandler ent = mock(EntityHandler.class);
        setPrivate(processor, "inheritanceHandler", inh);
        setPrivate(processor, "relationshipHandler", rel);
        setPrivate(processor, "entityHandler", ent);

        // Put one valid entity into schema
        EntityModel em = EntityModel.builder()
                .entityName("com.example.MissingType")
                .tableName("t_missing")
                .build();
        ctx.getSchemaModel().getEntities().put("com.example.MissingType", em);

        // Make elements.getTypeElement return null for that name
        when(elements.getTypeElement("com.example.MissingType")).thenReturn(null);

        // Round env has no new annotated elements, but processingOver=true
        when(roundEnv.getElementsAnnotatedWith(any(Class.class))).thenReturn(Set.of());
        when(roundEnv.processingOver()).thenReturn(true);

        // When
        processor.process(Set.of(), roundEnv);

        // Then: WARNING emitted, and relationship handler NOT invoked
        verify(messager).printMessage(
                eq(Diagnostic.Kind.WARNING),
                eq("Skip relationship resolution: cannot resolve TypeElement for com.example.MissingType")
        );
        verify(rel, never()).resolveRelationships(any(), any());
    }

    @Test
    void processingOver_finalPkValidation_errorsWhenNoPk_andInvalidatesEntity() throws Exception {
        // Given: processingOver=true, one entity without PKs
        ProcessingContext realCtx = (ProcessingContext) getPrivate(processor, "context");
        ProcessingContext ctx = Mockito.spy(realCtx);
        doNothing().when(ctx).saveModelToJson();
        setPrivate(processor, "context", ctx);

        // Mock handlers to no-op
        InheritanceHandler inh = mock(InheritanceHandler.class);
        RelationshipHandler rel = mock(RelationshipHandler.class);
        EntityHandler ent = mock(EntityHandler.class);
        setPrivate(processor, "inheritanceHandler", inh);
        setPrivate(processor, "relationshipHandler", rel);
        setPrivate(processor, "entityHandler", ent);

        // Entity has no PK columns
        EntityModel em = EntityModel.builder()
                .entityName("com.example.NoPk")
                .tableName("no_pk")
                .build();
        ctx.getSchemaModel().getEntities().put("com.example.NoPk", em);

        // getTypeElement should return a non-null element for error attachment
        TypeElement te = mockTypeElement("com.example.NoPk");
        when(elements.getTypeElement("com.example.NoPk")).thenReturn(te);

        when(roundEnv.getElementsAnnotatedWith(any(Class.class))).thenReturn(Set.of());
        when(roundEnv.processingOver()).thenReturn(true);

        // When
        processor.process(Set.of(), roundEnv);

        // Then: ERROR logged with the TypeElement, and entity invalidated
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                eq("Entity 'com.example.NoPk' must have a primary key."),
                eq(te)
        );
        assertFalse(em.isValid(), "Entity should be marked invalid when no PKs");
    }


    private static <T> T getPrivate(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        T val = (T) f.get(target);
        return val;
    }

    private static void setPrivate(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static TypeElement mockTypeElement(String qname) {
        TypeElement te = mock(TypeElement.class);
        Name name = mock(Name.class);
        lenient().when(name.toString()).thenReturn(qname);
        lenient().when(te.getQualifiedName()).thenReturn(name);
        return te;
    }

    private static TypeMirror mockTypeMirror(String display) {
        TypeMirror tm = mock(TypeMirror.class);
        lenient().when(tm.toString()).thenReturn(display);
        return tm;
    }
}
