package org.jinx.handler.relationship;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.OneToMany;
import org.jinx.context.DefaultNaming;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationMocks;
import org.jinx.testing.util.NamingTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OneToManyOwningFkProcessorTest {

    ProcessingContext context;
    RelationshipSupport support;
    Messager messager;
    SchemaModel schema;
    Map<String, EntityModel> entities;
    Naming naming;

    OneToManyOwningFkProcessor processor;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        support = mock(RelationshipSupport.class);
        messager = mock(Messager.class);
        schema = mock(SchemaModel.class);
        entities = new HashMap<>();

        when(context.getMessager()).thenReturn(messager);
        when(context.getSchemaModel()).thenReturn(schema);
        when(schema.getEntities()).thenReturn(entities);

        when(context.getNaming()).thenReturn(new DefaultNaming(255));

        // putColumn 동작을 실제 모델에 반영
        doAnswer(inv -> {
            EntityModel e = inv.getArgument(0);
            ColumnModel c = inv.getArgument(1);
            e.putColumn(c);
            return null;
        }).when(support).putColumn(any(EntityModel.class), any(ColumnModel.class));

        // 기타 기본값
        when(support.allSameConstraintMode(anyList())).thenReturn(true);
        doNothing().when(support).addForeignKeyIndex(any(EntityModel.class), anyList(), anyString());

        processor = new OneToManyOwningFkProcessor(context, support);
    }

    @Test
    @DisplayName("supports: OneToMany owning(FK) + @JoinColumn 있을 때만 true")
    void supports_true_only_when_owning_with_joinColumn() {
        OneToMany otm = mock(OneToMany.class);
        when(otm.mappedBy()).thenReturn("");

        JoinColumn jc = AnnotationMocks.joinColumn("children_id", "id");
        AttributeDescriptor attr = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm, jc);

        // JoinTable은 없고 JoinColumn만 존재
        assertTrue(processor.supports(attr));

        // mappedBy가 있으면 false
        OneToMany withMappedBy = mock(OneToMany.class);
        when(withMappedBy.mappedBy()).thenReturn("owner");
        AttributeDescriptor attr2 = AttributeDescriptorFactory.listOf("com.example.Child", "children", withMappedBy, jc);
        assertFalse(processor.supports(attr2));

        // JoinColumn 없으면 false
        OneToMany otm2 = mock(OneToMany.class);
        when(otm2.mappedBy()).thenReturn("");
        AttributeDescriptor attr3 = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm2 /* no jc */);
        assertFalse(processor.supports(attr3));
    }

    @Test
    @DisplayName("process: 행복 경로 - 단일 PK, 단일 JoinColumn → FK 컬럼 + FK 관계 생성")
    void process_happy_path_singlePk_singleJoinColumn() {
        // Owner 엔티티(PK=id: Long)
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.example.Owner", "owner");

        // Target 엔티티(Many 쪽, child 테이블)
        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put("com.example.Child", child);

        // Attribute(컬렉션 + 제네릭 T 존재) 준비
        OneToMany otm = mock(OneToMany.class);
        when(otm.mappedBy()).thenReturn("");
        JoinColumn jc = AnnotationMocks.joinColumn("children_id", "id");
        AttributeDescriptor attr = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm, jc);
        when(attr.isCollection()).thenReturn(true);
        when(attr.genericArg(eq(0))).thenReturn(Optional.of(mock(DeclaredType.class)));

        // 타깃 엔티티 해석 성공
        TypeElement targetEl = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Child");
        when(targetEl.getQualifiedName()).thenReturn(qn);
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), eq(otm), isNull()))
                .thenReturn(Optional.of(targetEl));

        // Owner PK 조회
        ColumnModel ownerPk = ColumnModel.builder()
                .tableName("owner").columnName("id").javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(ownerPk));

        // FK 컬럼 테이블 해석
        when(support.resolveJoinColumnTable(eq(jc), eq(child))).thenReturn("child");

        // 기존 컬럼 없음
        when(support.findColumn(eq(child), eq("child"), eq("children_id"))).thenReturn(null);

        // cascade 변환
        when(support.toCascadeList(any())).thenReturn(List.of());

        // 실행
        processor.process(attr, owner);

        RelationshipAssertions.assertFkByStructure(
                child,
                "child", List.of("children_id"),
                "owner", List.of("id"),
                RelationshipType.ONE_TO_MANY
        );

        ColumnModel col = child.getColumns().get("child::children_id");
        assertNotNull(col, "FK column should be added to child");
        assertEquals("java.lang.Long", col.getJavaType());

        // 인덱스 생성 호출되었는지 확인
        verify(support, atLeastOnce()).addForeignKeyIndex(eq(child), eq(List.of("children_id")), eq("child"));
    }

    @Test
    @DisplayName("process: 컬렉션 타입이 아니면 ERROR")
    void process_error_when_not_collection() {
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.example.Owner", "owner");

        OneToMany otm = mock(OneToMany.class);
        when(otm.mappedBy()).thenReturn("");
        JoinColumn jc = AnnotationMocks.joinColumn("children_id", "id");
        AttributeDescriptor attr = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm, jc);

        when(attr.isCollection()).thenReturn(false);

        processor.process(attr, owner);

        MessagerAssertions.assertErrorContains(messager, "@OneToMany field must be a collection type");
    }

    @Test
    @DisplayName("process: @JoinColumns 크기 != owner PK 수 → ERROR")
    void process_error_joinColumns_size_mismatch() {
        // Owner에 PK 2개로 가정
        EntityModel owner = EntityModelMother.javaEntity("com.example.Owner", "owner");
        ColumnModel id1 = ColumnModel.builder().tableName("owner").columnName("id1").javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        ColumnModel id2 = ColumnModel.builder().tableName("owner").columnName("id2").javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        owner.putColumn(id1);
        owner.putColumn(id2);

        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put("com.example.Child", child);

        OneToMany otm = mock(OneToMany.class);
        when(otm.mappedBy()).thenReturn("");

        // JoinColumns 1개만 제공 → mismatch
        JoinColumn j1 = AnnotationMocks.joinColumn("children_id", "id1");
        JoinColumns jcs = mock(JoinColumns.class);
        when(jcs.value()).thenReturn(new JoinColumn[]{j1});

        AttributeDescriptor base = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm, jcs);
        when(base.isCollection()).thenReturn(true);
        when(base.genericArg(eq(0))).thenReturn(Optional.of(mock(DeclaredType.class)));

        TypeElement targetEl = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Child");
        when(targetEl.getQualifiedName()).thenReturn(qn);
        when(support.resolveTargetEntity(eq(base), isNull(), isNull(), eq(otm), isNull()))
                .thenReturn(Optional.of(targetEl));

        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(id1, id2));

        processor.process(base, owner);

        MessagerAssertions.assertErrorContains(messager, "@JoinColumns size mismatch");
    }

    @Test
    @DisplayName("process: @JoinColumn(unique=true) → WARNING + 유니크 제약 추가")
    void process_warning_unique_true_adds_unique_constraint() {
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.example.Owner", "owner");
        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put("com.example.Child", child);

        OneToMany otm = mock(OneToMany.class);
        when(otm.mappedBy()).thenReturn("");

        JoinColumn jc = AnnotationMocks.joinColumn("children_id", "id");
        when(jc.unique()).thenReturn(true); // unique=true로 경로 진입

        AttributeDescriptor attr = AttributeDescriptorFactory.listOf("com.example.Child", "children", otm, jc);
        when(attr.isCollection()).thenReturn(true);
        when(attr.genericArg(eq(0))).thenReturn(Optional.of(mock(DeclaredType.class)));

        TypeElement targetEl = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Child");
        when(targetEl.getQualifiedName()).thenReturn(qn);
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), eq(otm), isNull()))
                .thenReturn(Optional.of(targetEl));

        ColumnModel ownerPk = ColumnModel.builder()
                .tableName("owner").columnName("id").javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(ownerPk));

        when(support.resolveJoinColumnTable(eq(jc), eq(child))).thenReturn("child");
        when(support.findColumn(eq(child), eq("child"), eq("children_id"))).thenReturn(null);
        when(support.toCascadeList(any())).thenReturn(List.of());

        processor.process(attr, owner);

        // WARNING 로그(3-인자) 확인
        verify(messager, atLeastOnce()).printMessage(
                eq(Diagnostic.Kind.WARNING),
                contains("@OneToMany with @JoinColumn(unique=true) effectively becomes a one-to-one relationship"),
                any()
        );
        assertTrue(
                child.getConstraints().values().stream().anyMatch(c ->
                        c.getType() == ConstraintType.UNIQUE &&
                                "child".equals(c.getTableName()) &&
                                c.getColumns().equals(List.of("children_id"))
                ),
                "Unique constraint (child, [children_id]) should exist"
        );
    }
}
