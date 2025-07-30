package org.jinx.handler;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class InheritanceHandlerTest {

    private ProcessingContext mockContext(SchemaModel model,
                                          Elements elements,
                                          Types types,
                                          Messager messager) {

        ProcessingEnvironment env = Mockito.mock(ProcessingEnvironment.class);
        Mockito.when(env.getElementUtils()).thenReturn(elements);
        Mockito.when(env.getTypeUtils()).thenReturn(types);
        Mockito.when(env.getMessager()).thenReturn(messager);
        return new ProcessingContext(env, model);
    }


    @Test
    void singleTable_addsDiscriminatorColumn() {
        // ── EntityModel 준비 ──────────────────────────────
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .build();

        SchemaModel schema = SchemaModel.builder()
                .entities(Map.of(parent.getEntityName(), parent))
                .build();

        // ── TypeElement + Annotation Stub ────────────────
        TypeElement parentType = Mockito.mock(TypeElement.class);

        Inheritance inh = Mockito.mock(Inheritance.class);
        Mockito.when(inh.strategy()).thenReturn(InheritanceType.SINGLE_TABLE);
        Mockito.when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

        DiscriminatorColumn disc = Mockito.mock(DiscriminatorColumn.class);
        Mockito.when(disc.name()).thenReturn("dtype");
        Mockito.when(parentType.getAnnotation(DiscriminatorColumn.class)).thenReturn(disc);

        // ── Context & Handler ─────────────────────────────
        ProcessingContext ctx = mockContext(schema,
                Mockito.mock(Elements.class),
                Mockito.mock(Types.class),
                Mockito.mock(Messager.class));

        InheritanceHandler handler = new InheritanceHandler(ctx);

        handler.resolveInheritance(parentType, parent);

        assertThat(parent.getInheritance()).isEqualTo(InheritanceType.SINGLE_TABLE);
        assertThat(parent.getColumns()).containsKey("dtype");
    }

    @Test
    void joinedInheritance_populatesChildEntity() {
        // ── Parent & Child 모델 ───────────────────────────
        ColumnModel idCol = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();

        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .columns(Map.of("id", idCol))
                .build();

        EntityModel child = EntityModel.builder()
                .entityName("com.example.Child")
                .tableName("Child")
                .build();

        SchemaModel schema = SchemaModel.builder()
                .entities(Map.of(
                        parent.getEntityName(), parent,
                        child.getEntityName(), child))
                .build();

        // ── Mock TypeElements & 타입 계층 관계 ─────────────
        TypeElement parentType = Mockito.mock(TypeElement.class);
        TypeElement childType  = Mockito.mock(TypeElement.class);
        TypeMirror  parentTm   = Mockito.mock(TypeMirror.class);
        TypeMirror  childTm    = Mockito.mock(TypeMirror.class);

        Mockito.when(parentType.asType()).thenReturn(parentTm);
        Mockito.when(childType.asType()).thenReturn(childTm);

        // Parent에 @Inheritance(strategy = JOINED)
        Inheritance inh = Mockito.mock(Inheritance.class);
        Mockito.when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        Mockito.when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

        // Elements.getTypeElement(...) 반환 설정
        Elements elements = Mockito.mock(Elements.class);
        Mockito.when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        Mockito.when(elements.getTypeElement("com.example.Parent")).thenReturn(parentType);

        // Types.isSubtype(child, parent) => true
        Types types = Mockito.mock(Types.class);
        Mockito.when(types.isSubtype(childTm, parentTm)).thenReturn(true);

        ProcessingContext ctx = mockContext(schema, elements, types, Mockito.mock(Messager.class));
        InheritanceHandler handler = new InheritanceHandler(ctx);

        handler.resolveInheritance(parentType, parent);

        /* ── 검증 ─────────────────────────────────────── */
        assertThat(child.getInheritance()).isEqualTo(InheritanceType.JOINED);
        assertThat(child.getParentEntity()).isEqualTo(parent.getEntityName());
        // 부모 PK가 자식 FK+PK로 복사됐는지
        assertThat(child.getColumns()).containsKey("id");
        assertThat(child.getColumns().get("id").isPrimaryKey()).isTrue();
        // 관계가 추가됐는지
        assertThat(child.getRelationships()).isNotEmpty();
        RelationshipModel rel = child.getRelationships().get(0);
        assertThat(rel.getReferencedTable()).isEqualTo("Parent");
        assertThat(rel.getColumn()).isEqualTo("id");
    }
}
