package org.jinx.handler.relationship;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.naming.DefaultNaming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManyToManyOwningProcessorTest {

    ProcessingContext context;
    RelationshipSupport support;
    RelationshipJoinSupport joinSupport;
    ManyToManyOwningProcessor processor;

    SchemaModel schema;
    Map<String, EntityModel> entities;
    javax.annotation.processing.Messager messager;

    AttributeDescriptor attr;
    ManyToMany manyToMany;
    Element diagnosticElement;

    EntityModel owner;
    EntityModel target;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        support = mock(RelationshipSupport.class);
        joinSupport = mock(RelationshipJoinSupport.class);
        processor = new ManyToManyOwningProcessor(context, support, joinSupport);

        messager = mock(javax.annotation.processing.Messager.class);
        schema = mock(SchemaModel.class);
        entities = new HashMap<>();

        when(context.getMessager()).thenReturn(messager);
        when(context.getSchemaModel()).thenReturn(schema);
        when(schema.getEntities()).thenReturn(entities);
        when(context.getNaming()).thenReturn(new DefaultNaming(63));

        attr = mock(AttributeDescriptor.class);
        manyToMany = mock(ManyToMany.class);
        when(attr.getAnnotation(ManyToMany.class)).thenReturn(manyToMany);
        when(manyToMany.mappedBy()).thenReturn(""); // owning side

        diagnosticElement = mock(Element.class);
        when(attr.elementForDiagnostics()).thenReturn(diagnosticElement);
        when(attr.name()).thenReturn("mmField");

        // 기본 엔티티
        owner = EntityModel.builder()
                .entityName("com.example.Owner")
                .tableName("owner_tbl")
                .build();
        target = EntityModel.builder()
                .entityName("com.example.Target")
                .tableName("target_tbl")
                .build();

        // 기본 PK (단일)
        when(context.findAllPrimaryKeyColumns(owner))
                .thenReturn(List.of(ColumnModel.builder().tableName("owner_tbl").columnName("owner_id").javaType("Long").isPrimaryKey(true).build()));
        when(context.findAllPrimaryKeyColumns(target))
                .thenReturn(List.of(ColumnModel.builder().tableName("target_tbl").columnName("target_id").javaType("Long").isPrimaryKey(true).build()));

        // 컬렉션 + 제네릭 존재
        when(attr.isCollection()).thenReturn(true);
        when(attr.genericArg(0)).thenReturn(Optional.of(mock(DeclaredType.class)));

        // resolveTargetEntity → TypeElement(mock) → FQN(String)
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Target");
        when(te.getQualifiedName()).thenReturn(qn);
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), isNull(), eq(manyToMany)))
                .thenReturn(Optional.of(te));

        // ConstraintMode 체크는 기본 true로
        when(support.allSameConstraintMode(anyList())).thenReturn(true);

        // 스키마에 타겟 등록
        entities.put("com.example.Target", target);
    }

    // ---------- supports() ----------

    @Test
    void supports_true_when_mappedBy_empty() {
        when(manyToMany.mappedBy()).thenReturn("");
        assertThat(processor.supports(attr)).isTrue();
    }

    @Test
    void supports_false_when_mappedBy_present() {
        when(manyToMany.mappedBy()).thenReturn("otherSide");
        assertThat(processor.supports(attr)).isFalse();
    }

    // ---------- process(): 행복 경로 (JoinTable 미지정, 기본 네이밍) ----------

    @Test
    void process_happyPath_default_join_table_and_defaults() {
        // JoinTable 없음
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);

        // 이름 충돌 검증: 예외 없이 통과
        doReturn(true).when(joinSupport).validateJoinTableNameConflict(anyString(), eq(owner), eq(target), eq(attr));

        // createJoinTableEntity → Optional.of(...)
        ArgumentCaptor<JoinTableDetails> detailsCap = ArgumentCaptor.forClass(JoinTableDetails.class);
        EntityModel created = EntityModel.builder()
                .entityName("jt_owner_tbl__target_tbl")
                .tableName("jt_owner_tbl__target_tbl")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        when(joinSupport.createJoinTableEntity(detailsCap.capture(), anyList(), anyList()))
                .thenReturn(Optional.of(created));

        processor.process(attr, owner);

        // JoinTableDetails 검증
        JoinTableDetails d = detailsCap.getValue();
        assertThat(d.joinTableName()).isEqualTo("jt_owner_tbl__target_tbl");
        assertThat(d.ownerEntity()).isEqualTo(owner);
        assertThat(d.referencedEntity()).isEqualTo(target);
        assertThat(d.ownerFkToPkMap()).containsEntry("owner_tbl_owner_id", "owner_id");
        assertThat(d.inverseFkToPkMap()).containsEntry("target_tbl_target_id", "target_id");

        // ensure 호출
        verify(joinSupport).ensureJoinTableColumns(eq(created), anyList(), anyList(), anyMap(), anyMap(), eq(attr));
        verify(joinSupport).ensureJoinTableRelationships(eq(created), any(JoinTableDetails.class));

        // N:N PK 제약 추가 확인 (owner_fk + target_fk)
        assertThat(created.getConstraints().values())
                .anySatisfy(c -> {
                    assertThat(c.getType()).isEqualTo(ConstraintType.PRIMARY_KEY);
                    assertThat(c.getColumns()).containsExactly("owner_tbl_owner_id", "target_tbl_target_id");
                });

        // 스키마에 등록
        assertThat(entities).containsKey("jt_owner_tbl__target_tbl");
        assertThat(entities.get("jt_owner_tbl__target_tbl")).isSameAs(created);

        // 에러 없음
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), any());
    }

    // ---------- process(): 행복 경로 (JoinTable 명시 + 명시적 JoinColumns) ----------

    @Test
    void process_happyPath_with_explicit_JoinTable_and_JoinColumns_and_UniqueConstraints() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // jt.name() 모킹 반드시
        when(jt.name()).thenReturn("mm_posts_tags");

        // owner side joinColumns (1:1)
        JoinColumn o = mock(JoinColumn.class);
        when(o.referencedColumnName()).thenReturn(""); // 단일PK: 빈 문자열이면 순서로 매핑
        when(o.name()).thenReturn("owner_fk");
        ForeignKey ofk = mock(ForeignKey.class);
        when(ofk.name()).thenReturn(""); // 이름 미지정 → 내부 네이밍
        when(ofk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(o.foreignKey()).thenReturn(ofk);
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{o});

        // inverse side joinColumns (1:1)
        JoinColumn t = mock(JoinColumn.class);
        when(t.referencedColumnName()).thenReturn(""); // 단일PK
        when(t.name()).thenReturn("target_fk");
        ForeignKey tfk = mock(ForeignKey.class);
        when(tfk.name()).thenReturn("");
        when(tfk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(t.foreignKey()).thenReturn(tfk);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{t});

        // uniqueConstraints 존재
        jakarta.persistence.UniqueConstraint uc = mock(jakarta.persistence.UniqueConstraint.class);
        when(uc.columnNames()).thenReturn(new String[]{"owner_fk"});
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[]{uc});

        // 이름 충돌 검증: 통과
        doReturn(true).when(joinSupport).validateJoinTableNameConflict(eq("mm_posts_tags"), eq(owner), eq(target), eq(attr));

        ArgumentCaptor<JoinTableDetails> cap = ArgumentCaptor.forClass(JoinTableDetails.class);
        EntityModel created = EntityModel.builder()
                .entityName("mm_posts_tags")
                .tableName("mm_posts_tags")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        when(joinSupport.createJoinTableEntity(cap.capture(), anyList(), anyList()))
                .thenReturn(Optional.of(created));

        processor.process(attr, owner);

        JoinTableDetails d = cap.getValue();
        assertThat(d.joinTableName()).isEqualTo("mm_posts_tags");
        assertThat(d.ownerFkToPkMap()).containsEntry("owner_fk", "owner_id");
        assertThat(d.inverseFkToPkMap()).containsEntry("target_fk", "target_id");

        // ensure & uniqueConstraints 처리 호출 확인
        verify(joinSupport).ensureJoinTableColumns(eq(created), anyList(), anyList(), anyMap(), anyMap(), eq(attr));
        verify(joinSupport).ensureJoinTableRelationships(eq(created), any(JoinTableDetails.class));
        verify(joinSupport).addJoinTableUniqueConstraints(eq(created), any(jakarta.persistence.UniqueConstraint[].class), eq(attr));

        // PK 제약 추가 확인
        assertThat(created.getConstraints().values())
                .anySatisfy(c -> assertThat(c.getType()).isEqualTo(ConstraintType.PRIMARY_KEY));
    }

    // ---------- 에러 경로들 ----------

    @Test
    void process_error_when_not_a_collection() {
        when(attr.isCollection()).thenReturn(false);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@ManyToMany field must be a collection type"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_generic_type_missing() {
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);
        when(attr.genericArg(0)).thenReturn(Optional.empty());

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Cannot resolve generic type parameter"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_any_side_pks_empty() {
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of()); // owner pk 없음

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("must have a primary key"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_joinColumns_count_mismatch_owner_side() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("mm_bad");
        // composite owner PK 2개
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));
        // joinColumns 1개 → 카운트 불일치
        JoinColumn c = mock(JoinColumn.class);
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{c});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("must match its primary key columns"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_inverseJoinColumns_count_mismatch_target_side() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("mm_bad2");
        // target composite PK 2개
        when(context.findAllPrimaryKeyColumns(target)).thenReturn(List.of(
                ColumnModel.builder().tableName("target_tbl").columnName("x").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("target_tbl").columnName("y").javaType("Long").isPrimaryKey(true).build()
        ));
        // inverseJoinColumns 1개 → 카운트 불일치
        JoinColumn c = mock(JoinColumn.class);
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{c});

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("must match its primary key columns"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_constraintMode_not_same_on_owner_side() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("mm_owner_mode");
        // owner composite PK 2개
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        JoinColumn jc1 = mock(JoinColumn.class);
        JoinColumn jc2 = mock(JoinColumn.class);
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        ForeignKey fk1 = mock(ForeignKey.class);
        when(fk1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(fk1.name()).thenReturn("");
        when(jc1.foreignKey()).thenReturn(fk1);
        when(jc1.referencedColumnName()).thenReturn("a");
        when(jc1.name()).thenReturn("a_fk");

        ForeignKey fk2 = mock(ForeignKey.class);
        when(fk2.value()).thenReturn(ConstraintMode.NO_CONSTRAINT);
        when(fk2.name()).thenReturn("");
        when(jc2.foreignKey()).thenReturn(fk2);
        when(jc2.referencedColumnName()).thenReturn("b");
        when(jc2.name()).thenReturn("b_fk");

        // 이 케이스만 false 반환
        when(support.allSameConstraintMode(eq(Arrays.asList(jc1, jc2)))).thenReturn(false);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("All owner-side @JoinColumn.foreignKey.value must be identical"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_fk_name_collision_between_owner_and_target_sides() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("mm_collision");

        // 단일 PK 양쪽, 같은 FK 이름 강제 → 충돌
        JoinColumn o = mock(JoinColumn.class);
        when(o.name()).thenReturn("dup_fk");
        when(o.referencedColumnName()).thenReturn("");
        ForeignKey ofk = mock(ForeignKey.class);
        when(ofk.name()).thenReturn("");
        when(ofk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(o.foreignKey()).thenReturn(ofk);

        JoinColumn t = mock(JoinColumn.class);
        when(t.name()).thenReturn("dup_fk");
        when(t.referencedColumnName()).thenReturn("");
        ForeignKey tfk = mock(ForeignKey.class);
        when(tfk.name()).thenReturn("");
        when(tfk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(t.foreignKey()).thenReturn(tfk);

        when(jt.joinColumns()).thenReturn(new JoinColumn[]{o});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{t});

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("foreign key name collision across sides"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_owner_referencedColumn_is_not_pk() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("mm_bad_ref");

        // owner 실제 PK는 a
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build()
        ));

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.referencedColumnName()).thenReturn("not_pk");
        when(jc.name()).thenReturn("fk_bad");
        ForeignKey fk = mock(ForeignKey.class);
        when(fk.name()).thenReturn("");
        when(fk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc.foreignKey()).thenReturn(fk);
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{jc});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("is not a primary key column"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_returns_when_resolveTargetEntity_empty() {
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), isNull(), eq(manyToMany)))
                .thenReturn(Optional.empty());

        processor.process(attr, owner);

        verifyNoInteractions(joinSupport);
    }

    @Test
    void process_returns_when_target_entity_not_in_schema() {
        entities.clear(); // target 제거
        processor.process(attr, owner);
        verifyNoInteractions(joinSupport);
    }

    @Test
    void process_existing_join_table_with_non_join_table_type_reports_error() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("existing_tbl");
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        EntityModel existing = EntityModel.builder()
                .entityName("existing_tbl")
                .tableName("existing_tbl")
                .tableType(EntityModel.TableType.ENTITY) // 충돌
                .build();
        entities.put("existing_tbl", existing);

        doReturn(true).when(joinSupport).validateJoinTableNameConflict(eq("existing_tbl"), eq(owner), eq(target), eq(attr));

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("conflicts with a non-join entity/table"), eq(diagnosticElement));
        verify(joinSupport, never()).ensureJoinTableColumns(any(), anyList(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void process_returns_when_join_table_name_validator_throws() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.name()).thenReturn("mm_throw");

        doThrow(new IllegalStateException("stop"))
                .when(joinSupport).validateJoinTableNameConflict(eq("mm_throw"), eq(owner), eq(target), eq(attr));

        processor.process(attr, owner);

        // 이후 동작 없음
        verify(joinSupport, never()).createJoinTableEntity(any(), anyList(), anyList());
    }

    @Test
    void process_existing_join_table_with_fk_set_mismatch_aborts() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("existing_jt");
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        EntityModel existing = EntityModel.builder()
                .entityName("existing_jt")
                .tableName("existing_jt")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        entities.put("existing_jt", existing);

        doReturn(true).when(joinSupport).validateJoinTableNameConflict(eq("existing_jt"), eq(owner), eq(target), eq(attr));
        doThrow(new IllegalStateException("fk mismatch"))
                .when(joinSupport).validateJoinTableFkConsistency(eq(existing), any(JoinTableDetails.class), eq(attr));

        processor.process(attr, owner);

        verify(joinSupport, never()).ensureJoinTableColumns(any(), anyList(), anyList(), anyMap(), anyMap(), any());
    }
}
