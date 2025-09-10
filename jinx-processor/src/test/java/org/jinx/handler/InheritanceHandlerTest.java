package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.jinx.naming.Naming;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InheritanceHandlerTest {

    @Mock private ProcessingContext context;
    @Mock private Messager messager;
    @Mock private Elements elements;
    @Mock private Types types;
    @Mock private SchemaModel schemaModel;

    private InheritanceHandler handler;
    private Map<String, EntityModel> entities;

    @BeforeEach
    void setUp() {
        handler = new InheritanceHandler(context);
        when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getElementUtils()).thenReturn(elements);
        lenient().when(context.getTypeUtils()).thenReturn(types);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        entities = new HashMap<>();
        lenient().when(schemaModel.getEntities()).thenReturn(entities);
    }

    // ---------- SINGLE_TABLE ----------

    @Test
    @DisplayName("SINGLE_TABLE: DiscriminatorColumn/Value 반영 및 중복 방지")
    void singleTable_discriminator_added_and_duplicate_guard() {
        // === parentType 명시적 모킹 ===
        TypeElement parentType = mock(TypeElement.class);
        Name parentName = mock(Name.class);
        lenient().when(parentName.toString()).thenReturn("com.example.Parent");
        lenient().when(parentType.getQualifiedName()).thenReturn(parentName);

        // annotations
        when(parentType.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        when(parentType.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumn("dtype_col"));
        when(parentType.getAnnotation(DiscriminatorValue.class))
                .thenReturn(AnnotationProxies.discriminatorValue("P"));

        // model
        EntityModel parent = EntityModelMother.javaEntity("com.example.Parent", "parent");

        // act
        handler.resolveInheritance(parentType, parent);

        // assert
        assertEquals(InheritanceType.SINGLE_TABLE, parent.getInheritance());
        ColumnModel d = parent.findColumn(null, "dtype_col");
        assertNotNull(d);
        assertEquals("java.lang.String", d.getJavaType());
        assertFalse(d.isNullable());
        assertEquals("P", parent.getDiscriminatorValue());

        // duplicate guard: 같은 이름 컬럼이 이미 있으면 에러 + invalid
        EntityModel parent2 = EntityModelMother.javaEntity("com.example.Parent2", "parent2");
        parent2.putColumn(ColumnModel.builder().tableName("parent2").columnName("dtype").javaType("java.lang.String").build());

        // === parentType2 명시적 모킹 ===
        TypeElement parentType2 = mock(TypeElement.class);
        Name parentName2 = mock(Name.class);
        lenient().when(parentName2.toString()).thenReturn("com.example.Parent2");
        lenient().when(parentType2.getQualifiedName()).thenReturn(parentName2);
        when(parentType2.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        when(parentType2.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumn("dtype")); // 이미 존재
        when(parentType2.getAnnotation(DiscriminatorValue.class)).thenReturn(null);

        handler.resolveInheritance(parentType2, parent2);

        assertFalse(parent2.isValid());
        verify(messager, atLeastOnce()).printMessage(eq(Diagnostic.Kind.ERROR), contains("Duplicate column name 'dtype'"), any());
    }

    // ---------- JOINED ----------

    @Test
    @DisplayName("JOINED: 부모 PK 기준으로 자식 FK(PK) 컬럼 생성 + 관계(FK) 생성")
    void joined_child_pk_and_fk_relation_created() {
        // parent & child 엔티티 모델 준비
        EntityModel parent = EntityModelMother.javaEntityWithPkIdLong("com.example.Parent", "parent");
        entities.put(parent.getEntityName(), parent);
        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put(child.getEntityName(), child);

        // === TypeElement 및 관련 객체들 명시적 모킹 ===
        TypeElement parentType = mock(TypeElement.class);
        TypeMirror parentTypeMirror = mock(TypeMirror.class);
        Name parentName = mock(Name.class);
        lenient().when(parentName.toString()).thenReturn("com.example.Parent");
        lenient().when(parentType.getQualifiedName()).thenReturn(parentName);
        when(parentType.asType()).thenReturn(parentTypeMirror);
        when(parentType.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.JOINED));

        TypeElement childType = mock(TypeElement.class);
        TypeMirror childTypeMirror = mock(TypeMirror.class);
        when(childType.asType()).thenReturn(childTypeMirror);
        when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        when(types.isSubtype(childTypeMirror, parentTypeMirror)).thenReturn(true);

        // PK 조회 모킹
        when(context.findAllPrimaryKeyColumns(parent))
                .thenReturn(List.of(parent.findColumn("parent","id")));

        // Naming 모킹
        var naming = mock(Naming.class);
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(eq("child"), eq(List.of("id")), eq("parent"), eq(List.of("id"))))
                .thenReturn("FK_child_parent");

        // act
        handler.resolveInheritance(parentType, parent);

        // assert: child에 id(=PK, not null) 추가
        ColumnModel id = child.findColumn("child", "id");
        assertNotNull(id, "child.id must be created");
        assertTrue(id.isPrimaryKey());
        assertFalse(id.isNullable());
        assertEquals("java.lang.Long", id.getJavaType());

        // 관계 생성
        RelationshipModel fk = child.getRelationships().get("FK_child_parent");
        assertNotNull(fk);
        assertEquals(RelationshipType.JOINED_INHERITANCE, fk.getType());
        assertEquals("child", fk.getTableName());
        assertEquals(List.of("id"), fk.getColumns());
        assertEquals("parent", fk.getReferencedTable());
        assertEquals(List.of("id"), fk.getReferencedColumns());

        assertEquals(InheritanceType.JOINED, child.getInheritance());
        assertEquals(parent.getEntityName(), child.getParentEntity());
    }

    @Test
    @DisplayName("JOINED: @PrimaryKeyJoinColumns 개수 불일치 → IllegalState + child invalid")
    void joined_pkjoin_size_mismatch_marks_child_invalid() {
        EntityModel parent = EntityModelMother.javaEntityWithPkIdLong("com.example.Parent", "parent");
        entities.put(parent.getEntityName(), parent);
        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put(child.getEntityName(), child);

        // === TypeElement 명시적 모킹 ===
        TypeElement parentType = mock(TypeElement.class);
        TypeMirror parentTypeMirror = mock(TypeMirror.class);
        Name parentName = mock(Name.class);
        lenient().when(parentName.toString()).thenReturn("com.example.Parent");
        lenient().when(parentType.getQualifiedName()).thenReturn(parentName);
        when(parentType.asType()).thenReturn(parentTypeMirror);
        when(parentType.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.JOINED));

        TypeElement childType = mock(TypeElement.class);
        TypeMirror childTypeMirror = mock(TypeMirror.class);
        when(childType.asType()).thenReturn(childTypeMirror);
        when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        when(types.isSubtype(childTypeMirror, parentTypeMirror)).thenReturn(true);

        // parent PK 1개 모킹
        when(context.findAllPrimaryKeyColumns(parent))
                .thenReturn(List.of(parent.findColumn("parent","id")));

        // child에 @PrimaryKeyJoinColumns 2개(불일치) 설정
        PrimaryKeyJoinColumn p1 = AnnotationProxies.pkJoin("cid1", "id");
        PrimaryKeyJoinColumn p2 = AnnotationProxies.pkJoin("cid2", "id");
        when(childType.getAnnotation(PrimaryKeyJoinColumns.class))
                .thenReturn(AnnotationProxies.pkJoins(p1, p2));

        // act
        handler.resolveInheritance(parentType, parent);

        // assert: child invalid, 컬럼 미커밋
        assertFalse(child.isValid());
        assertNull(child.findColumn("child", "cid1"));
        assertTrue(child.getRelationships().isEmpty());
        verify(messager, atLeastOnce()).printMessage(eq(Diagnostic.Kind.ERROR), contains("PK mapping mismatch"));
    }

    @Test
    @DisplayName("JOINED: 자식에 동일명 컬럼이 있으나 타입/PK/NULL 조건 불일치 → 에러 후 미커밋")
    void joined_existing_child_column_mismatch() {
        EntityModel parent = EntityModelMother.javaEntityWithPkIdLong("com.example.Parent", "parent");
        entities.put(parent.getEntityName(), parent);

        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        child.putColumn(ColumnModel.builder() // child에 id 컬럼이 이미 존재하지만 nullable=true로 잘못 선언
                .tableName("child").columnName("id").javaType("java.lang.Long")
                .isPrimaryKey(false).isNullable(true).build());
        entities.put(child.getEntityName(), child);

        // === TypeElement 명시적 모킹 ===
        TypeElement parentType = mock(TypeElement.class);
        TypeMirror parentTypeMirror = mock(TypeMirror.class);
        Name parentName = mock(Name.class);
        lenient().when(parentName.toString()).thenReturn("com.example.Parent");
        lenient().when(parentType.getQualifiedName()).thenReturn(parentName);
        when(parentType.asType()).thenReturn(parentTypeMirror);
        when(parentType.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.JOINED));

        TypeElement childType = mock(TypeElement.class);
        TypeMirror childTypeMirror = mock(TypeMirror.class);
        when(childType.asType()).thenReturn(childTypeMirror);
        when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        when(types.isSubtype(childTypeMirror, parentTypeMirror)).thenReturn(true);
        when(childType.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(null);
        when(childType.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(null);

        when(context.findAllPrimaryKeyColumns(parent))
                .thenReturn(List.of(parent.findColumn("parent","id")));

        // act
        handler.resolveInheritance(parentType, parent);

        // 먼저 기본 FK 생성 WARNING 찍히는지
        verify(messager, atLeastOnce()).printMessage(
                eq(Diagnostic.Kind.WARNING),
                contains("creating a default foreign key"),
                eq(childType)
        );

        // 컬럼 조건 불일치 ERROR 찍히는지 (3-인자)
        verify(messager, atLeastOnce()).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("JOINED column mismatch"),
                eq(childType)
        );

        // assert: child invalid, 관계 없음, 컬럼 그대로(수정 X)
        assertFalse(child.isValid());
        assertTrue(child.getRelationships().isEmpty());
        ColumnModel still = child.findColumn("child","id");
        assertNotNull(still);
        assertTrue(still.isNullable());
        assertFalse(still.isPrimaryKey());
    }

    // ---------- TABLE_PER_CLASS ----------

    @Test
    @DisplayName("TABLE_PER_CLASS: 부모 컬럼/제약 복제 + IDENTITY 경고 출력")
    void tpc_copies_columns_and_constraints_and_warns_identity() {
        // parent with columns & constraint
        EntityModel parent = EntityModelMother.javaEntity("com.example.Parent", "parent");
        parent.putColumn(ColumnModel.builder()
                .tableName("parent").columnName("id").javaType("java.lang.Long")
                .isPrimaryKey(true).isNullable(false).build());
        parent.putColumn(ColumnModel.builder()
                .tableName("parent").columnName("name").javaType("java.lang.String")
                .isPrimaryKey(false).isNullable(true).build());
        parent.getConstraints().put("UK_parent_name", ConstraintModel.builder()
                .name("UK_parent_name").type(ConstraintType.UNIQUE)
                .columns(new ArrayList<>(List.of("name")))
                .build());
        entities.put(parent.getEntityName(), parent);

        EntityModel child = EntityModelMother.javaEntity("com.example.Child", "child");
        entities.put(child.getEntityName(), child);

        // === TypeElement 명시적 모킹 ===
        TypeElement parentType = mock(TypeElement.class);
        TypeMirror parentTypeMirror = mock(TypeMirror.class);
        Name parentName = mock(Name.class);
        lenient().when(parentName.toString()).thenReturn("com.example.Parent");
        lenient().when(parentType.asType()).thenReturn(parentTypeMirror);
        when(parentType.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.TABLE_PER_CLASS));

        TypeElement childType = mock(TypeElement.class);
        TypeMirror childTypeMirror = mock(TypeMirror.class);
        when(childType.asType()).thenReturn(childTypeMirror);
        when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        when(types.isSubtype(childTypeMirror, parentTypeMirror)).thenReturn(true);

        // child 필드에 ID + GeneratedValue(IDENTITY) -> WARNING 기대
        VariableElement idField = mock(VariableElement.class);
        when(idField.getKind()).thenReturn(ElementKind.FIELD);
        when(idField.getSimpleName()).thenReturn(NameStub.of("id"));
        when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        lenient().when(idField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(idField.getAnnotation(GeneratedValue.class)).thenReturn(AnnotationProxies.generated(GenerationType.IDENTITY));
        doReturn(List.of(idField)).when(childType).getEnclosedElements();

        // act
        handler.resolveInheritance(parentType, parent);

        // assert: child가 부모 컬럼/제약 복제 받음
        ColumnModel cId = child.findColumn("child", "id");
        ColumnModel cName = child.findColumn("child", "name");
        assertNotNull(cId);
        assertNotNull(cName);
        assertEquals("child", cId.getTableName());
        assertEquals("child", cName.getTableName());
        assertTrue(cId.isPrimaryKey());
        assertFalse(cId.isNullable());
        assertTrue(child.getConstraints().containsKey("UK_parent_name"));

        // WARNING 출력 확인
        verify(messager, atLeastOnce()).printMessage(eq(Diagnostic.Kind.WARNING), contains("IDENTITY"), eq(idField));
        assertEquals(InheritanceType.TABLE_PER_CLASS, child.getInheritance());
        assertEquals(parent.getEntityName(), child.getParentEntity());
    }

    // ---- helpers ----

    /** Name mocking helper (간단한 CharSequence 스텁) */
    static final class NameStub implements Name {
        private final String v;
        private NameStub(String v) { this.v = v; }
        static NameStub of(String v) { return new NameStub(v); }
        @Override public boolean contentEquals(CharSequence cs) { return v.contentEquals(cs); }
        @Override public int length() { return v.length(); }
        @Override public char charAt(int index) { return v.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return v.subSequence(start, end); }
        @Override public String toString() { return v; }
    }
}