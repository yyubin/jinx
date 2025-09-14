package org.jinx.handler.relationship;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.naming.DefaultNaming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OneToManyOwningFkProcessor_AdditionalTest {

    ProcessingContext context;
    RelationshipSupport support;
    OneToManyOwningFkProcessor processor;

    SchemaModel schema;
    Map<String, EntityModel> entities;
    javax.annotation.processing.Messager messager;

    AttributeDescriptor attr;
    OneToMany oneToMany;
    Element diagEl;

    EntityModel owner;
    EntityModel target;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        support = mock(RelationshipSupport.class);
        processor = new OneToManyOwningFkProcessor(context, support);

        messager = mock(javax.annotation.processing.Messager.class);
        schema = mock(SchemaModel.class);
        entities = new HashMap<>();

        when(context.getMessager()).thenReturn(messager);
        when(context.getSchemaModel()).thenReturn(schema);
        when(schema.getEntities()).thenReturn(entities);
        when(context.getNaming()).thenReturn(new DefaultNaming(63));

        attr = mock(AttributeDescriptor.class);
        oneToMany = mock(OneToMany.class);
        when(attr.getAnnotation(OneToMany.class)).thenReturn(oneToMany);
        when(oneToMany.mappedBy()).thenReturn(""); // owning

        diagEl = mock(Element.class);
        when(attr.elementForDiagnostics()).thenReturn(diagEl);
        when(attr.name()).thenReturn("orders");   // FK 기본 네이밍에 사용됨

        // 기본: 컬렉션 + 제네릭 존재
        when(attr.isCollection()).thenReturn(true);
        when(attr.genericArg(0)).thenReturn(Optional.of(mock(DeclaredType.class)));

        // 기본 엔티티
        owner = EntityModel.builder().entityName("com.example.Owner").tableName("owner_tbl").build();
        target = EntityModel.builder().entityName("com.example.Target").tableName("target_tbl").build();

        // PK (단일)
        when(context.findAllPrimaryKeyColumns(owner))
                .thenReturn(List.of(ColumnModel.builder()
                        .tableName("owner_tbl").columnName("owner_id").javaType("Long").isPrimaryKey(true).build()));

        // resolveTargetEntity -> TypeElement("com.example.Target")
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Target");
        when(te.getQualifiedName()).thenReturn(qn);

        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), any(), isNull()))
                .thenReturn(Optional.of(te));

        // 스키마에 타겟 존재 (테스트마다 필요에 따라 비우기도 함)
        entities.put("com.example.Target", target);

        // 기본: JoinColumn 하나(mock)
        JoinColumn jc = mock(JoinColumn.class);
        when(jc.name()).thenReturn("owner_fk");
        when(jc.referencedColumnName()).thenReturn(""); // 인덱스 기반 fallback 사용 가능
        when(jc.nullable()).thenReturn(true);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(jc);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);

        // FK가 걸릴 테이블 resolve
        when(support.resolveJoinColumnTable(any(), eq(target))).thenReturn(target.getTableName());
    }

    // --- supports() 보강: JoinTable이 존재하면 false ---

    @Test
    void supports_false_when_joinTable_present_even_if_joinColumn_exists() {
        // given: JoinColumn + JoinTable 동시 존재
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        boolean ok = processor.supports(attr);
        assertThat(ok).isFalse(); // FK 전략은 JoinTable 있으면 안 됨
    }

    // --- process(): resolveTargetEntity empty → return ---

    @Test
    void process_returns_when_resolveTargetEntity_empty() {
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), any(), isNull()))
                .thenReturn(Optional.empty());

        processor.process(attr, owner);

        verify(support, times(1))
                .resolveTargetEntity(eq(attr), isNull(), isNull(), any(OneToMany.class), isNull());

        verifyNoMoreInteractions(support);

        verify(support, never()).putColumn(any(), any());
    }

    // --- process(): 타겟 엔티티 스키마에 없음 → return ---

    @Test
    void process_returns_when_target_entity_not_in_schema() {
        entities.clear(); // 타겟 제거

        processor.process(attr, owner);

        // 새 컬럼 추가나 관계 생성이 없어야 함
        verify(support, never()).putColumn(any(), any());
    }

    // --- process(): 기존 FK 컬럼 타입 불일치 → ERROR 후 abort ---

    @Test
    void process_error_when_existing_fk_column_type_mismatch() {
        // target에 동일 이름의 FK 컬럼이 이미 있는데 타입이 다름(String vs Long)
        String fkColName = "owner_fk";
        ColumnModel existing = ColumnModel.builder()
                .columnName(fkColName)
                .tableName(target.getTableName())
                .javaType("String") // 기대: Long
                .build();

        when(support.findColumn(eq(target), eq(target.getTableName()), eq(fkColName)))
                .thenReturn(existing);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for implicit foreign key column 'owner_fk'"),
                eq(diagEl));
        // abort 되었으므로 putColumn/관계 추가 없음
        verify(support, never()).putColumn(any(), any());
    }
}
