package org.jinx.handler.relationship;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
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

class OneToManyOwningJoinTableProcessorTest {

    ProcessingContext context;
    RelationshipSupport support;
    RelationshipJoinSupport joinSupport;
    OneToManyOwningJoinTableProcessor processor;

    SchemaModel schema;
    Map<String, EntityModel> entities;
    javax.annotation.processing.Messager messager;

    AttributeDescriptor attr;
    OneToMany oneToMany;
    Element diagnosticElement;

    EntityModel owner;
    EntityModel target;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        support = mock(RelationshipSupport.class);
        joinSupport = mock(RelationshipJoinSupport.class);
        processor = new OneToManyOwningJoinTableProcessor(context, support, joinSupport);

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
        when(oneToMany.mappedBy()).thenReturn(""); // owning side
        
        // Mock elementForDiagnostics() to return Element
        diagnosticElement = mock(Element.class);
        when(attr.elementForDiagnostics()).thenReturn(diagnosticElement);
        when(attr.name()).thenReturn("testAttribute");

        // 공통 엔티티
        owner = EntityModel.builder()
                .entityName("com.example.Owner")
                .tableName("owner_tbl")
                .build();
        target = EntityModel.builder()
                .entityName("com.example.Target")
                .tableName("target_tbl")
                .build();

        // PK 리스트 (단일 PK 기본)
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
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), eq(oneToMany), isNull()))
                .thenReturn(Optional.of(te));
        
        // Mock allSameConstraintMode method
        when(support.allSameConstraintMode(anyList())).thenReturn(true);

        // 스키마에 타겟 등록
        entities.put("com.example.Target", target);
    }

    // ---------- supports() ----------

    @Test
    void supports_true_when_Owning_without_JoinColumn_and_no_JoinTable() {
        // given
        when(attr.getAnnotation(jakarta.persistence.JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(null);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);

        // when
        boolean ok = processor.supports(attr);

        // then
        assertThat(ok).isTrue();
    }

    @Test
    void supports_false_when_mappedBy_present() {
        when(oneToMany.mappedBy()).thenReturn("owner");
        boolean ok = processor.supports(attr);
        assertThat(ok).isFalse();
    }

    @Test
    void supports_true_when_JoinTable_present_even_if_JoinColumn_exists() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        JoinColumn jc = mock(JoinColumn.class);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(jc);

        boolean ok = processor.supports(attr);
        assertThat(ok).isTrue(); // JoinTable 있으면 true
    }

    @Test
    void supports_false_when_has_JoinColumn_and_no_JoinTable() {
        JoinColumn jc = mock(JoinColumn.class);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(jc);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);

        boolean ok = processor.supports(attr);
        assertThat(ok).isFalse();
    }

    // ---------- process(): 행복 경로 (JoinTable 미지정, 기본 네이밍) ----------

    @Test
    void process_happyPath_default_join_table_and_defaults() {
        // given: JoinTable 없음 → 기본 이름 jt_owner_tbl__target_tbl
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(null);

        // joinSupport 동작 설정
        when(joinSupport.validateJoinTableNameConflict(anyString(), eq(owner), eq(target), eq(attr)))
                .thenReturn(true);

        // createJoinTableEntity → Optional.of(...)
        ArgumentCaptor<JoinTableDetails> detailsCap = ArgumentCaptor.forClass(JoinTableDetails.class);
        EntityModel created = EntityModel.builder()
                .entityName("jt_owner_tbl__target_tbl")
                .tableName("jt_owner_tbl__target_tbl")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        when(joinSupport.createJoinTableEntity(detailsCap.capture(), anyList(), anyList()))
                .thenReturn(Optional.of(created));

        // when
        processor.process(attr, owner);

        // then: JoinTableDetails 검증
        JoinTableDetails d = detailsCap.getValue();
        assertThat(d.joinTableName()).isEqualTo("jt_owner_tbl__target_tbl");
        assertThat(d.ownerEntity()).isEqualTo(owner);
        assertThat(d.referencedEntity()).isEqualTo(target);
        assertThat(d.ownerFkToPkMap()).containsEntry("owner_tbl_owner_id", "owner_id");
        assertThat(d.inverseFkToPkMap()).containsEntry("target_tbl_target_id", "target_id");

        // ensure 호출
        verify(joinSupport).ensureJoinTableColumns(eq(created), anyList(), anyList(), anyMap(), anyMap(), eq(attr));
        verify(joinSupport).ensureJoinTableRelationships(eq(created), any(JoinTableDetails.class));
        verify(joinSupport).addOneToManyJoinTableUnique(eq(created), anyMap());

        // 스키마에 등록
        assertThat(entities).containsKey("jt_owner_tbl__target_tbl");
        assertThat(entities.get("jt_owner_tbl__target_tbl")).isSameAs(created);

        // 에러 없음
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), any());
    }

    // ---------- process(): 행복 경로 (JoinTable 명시 + 명시적 JoinColumns) ----------

    @Test
    void process_happyPath_with_explicit_JoinTable_and_JoinColumns() {
        // JoinTable
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn("post__comments");
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // owner side joinColumns (1:1)
        JoinColumn o = mock(JoinColumn.class);
        when(o.referencedColumnName()).thenReturn(""); // 단일PK: 비워두면 순서로 매핑
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

        when(attr.getAnnotation(jakarta.persistence.JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(null);

        when(joinSupport.validateJoinTableNameConflict(eq("post__comments"), eq(owner), eq(target), eq(attr)))
                .thenReturn(true);

        ArgumentCaptor<JoinTableDetails> cap = ArgumentCaptor.forClass(JoinTableDetails.class);
        EntityModel created = EntityModel.builder()
                .entityName("post__comments")
                .tableName("post__comments")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        when(joinSupport.createJoinTableEntity(cap.capture(), anyList(), anyList()))
                .thenReturn(Optional.of(created));

        processor.process(attr, owner);

        JoinTableDetails d = cap.getValue();
        assertThat(d.joinTableName()).isEqualTo("post__comments");
        assertThat(d.ownerFkToPkMap()).containsEntry("owner_fk", "owner_id");
        assertThat(d.inverseFkToPkMap()).containsEntry("target_fk", "target_id");

        // 정상 흐름 호출 보장
        verify(joinSupport).ensureJoinTableColumns(eq(created), anyList(), anyList(), anyMap(), anyMap(), eq(attr));
        verify(joinSupport).ensureJoinTableRelationships(eq(created), any(JoinTableDetails.class));
        verify(joinSupport).addOneToManyJoinTableUnique(eq(created), anyMap());
        assertThat(entities).containsKey("post__comments");
    }

    // ---------- 에러 경로들 ----------

    @Test
    void process_error_when_not_a_collection() {
        when(attr.isCollection()).thenReturn(false);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@OneToMany field must be a collection type"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_generic_type_missing() {
        when(attr.genericArg(0)).thenReturn(Optional.empty());

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Cannot resolve generic type parameter"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_owner_pks_empty() {
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of());

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Owner entity requires a primary key"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_target_pks_empty() {
        when(context.findAllPrimaryKeyColumns(target)).thenReturn(List.of());

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Target entity requires a primary key"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_joinColumns_count_mismatch_owner_side() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn(""); // Empty string to use default naming
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // composite owner PK 2개
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        // joinColumns 1개 → 카운트 불일치
        JoinColumn c = mock(JoinColumn.class);
        when(c.name()).thenReturn("a");
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{c});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("JoinTable.joinColumns count must match owner primary key count"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_inverseJoinColumns_count_mismatch_target_side() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn(""); // Empty string to use default naming
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        when(context.findAllPrimaryKeyColumns(target)).thenReturn(List.of(
                ColumnModel.builder().tableName("target_tbl").columnName("x").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("target_tbl").columnName("y").javaType("Long").isPrimaryKey(true).build()
        ));

        JoinColumn c = mock(JoinColumn.class);
        when(c.name()).thenReturn("a");
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{c});

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("JoinTable.inverseJoinColumns count must match target primary key count"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_composite_pk_requires_referencedColumnName_but_missing() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn(""); // Empty string to use default naming
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // composite owner PK 2개
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        // joinColumns 두 개인데 referencedColumnName 비워서 에러
        JoinColumn jc1 = mock(JoinColumn.class);
        when(jc1.name()).thenReturn("a");
        JoinColumn jc2 = mock(JoinColumn.class);
        when(jc2.name()).thenReturn("b");
        when(jc1.referencedColumnName()).thenReturn("");
        when(jc2.referencedColumnName()).thenReturn("");
        ForeignKey fk = mock(ForeignKey.class);
        when(fk.name()).thenReturn("");
        when(fk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc1.foreignKey()).thenReturn(fk);
        when(jc2.foreignKey()).thenReturn(fk);

        when(jt.joinColumns()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Composite primary key requires explicit referencedColumnName"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_constraintMode_not_same_on_owner_side() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn(""); // Empty string to use default naming
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        
        // Set up composite PK for owner to match 2 join columns
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        JoinColumn jc1 = mock(JoinColumn.class);
        when(jc1.name()).thenReturn("a");
        JoinColumn jc2 = mock(JoinColumn.class);
        when(jc2.name()).thenReturn("b");

        ForeignKey fk1 = mock(ForeignKey.class);
        when(fk1.name()).thenReturn("");
        when(fk1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc1.foreignKey()).thenReturn(fk1);
        when(jc1.referencedColumnName()).thenReturn("a"); // Composite PK needs explicit reference

        ForeignKey fk2 = mock(ForeignKey.class);
        when(fk2.name()).thenReturn("");
        when(fk2.value()).thenReturn(ConstraintMode.NO_CONSTRAINT);
        when(jc2.foreignKey()).thenReturn(fk2);
        when(jc2.referencedColumnName()).thenReturn("b"); // Composite PK needs explicit reference

        when(jt.joinColumns()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        
        // Mock support.allSameConstraintMode to return false for this specific case
        when(support.allSameConstraintMode(eq(Arrays.asList(jc1, jc2)))).thenReturn(false);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("All owner-side @JoinColumn.foreignKey.value must be identical"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_fk_name_collision_between_owner_and_target_sides() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn(""); // Empty string to use default naming
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // 오너/타깃 각각 단일 PK, 둘 다 같은 FK 이름으로 지정 → 충돌
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
                contains("JoinTable foreign key name collision across sides"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_returns_when_join_table_name_conflict_validator_fails() {
        // 기본 경로에서 validator=false
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(jakarta.persistence.JoinColumn.class)).thenReturn(null);

        when(joinSupport.validateJoinTableNameConflict(anyString(), eq(owner), eq(target), eq(attr)))
                .thenReturn(false);

        processor.process(attr, owner);

        // 이후 동작 없음
        verify(joinSupport, never()).createJoinTableEntity(any(), anyList(), anyList());
    }

    @Test
    void process_existing_join_table_with_non_join_table_type_reports_error() {
        // JoinTable 명시
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn("existing_tbl");
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        // 스키마에 동일 이름의 엔티티 있으나 JOIN_TABLE 아님
        EntityModel existing = EntityModel.builder()
                .entityName("existing_tbl")
                .tableName("existing_tbl")
                .tableType(EntityModel.TableType.ENTITY) // 충돌
                .build();
        entities.put("existing_tbl", existing);
        
        // Mock joinSupport.validateJoinTableNameConflict to return true so we proceed to the type check
        when(joinSupport.validateJoinTableNameConflict(eq("existing_tbl"), eq(owner), eq(target), eq(attr)))
                .thenReturn(true);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("conflicts with a non-join entity/table"), eq(diagnosticElement));
        verify(joinSupport, never()).ensureJoinTableColumns(any(), anyList(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void process_existing_join_table_with_fk_set_mismatch_aborts() {
        JoinTable jt = mock(JoinTable.class);
        when(jt.name()).thenReturn("existing_jt");
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);

        EntityModel existing = EntityModel.builder()
                .entityName("existing_jt")
                .tableName("existing_jt")
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
        entities.put("existing_jt", existing);

        when(joinSupport.validateJoinTableFkConsistency(eq(existing), any(JoinTableDetails.class), eq(attr)))
                .thenReturn(false);
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);

        processor.process(attr, owner);

        verify(joinSupport, never()).ensureJoinTableColumns(any(), anyList(), anyList(), anyMap(), anyMap(), any());
    }

    @Test
    void process_returns_when_resolveTargetEntity_empty() {
        when(support.resolveTargetEntity(eq(attr), isNull(), isNull(), eq(oneToMany), isNull()))
                .thenReturn(Optional.empty());

        processor.process(attr, owner);

        // 이후 joinSupport 호출 없음
        verifyNoInteractions(joinSupport);
    }

    @Test
    void process_returns_when_target_entity_not_in_schema() {
        // resolveTargetEntity OK지만 스키마에 미등록
        entities.clear(); // target 제거
        processor.process(attr, owner);
        verifyNoInteractions(joinSupport);
    }

    @Test
    void process_error_when_owner_fk_names_not_identical() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("jt");

        JoinColumn jc1 = mock(JoinColumn.class);
        JoinColumn jc2 = mock(JoinColumn.class);
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[0]);
        // composite PK 2개로 맞춰줌
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(
                ColumnModel.builder().tableName("owner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("owner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        ForeignKey fk1 = mock(ForeignKey.class);
        when(fk1.name()).thenReturn("FK_A");
        when(fk1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc1.foreignKey()).thenReturn(fk1);
        when(jc1.referencedColumnName()).thenReturn("a");
        when(jc1.name()).thenReturn("a_fk");

        ForeignKey fk2 = mock(ForeignKey.class);
        when(fk2.name()).thenReturn("FK_B");
        when(fk2.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc2.foreignKey()).thenReturn(fk2);
        when(jc2.referencedColumnName()).thenReturn("b");
        when(jc2.name()).thenReturn("b_fk");

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("All owner-side @JoinColumn.foreignKey names must be identical"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_inverse_fk_names_not_identical() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("jt");

        JoinColumn t1 = mock(JoinColumn.class);
        JoinColumn t2 = mock(JoinColumn.class);
        when(jt.joinColumns()).thenReturn(new JoinColumn[0]);
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{t1, t2});

        // target composite PK 2개
        when(context.findAllPrimaryKeyColumns(target)).thenReturn(List.of(
                ColumnModel.builder().tableName("target_tbl").columnName("x").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("target_tbl").columnName("y").javaType("Long").isPrimaryKey(true).build()
        ));

        ForeignKey fk1 = mock(ForeignKey.class);
        when(fk1.name()).thenReturn("FK_X");
        when(fk1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(t1.foreignKey()).thenReturn(fk1);
        when(t1.referencedColumnName()).thenReturn("x");
        when(t1.name()).thenReturn("x_fk");

        ForeignKey fk2 = mock(ForeignKey.class);
        when(fk2.name()).thenReturn("FK_Y_DIFFERENT");
        when(fk2.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(t2.foreignKey()).thenReturn(fk2);
        when(t2.referencedColumnName()).thenReturn("y");
        when(t2.name()).thenReturn("y_fk");

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("All inverse-side @JoinColumn.foreignKey names must be identical"), eq(diagnosticElement));
        verifyNoMoreInteractions(joinSupport);
    }

    @Test
    void process_error_when_owner_referencedColumn_is_not_pk() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("jt");

        // owner PK는 a 하나
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
    void process_returns_when_createJoinTableEntity_empty() {
        when(attr.getAnnotation(JoinTable.class)).thenReturn(null);
        when(joinSupport.validateJoinTableNameConflict(anyString(), eq(owner), eq(target), eq(attr)))
                .thenReturn(true);

        when(joinSupport.createJoinTableEntity(any(), anyList(), anyList()))
                .thenReturn(Optional.empty());

        processor.process(attr, owner);

        verify(joinSupport, never()).ensureJoinTableColumns(any(), anyList(), anyList(), anyMap(), anyMap(), any());
        verify(joinSupport, never()).ensureJoinTableRelationships(any(), any());
    }

    @Test
    void process_sets_noConstraint_flags_from_joinColumns() {
        JoinTable jt = mock(JoinTable.class);
        when(attr.getAnnotation(JoinTable.class)).thenReturn(jt);
        when(jt.name()).thenReturn("jt");
        when(jt.uniqueConstraints()).thenReturn(new jakarta.persistence.UniqueConstraint[0]);

        JoinColumn o = mock(JoinColumn.class);
        ForeignKey ofk = mock(ForeignKey.class);
        when(ofk.name()).thenReturn("");
        when(ofk.value()).thenReturn(ConstraintMode.NO_CONSTRAINT); // owner no constraint
        when(o.foreignKey()).thenReturn(ofk);
        when(o.referencedColumnName()).thenReturn(""); // 단일 PK이면 빈 문자열 허용
        when(o.name()).thenReturn("owner_fk");
        when(jt.joinColumns()).thenReturn(new JoinColumn[]{o});

        JoinColumn t = mock(JoinColumn.class);
        ForeignKey tfk = mock(ForeignKey.class);
        when(tfk.name()).thenReturn("");
        when(tfk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(t.foreignKey()).thenReturn(tfk);
        when(t.referencedColumnName()).thenReturn("");
        when(t.name()).thenReturn("target_fk");
        when(jt.inverseJoinColumns()).thenReturn(new JoinColumn[]{t});

        when(joinSupport.validateJoinTableNameConflict(anyString(), eq(owner), eq(target), eq(attr)))
                .thenReturn(true);

        ArgumentCaptor<JoinTableDetails> cap = ArgumentCaptor.forClass(JoinTableDetails.class);
        when(joinSupport.createJoinTableEntity(cap.capture(), anyList(), anyList()))
                .thenReturn(Optional.of(EntityModel.builder()
                        .entityName("owner__target")
                        .tableName("owner__target")
                        .tableType(EntityModel.TableType.JOIN_TABLE)
                        .build()));

        processor.process(attr, owner);

        JoinTableDetails d = cap.getValue();
        assertThat(d.ownerNoConstraint()).isTrue();
        assertThat(d.inverseNoConstraint()).isFalse();
    }

}
