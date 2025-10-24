package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;
import org.jinx.naming.DefaultNaming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ToOneRelationshipProcessor
 */
class ToOneRelationshipProcessorTest {

    ProcessingContext context;
    javax.annotation.processing.Messager messager;
    SchemaModel schema;
    Map<String, EntityModel> entities;

    AttributeDescriptor attr;
    Element diagEl;

    EntityModel owner;
    EntityModel referenced;

    ToOneRelationshipProcessor processor;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        messager = mock(javax.annotation.processing.Messager.class);
        when(context.getMessager()).thenReturn(messager);
        when(context.getNaming()).thenReturn(new DefaultNaming(63));

        schema = mock(SchemaModel.class);
        entities = new HashMap<>();
        when(context.getSchemaModel()).thenReturn(schema);
        when(schema.getEntities()).thenReturn(entities);

        // attribute descriptor
        attr = mock(AttributeDescriptor.class);
        when(attr.name()).thenReturn("partner");
        diagEl = mock(Element.class);
        when(attr.elementForDiagnostics()).thenReturn(diagEl);

        // owner/referenced entities
        owner = EntityModel.builder()
                .entityName("com.example.Owner")
                .tableName("owner_tbl")
                .build();

        referenced = EntityModel.builder()
                .entityName("com.example.Partner")
                .tableName("partner_tbl")
                .build();

        // referenced PK (단일 PK)
        when(context.findAllPrimaryKeyColumns(referenced)).thenReturn(List.of(
                ColumnModel.builder()
                        .tableName("partner_tbl").columnName("id")
                        .javaType("Long").isPrimaryKey(true).build()
        ));

        // resolveTargetEntity 경로: attr.type() -> DeclaredType -> TypeElement(FQN)
        DeclaredType dt = mock(DeclaredType.class);
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Partner");
        when(te.getQualifiedName()).thenReturn(qn);
        when(dt.asElement()).thenReturn(te);
        when(attr.type()).thenReturn(dt);

        // 스키마에 target 등록
        entities.put("com.example.Partner", referenced);

        processor = new ToOneRelationshipProcessor(context);
    }

    // ---------- supports() ----------

    @Test
    void supports_true_for_ManyToOne() {
        ManyToOne m2o = mock(ManyToOne.class);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);
        assertThat(processor.supports(attr)).isTrue();
    }

    @Test
    void supports_true_for_OneToOne_owning() {
        OneToOne o2o = mock(OneToOne.class);
        when(o2o.mappedBy()).thenReturn("");
        when(attr.getAnnotation(OneToOne.class)).thenReturn(o2o);
        assertThat(processor.supports(attr)).isTrue();
    }

    @Test
    void supports_false_for_OneToOne_inverse() {
        OneToOne o2o = mock(OneToOne.class);
        when(o2o.mappedBy()).thenReturn("owner");
        when(attr.getAnnotation(OneToOne.class)).thenReturn(o2o);
        assertThat(processor.supports(attr)).isFalse();
    }

    @Test
    void supports_false_when_no_annotations() {
        assertThat(processor.supports(attr)).isFalse();
    }

    // ---------- process(): ManyToOne happy path (no JoinColumn, single PK) ----------

    @Test
    void process_manyToOne_happyPath_default_fk_and_index_added() {
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        // no explicit JoinColumn(s)
        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        processor.process(attr, owner);

        // FK column created on owner
        ColumnModel fk = owner.findColumn("owner_tbl", "partner_id");
        assertThat(fk).isNotNull();
        assertThat(fk.getJavaType()).isEqualTo("Long");
        assertThat(fk.isNullable()).isTrue(); // optional=true & no JoinColumn override

        // Relationship added
        assertThat(owner.getRelationships()).hasSize(1);
        RelationshipModel rel = owner.getRelationships().values().iterator().next();
        assertThat(rel.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
        assertThat(rel.getTableName()).isEqualTo("owner_tbl");
        assertThat(rel.getColumns()).containsExactly("partner_id");
        assertThat(rel.getReferencedTable()).isEqualTo("partner_tbl");
        assertThat(rel.getReferencedColumns()).containsExactly("id");
        assertThat(rel.isNoConstraint()).isFalse();

        // Index auto-created
        assertThat(owner.getIndexes()).hasSize(1);
        IndexModel ix = owner.getIndexes().values().iterator().next();
        assertThat(ix.getTableName()).isEqualTo("owner_tbl");
        assertThat(ix.getColumnNames()).containsExactly("partner_id");

        // no errors
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), any());
    }

    // ---------- process(): OneToOne happy path -> UNIQUE added automatically ----------

    @Test
    void process_oneToOne_happyPath_adds_unique_when_single_fk_and_no_MapsId() {
        OneToOne o2o = mock(OneToOne.class);
        when(o2o.mappedBy()).thenReturn("");
        when(o2o.optional()).thenReturn(true);
        when(o2o.cascade()).thenReturn(new CascadeType[0]);
        when(o2o.fetch()).thenReturn(FetchType.EAGER);
        when(o2o.orphanRemoval()).thenReturn(false);
        when(attr.getAnnotation(OneToOne.class)).thenReturn(o2o);

        // no JoinColumn(s), no @MapsId
        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(attr.getAnnotation(MapsId.class)).thenReturn(null);

        processor.process(attr, owner);

        // UNIQUE constraint expected
        String uqName = new DefaultNaming(63).uqName("owner_tbl", List.of("partner_id"));
        assertThat(owner.getConstraints()).containsKey(uqName);
        ConstraintModel uq = owner.getConstraints().get(uqName);
        assertThat(uq.getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(uq.getColumns()).containsExactly("partner_id");
    }

    // ---------- Errors: composite PK but no JoinColumns ----------

    @Test
    void process_error_when_referenced_has_composite_pk_and_no_joinColumns() {
        // referenced composite PK 2개
        when(context.findAllPrimaryKeyColumns(referenced)).thenReturn(List.of(
                ColumnModel.builder().tableName("partner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("partner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Composite primary key"), eq(diagEl));
        assertThat(owner.isValid()).isFalse();
        assertThat(owner.getRelationships()).isEmpty();
    }

    // ---------- Errors: JoinColumns size mismatch ----------

    @Test
    void process_error_when_joinColumns_size_mismatch() {
        // referenced composite PK 2개
        when(context.findAllPrimaryKeyColumns(referenced)).thenReturn(List.of(
                ColumnModel.builder().tableName("partner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("partner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        JoinColumn onlyOne = mock(JoinColumn.class);
        when(onlyOne.name()).thenReturn("fk_a");
        when(onlyOne.referencedColumnName()).thenReturn("a");
        ForeignKey fk = mock(ForeignKey.class);
        when(fk.name()).thenReturn("");
        when(fk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(onlyOne.foreignKey()).thenReturn(fk);

        JoinColumns jcs = mock(JoinColumns.class);
        when(jcs.value()).thenReturn(new JoinColumn[]{onlyOne});
        when(attr.getAnnotation(JoinColumns.class)).thenReturn(jcs);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@JoinColumns size mismatch"), eq(diagEl));
        assertThat(owner.isValid()).isFalse();
    }

    // ---------- Errors: duplicate FK column name in composite ----------

    @Test
    void process_error_when_duplicate_fk_names() {
        // referenced composite PK 2개
        when(context.findAllPrimaryKeyColumns(referenced)).thenReturn(List.of(
                ColumnModel.builder().tableName("partner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("partner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        JoinColumn jc1 = mock(JoinColumn.class);
        when(jc1.referencedColumnName()).thenReturn("a");
        when(jc1.name()).thenReturn("dup_fk");
        when(jc1.table()).thenReturn("");
        ForeignKey f1 = mock(ForeignKey.class);
        when(f1.name()).thenReturn("");
        when(f1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc1.foreignKey()).thenReturn(f1);

        JoinColumn jc2 = mock(JoinColumn.class);
        when(jc2.referencedColumnName()).thenReturn("b");
        when(jc2.name()).thenReturn("dup_fk"); // duplicate
        when(jc2.table()).thenReturn("");
        ForeignKey f2 = mock(ForeignKey.class);
        when(f2.name()).thenReturn("");
        when(f2.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc2.foreignKey()).thenReturn(f2);

        JoinColumns jcs = mock(JoinColumns.class);
        when(jcs.value()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(attr.getAnnotation(JoinColumns.class)).thenReturn(jcs);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Duplicate foreign key column name"), eq(diagEl));
        assertThat(owner.getRelationships()).isEmpty();
    }

    // ---------- Errors: type mismatch with existing column ----------

    @Test
    void process_error_when_existing_column_type_conflict() {
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        // 기존에 같은 이름의 컬럼이 있으나 타입이 다름
        owner.putColumn(ColumnModel.builder()
                .tableName("owner_tbl").columnName("partner_id").javaType("String").isNullable(true).build());

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for column 'partner_id'"), eq(diagEl));
        assertThat(owner.isValid()).isFalse();
    }

    // ---------- Warning: optional=false & @JoinColumn(nullable=true) ----------

    @Test
    void process_warns_when_optional_false_but_joinColumn_nullable_true_and_forces_not_null() {
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(false); // optional=false
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.referencedColumnName()).thenReturn("");
        when(jc.name()).thenReturn(""); // will generate default name partner_id
        when(jc.nullable()).thenReturn(true); // conflicts with optional=false
        ForeignKey fk = mock(ForeignKey.class);
        when(fk.name()).thenReturn("");
        when(fk.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc.foreignKey()).thenReturn(fk);
        when(jc.table()).thenReturn(""); // base table

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(jc);

        processor.process(attr, owner);

        // warning emitted
        verify(messager).printMessage(eq(Diagnostic.Kind.WARNING),
                contains("conflicts with optional=false; treating as NOT NULL"), eq(diagEl));

        ColumnModel fkCol = owner.findColumn("owner_tbl", "partner_id");
        assertThat(fkCol).isNotNull();
        assertThat(fkCol.isNullable()).isFalse(); // 강제 NOT NULL
    }

    // ---------- Errors: composite JoinColumns target different tables ----------

    @Test
    void process_error_when_composite_joinColumns_point_to_different_tables() {
        // referenced composite PK 2개
        when(context.findAllPrimaryKeyColumns(referenced)).thenReturn(List.of(
                ColumnModel.builder().tableName("partner_tbl").columnName("a").javaType("Long").isPrimaryKey(true).build(),
                ColumnModel.builder().tableName("partner_tbl").columnName("b").javaType("Long").isPrimaryKey(true).build()
        ));

        // owner 에 secondary table 하나 등록했다고 가정 (resolveJoinColumnTable 비교용)
        owner.getSecondaryTables().add(SecondaryTableModel.builder().name("owner_sec").build());

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        JoinColumn jc1 = mock(JoinColumn.class);
        when(jc1.referencedColumnName()).thenReturn("a");
        when(jc1.name()).thenReturn("fk_a");
        when(jc1.table()).thenReturn(""); // resolves to owner_tbl
        when(jc1.nullable()).thenReturn(true);
        ForeignKey f1 = mock(ForeignKey.class);
        when(f1.name()).thenReturn("");
        when(f1.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc1.foreignKey()).thenReturn(f1);

        JoinColumn jc2 = mock(JoinColumn.class);
        when(jc2.referencedColumnName()).thenReturn("b");
        when(jc2.name()).thenReturn("fk_b");
        when(jc2.table()).thenReturn("owner_sec"); // resolves to secondary
        when(jc2.nullable()).thenReturn(true);
        ForeignKey f2 = mock(ForeignKey.class);
        when(f2.name()).thenReturn("");
        when(f2.value()).thenReturn(ConstraintMode.CONSTRAINT);
        when(jc2.foreignKey()).thenReturn(f2);

        JoinColumns jcs = mock(JoinColumns.class);
        when(jcs.value()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(attr.getAnnotation(JoinColumns.class)).thenReturn(jcs);

        processor.process(attr, owner);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("must target the same table"), eq(diagEl));
        assertThat(owner.isValid()).isFalse();
    }

    // ---------- NO_CONSTRAINT propagates ----------

    @Test
    void process_sets_noConstraint_when_foreignKey_value_is_NO_CONSTRAINT() {
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.referencedColumnName()).thenReturn("");
        when(jc.name()).thenReturn("");
        when(jc.nullable()).thenReturn(true);
        when(jc.table()).thenReturn("");

        ForeignKey fk = mock(ForeignKey.class);
        when(fk.name()).thenReturn("");
        when(fk.value()).thenReturn(ConstraintMode.NO_CONSTRAINT);
        when(jc.foreignKey()).thenReturn(fk);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(jc);

        processor.process(attr, owner);

        RelationshipModel rel = owner.getRelationships().values().iterator().next();
        assertThat(rel.isNoConstraint()).isTrue();
    }

    // ---------- Deferred Processing: Referenced Entity Not Yet Processed ----------

    @Test
    void process_defers_when_referenced_entity_not_found() {
        // Setup: Remove referenced entity from schema (simulating it hasn't been processed yet)
        entities.clear();

        // Setup deferred tracking
        Queue<EntityModel> deferredQueue = new LinkedList<>();
        Set<String> deferredNames = new HashSet<>();
        when(context.getDeferredEntities()).thenReturn(deferredQueue);
        when(context.getDeferredNames()).thenReturn(deferredNames);

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        processor.process(attr, owner);

        // Verify: Entity was deferred
        assertThat(deferredQueue).contains(owner);
        assertThat(deferredNames).contains(owner.getEntityName());

        // Verify: NOTE message was printed
        verify(messager).printMessage(eq(Diagnostic.Kind.NOTE),
                contains("Deferring FK generation"), eq(diagEl));
        verify(messager).printMessage(eq(Diagnostic.Kind.NOTE),
                contains("referenced entity not yet processed"), eq(diagEl));

        // Verify: No relationship or FK column was created
        assertThat(owner.getRelationships()).isEmpty();
        assertThat(owner.findColumn("owner_tbl", "partner_id")).isNull();
    }

    @Test
    void process_defers_only_once_when_referenced_entity_not_found() {
        // Setup: Remove referenced entity from schema
        entities.clear();

        // Setup deferred tracking with existing deferred name
        Queue<EntityModel> deferredQueue = new LinkedList<>();
        Set<String> deferredNames = new HashSet<>();
        deferredNames.add(owner.getEntityName()); // Already deferred
        when(context.getDeferredEntities()).thenReturn(deferredQueue);
        when(context.getDeferredNames()).thenReturn(deferredNames);

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        processor.process(attr, owner);

        // Verify: Entity was NOT added again to the queue
        assertThat(deferredQueue).isEmpty();

        // Verify: Still in deferredNames
        assertThat(deferredNames).contains(owner.getEntityName());
    }

    // ---------- Deferred Processing: Skip Already Processed Relationships ----------

    @Test
    void process_skips_when_relationship_already_processed() {
        // Setup: Add a relationship that has already been processed
        RelationshipModel existingRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("owner_tbl")
                .columns(List.of("partner_id"))
                .referencedTable("partner_tbl")
                .referencedColumns(List.of("id"))
                .sourceAttributeName("partner") // Same attribute name
                .constraintName("fk_owner_tbl__partner_id__partner_tbl__id")
                .build();
        owner.getRelationships().put(existingRel.getConstraintName(), existingRel);

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        int initialRelationshipCount = owner.getRelationships().size();

        processor.process(attr, owner);

        // Verify: No new relationship was added (still just 1)
        assertThat(owner.getRelationships()).hasSize(initialRelationshipCount);

        // Verify: No error or warning messages (silent skip)
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), any());
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.WARNING), anyString(), any());

        // Note: FK index may be checked/added for existing relationships (safe operation)
    }

    @Test
    void process_succeeds_after_referenced_entity_becomes_available() {
        // Scenario: First call - referenced entity missing (deferred)
        // Second call - referenced entity available (successful processing)

        // Setup deferred tracking
        Queue<EntityModel> deferredQueue = new LinkedList<>();
        Set<String> deferredNames = new HashSet<>();
        when(context.getDeferredEntities()).thenReturn(deferredQueue);
        when(context.getDeferredNames()).thenReturn(deferredNames);

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(m2o.fetch()).thenReturn(FetchType.EAGER);
        when(attr.getAnnotation(ManyToOne.class)).thenReturn(m2o);

        when(attr.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(attr.getAnnotation(JoinColumn.class)).thenReturn(null);

        // FIRST CALL: Referenced entity not available
        entities.clear();
        processor.process(attr, owner);

        assertThat(deferredQueue).contains(owner);
        assertThat(owner.getRelationships()).isEmpty();

        // SECOND CALL: Referenced entity now available
        entities.put("com.example.Partner", referenced);
        processor.process(attr, owner);

        // Verify: Relationship was successfully created
        assertThat(owner.getRelationships()).hasSize(1);
        RelationshipModel rel = owner.getRelationships().values().iterator().next();
        assertThat(rel.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
        assertThat(rel.getReferencedTable()).isEqualTo("partner_tbl");

        // Verify: FK column was created
        ColumnModel fk = owner.findColumn("owner_tbl", "partner_id");
        assertThat(fk).isNotNull();
        assertThat(fk.getJavaType()).isEqualTo("Long");
    }
}
