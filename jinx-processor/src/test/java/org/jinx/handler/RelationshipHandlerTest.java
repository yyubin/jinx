package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

import static org.assertj.core.condition.AllOf.allOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipHandlerTest {

    @Mock private ProcessingContext context;
    @Mock private SchemaModel schemaModel;
    @Mock private Naming namingStrategy;
    @Mock private javax.annotation.processing.Messager messager;

    private RelationshipHandler handler;
    private EntityModel ownerEntity;
    private EntityModel targetEntity;
    private Map<String, EntityModel> entities;

    private Types types;
    private Elements elements;

    @BeforeEach
    void setUp() {
        handler = new RelationshipHandler(context);
        types = mock(Types.class);
        elements = mock(Elements.class);

        lenient().when(context.getTypeUtils()).thenReturn(types);
        lenient().when(context.getElementUtils()).thenReturn(elements);

        TypeElement collectionElem = mock(TypeElement.class);
        TypeMirror collectionMirror = mock(TypeMirror.class);

        lenient().when(elements.getTypeElement("java.util.Collection")).thenReturn(collectionElem);
        lenient().when(collectionElem.asType()).thenReturn(collectionMirror);

        lenient().when(types.erasure(any(TypeMirror.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(types.isAssignable(any(TypeMirror.class), any(TypeMirror.class))).thenReturn(true);

        // 기본 엔티티 모델 설정
        ownerEntity = EntityModel.builder()
                .entityName("Owner")
                .tableName("owner_table")
                .build();

        targetEntity = EntityModel.builder()
                .entityName("Target")
                .tableName("target_table")
                .build();

        entities = new HashMap<>();
        entities.put("com.example.Owner", ownerEntity);
        entities.put("com.example.Target", targetEntity);

        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(schemaModel.getEntities()).thenReturn(entities);
        lenient().when(context.getNaming()).thenReturn(namingStrategy);
        lenient().when(context.getMessager()).thenReturn(messager);

        // 기본 네이밍 전략 설정
        lenient().when(namingStrategy.foreignKeyColumnName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));
        lenient().when(namingStrategy.joinTableName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));
        lenient().when(namingStrategy.fkName(anyString(), any(List.class), anyString(), any(List.class)))
                .thenAnswer(inv -> "fk_" + inv.getArgument(0) + "_" + inv.getArgument(2));
    }

    @Test
    void testManyToOneRelationship_SimplePrimaryKey() {
        // Given
        // 1. Owner TypeElement 모킹
        TypeElement ownerTypeElement = mockTypeElement("com.example.Owner");

        // 2. Field(VariableElement) 및 그 이름 모킹
        VariableElement field = mockField("targetField");
        Name fieldName = mock(Name.class);
        when(fieldName.toString()).thenReturn("targetField");
        when(field.getSimpleName()).thenReturn(fieldName); // <<-- [FIX #2] getSimpleName() 모킹

        // 3. Owner가 해당 필드를 포함하도록 설정
        doReturn(List.of(field)).when(ownerTypeElement).getEnclosedElements();

        mockManyToOneAnnotation(field, true); // optional = true

        // 타겟 엔티티에 단일 PK 설정
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);

        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity); // 수정된 ownerTypeElement 사용

        // Then
        // FK 컬럼이 소유 엔티티에 추가되었는지 확인
        String expectedFkName = "targetField_id";
        assertTrue(ownerEntity.getColumns().containsKey(expectedFkName),
                "FK column '" + expectedFkName + "' should exist in ownerEntity");
        ColumnModel fkColumn = ownerEntity.getColumns().get(expectedFkName);
        assertEquals("Long", fkColumn.getJavaType());
        assertFalse(fkColumn.isPrimaryKey());
        assertTrue(fkColumn.isNullable()); // optional = true

        // 관계 모델이 추가되었는지 확인
        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(RelationshipType.MANY_TO_ONE, relationship.getType());
        assertEquals(List.of(expectedFkName), relationship.getColumns());
        assertEquals("target_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOneRelationship_CompositePrimaryKey_WithJoinColumns() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false); // optional = false

        // 타겟 엔티티에 복합 PK 설정
        ColumnModel targetPk1 = ColumnModel.builder()
                .columnName("id1")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        ColumnModel targetPk2 = ColumnModel.builder()
                .columnName("id2")
                .javaType("String")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);

        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk1, targetPk2));

        // @JoinColumns 설정
        JoinColumns joinColumnsAnno = mockJoinColumnsAnnotation(field,
                new String[]{"fk_id1", "fk_id2"},
                new String[]{"id1", "id2"});

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // 두 개의 FK 컬럼이 추가되었는지 확인
        assertTrue(ownerEntity.getColumns().containsKey("fk_id1"));
        assertTrue(ownerEntity.getColumns().containsKey("fk_id2"));

        ColumnModel fk1 = ownerEntity.getColumns().get("fk_id1");
        ColumnModel fk2 = ownerEntity.getColumns().get("fk_id2");

        assertEquals("Long", fk1.getJavaType());
        assertEquals("String", fk2.getJavaType());
        assertFalse(fk1.isNullable()); // optional = false
        assertFalse(fk2.isNullable());

        // 관계 모델 확인
        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(List.of("fk_id1", "fk_id2"), relationship.getColumns());
        assertEquals(List.of("id1", "id2"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOneRelationship_CompositePrimaryKey_NoJoinColumns_ShouldFail() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true);

        // 타겟 엔티티에 복합 PK 설정하지만 @JoinColumns 없음
        ColumnModel targetPk1 = ColumnModel.builder()
                .columnName("id1")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        ColumnModel targetPk2 = ColumnModel.builder()
                .columnName("id2")
                .javaType("String")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);

        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk1, targetPk2));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Composite primary key on Target requires explicit @JoinColumns"), eq(field));

        // FK 컬럼이 추가되지 않았는지 확인
        assertTrue(ownerEntity.getColumns().isEmpty());
        assertTrue(ownerEntity.getRelationships().isEmpty());
    }

    @Test
    void testManyToOneRelationship_WithMapsId() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);

        // @MapsId 설정
        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn(""); // 전체 ID 공유
        when(field.getAnnotation(MapsId.class)).thenReturn(mapsId);
        when(field.getAnnotation(ManyToOne.class)).thenReturn(mock(ManyToOne.class));
        when(field.getAnnotation(OneToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToMany.class)).thenReturn(null);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);

        // 타겟 엔티티 PK 설정
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);

        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // FK 컬럼이 PK로 승격되었는지 확인
        String expectedFkName = "targetField_id";
        assertTrue(ownerEntity.getColumns().containsKey(expectedFkName));
        ColumnModel fkColumn = ownerEntity.getColumns().get(expectedFkName);
        assertTrue(fkColumn.isPrimaryKey());
        assertFalse(fkColumn.isNullable());

        // 관계에서 mapsId 플래그 확인
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertTrue(relationship.isMapsId());
    }

    @Test
    void testOneToManyRelationship_ForeignKey() {
        // Given
        VariableElement field = mockField("targetList");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        mockJoinColumnAnnotation(field, "owner_id", "id", true);
        mockCollectionFieldType(field, "com.example.Target");

        // 소유 엔티티 PK 설정
        ColumnModel ownerPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        ownerEntity.getColumns().put("id", ownerPk);

        when(context.findAllPrimaryKeyColumns(ownerEntity))
                .thenReturn(List.of(ownerPk));

        // 타겟 엔티티 설정 (Collection<Target>의 Target)
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // 타겟 엔티티에 FK 컬럼이 추가되었는지 확인
        assertTrue(targetEntity.getColumns().containsKey("owner_id"));
        ColumnModel fkColumn = targetEntity.getColumns().get("owner_id");
        assertEquals("Long", fkColumn.getJavaType());
        assertFalse(fkColumn.isPrimaryKey());
        assertTrue(fkColumn.isNullable());

        // 타겟 엔티티에 관계가 추가되었는지 확인
        assertEquals(1, targetEntity.getRelationships().size());
        RelationshipModel relationship = targetEntity.getRelationships().values().iterator().next();
        assertEquals(RelationshipType.ONE_TO_MANY, relationship.getType());
        assertEquals(List.of("owner_id"), relationship.getColumns());
        assertEquals("owner_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testOneToManyRelationship_JoinTable() {
        // Given
        VariableElement field = mockField("targetList");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockCollectionFieldType(field, "com.example.Target");
        mockOneToManyAnnotation(field);
        mockJoinTableAnnotation(field, "owner_target_join",
                new String[]{"owner_id"}, new String[]{"target_id"});

        // 소유 및 타겟 엔티티 PK 설정
        setupSinglePrimaryKeys();

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // 조인 테이블이 생성되었는지 확인
        assertTrue(entities.containsKey("owner_target_join"));
        EntityModel joinTable = entities.get("owner_target_join");
        assertEquals(EntityModel.TableType.JOIN_TABLE, joinTable.getTableType());

        // 조인 테이블에 FK 컬럼들이 PK로 추가되었는지 확인
        assertTrue(joinTable.getColumns().containsKey("owner_id"));
        assertTrue(joinTable.getColumns().containsKey("target_id"));

        ColumnModel ownerFk = joinTable.getColumns().get("owner_id");
        ColumnModel targetFk = joinTable.getColumns().get("target_id");

        assertTrue(ownerFk.isPrimaryKey());
        assertTrue(targetFk.isPrimaryKey());
        assertFalse(ownerFk.isNullable());
        assertFalse(targetFk.isNullable());

        // 조인 테이블에 두 개의 FK 관계가 있는지 확인
        assertEquals(2, joinTable.getRelationships().size());
    }

    @Test
    void testOneToManyRelationship_JoinTableAndJoinColumn_ShouldFail() {
        // Given
        VariableElement field = mockField("targetList");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        when(field.getAnnotation(JoinTable.class)).thenReturn(mock(JoinTable.class));
        when(field.getAnnotation(JoinColumn.class)).thenReturn(mock(JoinColumn.class));
        OneToMany oneToMany = mock(OneToMany.class);
        when(field.getAnnotation(OneToMany.class)).thenReturn(oneToMany);
        when(oneToMany.mappedBy()).thenReturn("");

        ColumnModel ownerPk = ColumnModel.builder()
                .columnName("owner_id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        ownerEntity.getColumns().put("owner_id", ownerPk);

        assertNotNull(field.getAnnotation(jakarta.persistence.JoinTable.class));
        assertNotNull(field.getAnnotation(jakarta.persistence.JoinColumn.class));
        assertNotNull(field.getAnnotation(jakarta.persistence.OneToMany.class));

        lenient().when(context.findAllPrimaryKeyColumns(ownerEntity))
                .thenReturn(List.of(ownerPk));

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@OneToMany에 @JoinTable과 @JoinColumn(s)를 함께 사용할 수 없습니다"),
                eq(field));
    }

    @Test
    void testOneToMany_FK_NoOwnerPk_ShouldError() {
        VariableElement field = mockField("targetList");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        mockJoinColumnAnnotation(field, "fk_col", "ref_col", true);
        mockCollectionFieldType(field, "com.example.Target");

        // owner PK 모킹 안 함 → empty
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of());

        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                eq("Entity " + ownerEntity.getEntityName() + " must have a primary key for @OneToMany relationship."),
                eq(field));
    }


    @Test
    void testManyToManyRelationship() {
        // Given
        VariableElement field = mockField("targetList");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field);
        mockJoinTableAnnotation(field, "owner_target",
                new String[]{"owner_id"}, new String[]{"target_id"});

        setupSinglePrimaryKeys();
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // 조인 테이블 생성 확인
        assertTrue(entities.containsKey("owner_target"));
        EntityModel joinTable = entities.get("owner_target");

        // PK 컬럼들 확인
        assertTrue(joinTable.getColumns().containsKey("owner_id"));
        assertTrue(joinTable.getColumns().containsKey("target_id"));

        ColumnModel ownerFk = joinTable.getColumns().get("owner_id");
        ColumnModel targetFk = joinTable.getColumns().get("target_id");

        assertTrue(ownerFk.isPrimaryKey());
        assertTrue(targetFk.isPrimaryKey());
        assertEquals("Long", ownerFk.getJavaType());
        assertEquals("Long", targetFk.getJavaType());

        // FK 관계들 확인
        assertEquals(2, joinTable.getRelationships().size());

        List<RelationshipModel> relationships = new ArrayList<>(joinTable.getRelationships().values());
        boolean hasOwnerRelation = relationships.stream()
                .anyMatch(r -> r.getReferencedTable().equals("owner_table"));
        boolean hasTargetRelation = relationships.stream()
                .anyMatch(r -> r.getReferencedTable().equals("target_table"));

        assertTrue(hasOwnerRelation);
        assertTrue(hasTargetRelation);
    }

    @Test
    void testManyToManyRelationship_DuplicateJoinTable_ShouldNotCreateTwice() {
        // Given
        VariableElement field1 = mockField("targets1");
        VariableElement field2 = mockField("targets2");

        mockManyToManyAnnotation(field1);
        mockManyToManyAnnotation(field2);

        // 같은 이름의 조인 테이블
        mockJoinTableAnnotation(field1, "same_join_table", new String[]{"owner_id"}, new String[]{"target_id"});
        mockJoinTableAnnotation(field2, "same_join_table", new String[]{"owner_id"}, new String[]{"target_id"});

        setupSinglePrimaryKeys();
        mockCollectionFieldType(field1, "com.example.Target");
        mockCollectionFieldType(field2, "com.example.Target");

        // 두 필드를 가진 TypeElement 모킹
        TypeElement ownerTypeElement = mock(TypeElement.class);
        doReturn(List.of(field1, field2)).when(ownerTypeElement).getEnclosedElements();

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // 조인 테이블이 한 번만 생성되었는지 확인
        assertTrue(entities.containsKey("same_join_table"));

        // putIfAbsent 호출 확인 (두 번째 호출에서는 추가되지 않음)
        verify(schemaModel, atLeast(1)).getEntities();
    }

    @Test
    void testTypeConflict_ShouldReportError() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true);

        String expectedFkName = "targetField_id";

        // 기존에 다른 타입의 컬럼이 있는 상황
        ColumnModel existingColumn = ColumnModel.builder()
                .columnName(expectedFkName)
                .javaType("String") // 충돌: Long이 와야 하는데 String
                .build();
        ownerEntity.getColumns().put(expectedFkName, existingColumn);

        // 타겟 엔티티 PK는 Long 타입
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);

        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for column '" + expectedFkName + "'"), eq(field));
    }

    @Test
    void testMissingPrimaryKey_ShouldReportError() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true);

        // 타겟 엔티티에 PK가 없는 상황
        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(Collections.emptyList());

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Entity Target must have a primary key to be referenced"), eq(field));
    }

    // Helper Methods
    private void setupSinglePrimaryKeys() {
        ColumnModel ownerPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();

        ownerEntity.getColumns().put("id", ownerPk);
        targetEntity.getColumns().put("id", targetPk);

        lenient().when(context.findAllPrimaryKeyColumns(ownerEntity))
                .thenReturn(List.of(ownerPk));
        when(context.findAllPrimaryKeyColumns(targetEntity))
                .thenReturn(List.of(targetPk));
    }

    @Test
    void testFieldWithTransient_ShouldBeIgnored() {
        // Given
        // @Transient 애노테이션을 가진 필드 모킹
        VariableElement transientField = mockField("transientField");
        when(transientField.getAnnotation(Transient.class)).thenReturn(mock(Transient.class));

        // 다른 정상적인 필드도
        VariableElement normalField = mockField("targetField");
        mockManyToOneAnnotation(normalField, true);
        mockFieldType(normalField, mockTypeElement("com.example.Target"));
        setupSinglePrimaryKeys(); // targetEntity에 PK 설정

        TypeElement ownerTypeElement = mockOwnerTypeElement(transientField, normalField);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(ownerEntity.getColumns().containsKey("targetField_id"));
        assertEquals(1, ownerEntity.getRelationships().size());

        // transientField에 대해서는 아무 작업도 일어나지 않았음을 확인
    }

    @Test
    void testBidirectionalOneToMany_ShouldBeIgnored() {
        // Given
        VariableElement mappedByField = mockField("ownerField");

        // mappedBy 속성에 값이 있는 @OneToMany 애노테이션을 모킹
        OneToMany oneToMany = mock(OneToMany.class);
        when(oneToMany.mappedBy()).thenReturn("owner"); // 양방향 관계 설정
        when(mappedByField.getAnnotation(OneToMany.class)).thenReturn(oneToMany);
        when(mappedByField.getAnnotation(ManyToOne.class)).thenReturn(null);
        when(mappedByField.getAnnotation(OneToOne.class)).thenReturn(null);
        when(mappedByField.getAnnotation(ManyToMany.class)).thenReturn(null);

        TypeElement ownerTypeElement = mockOwnerTypeElement(mappedByField);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // mappedBy 필드는 무시되어야 하므로, 어떤 컬럼이나 관계도 추가되지 않아야 함
        assertTrue(ownerEntity.getColumns().isEmpty());
        assertTrue(ownerEntity.getRelationships().isEmpty());
    }

    @Test
    void testManyToOne_WithMapsId_WithValue() {
        // Given
        VariableElement field = mockField("childPk");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false);

        // @MapsId("id1") 설정. Child의 PK 일부가 Parent의 PK 일부와 매핑됨을 의미
        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn("id1");
        when(field.getAnnotation(MapsId.class)).thenReturn(mapsId);

        // 참조되는 엔티티는 복합 키를 가짐
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        // @JoinColumns로 명시적 매핑
        mockJoinColumnsAnnotation(field, new String[]{"parent_id1", "parent_id2"}, new String[]{"id1", "id2"});
        mockFieldType(field, mockTypeElement("com.example.Target"));

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        // @MapsId("id1")에 의해 id1을 참조하는 FK(parent_id1)는 PK로 승격
        ColumnModel fk1 = ownerEntity.getColumns().get("parent_id1");
        assertTrue(fk1.isPrimaryKey());
        assertFalse(fk1.isNullable());

        // @MapsId와 관련 없는 다른 FK(parent_id2)는 일반 컬럼
        ColumnModel fk2 = ownerEntity.getColumns().get("parent_id2");
        assertFalse(fk2.isPrimaryKey());
    }

    @Test
    void testManyToMany_WithDefaultJoinTableColumnNames() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        setupSinglePrimaryKeys(); // owner, target에 'id'라는 PK 설정

        // joinColumns/inverseJoinColumns가 비어있는 @JoinTable 모킹
        JoinTable joinTable = mock(JoinTable.class);
        when(joinTable.name()).thenReturn("default_join_table");
        when(joinTable.joinColumns()).thenReturn(new JoinColumn[0]); // 비어있음
        when(joinTable.inverseJoinColumns()).thenReturn(new JoinColumn[0]); // 비어있음
        when(field.getAnnotation(JoinTable.class)).thenReturn(joinTable);

        mockManyToManyAnnotation(field);
        mockCollectionFieldType(field, "com.example.Target");

        // 기본 네이밍 전략 모킹 (setUp에서 이미 설정했지만 명확성을 위해 재확인)
        when(namingStrategy.foreignKeyColumnName("owner_table", "id")).thenReturn("owner_fk");
        when(namingStrategy.foreignKeyColumnName("target_table", "id")).thenReturn("target_fk");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(entities.containsKey("default_join_table"));
        EntityModel joinTableEntity = entities.get("default_join_table");

        // 네이밍 전략에 따라 기본 이름으로 컬럼이 생성되었는지 확인
        assertTrue(joinTableEntity.getColumns().containsKey("owner_fk"));
        assertTrue(joinTableEntity.getColumns().containsKey("target_fk"));
    }

    @Test
    void testOneToMany_FK_JoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);

        // Owner 복합 PK 2개
        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));
        when(field.getAnnotation(JoinTable.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);

        // hasJoinColumn=true를 만들기 위해 @JoinColumns 1개만 제공 → 불일치 유도
        mockJoinColumnsAnnotation(field, new String[]{"fk_id1"}, new String[]{"id1"});

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@JoinColumns size mismatch"), eq(field));
    }

    @Test
    void testOneToMany_FK_ImplicitFkTypeMismatch_ShouldReportError() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);

        // hasJoinColumn=true (하지만 name/referencedColumnName은 비워 기본 네이밍을 쓰게)
        mockJoinColumnAnnotation(field, "", "", true);

        // Owner PK (Long)
        ColumnModel ownerPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id", ownerPk);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk));

        // 기본 네이밍: owner_table_id
        when(namingStrategy.foreignKeyColumnName(ownerEntity.getTableName(), "id")).thenReturn("owner_table_id");

        // Target에 동일 이름의 컬럼이 이미 있는데 타입은 String → 타입 충돌 기대
        targetEntity.getColumns().put("owner_table_id",
                ColumnModel.builder().columnName("owner_table_id").javaType("String").build());

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for implicit foreign key column 'owner_table_id'"), eq(field));
    }

    @Test
    void testOneToMany_JoinTable_OwnerJoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);

        // JoinTable에 owner측 1개만, target측 1개 제공
        mockJoinTableAnnotation(field, "jt_owner_mismatch",
                new String[]{"owner_id"}, new String[]{"target_id"});

        // Owner는 복합 PK 2개 → 불일치 유발
        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));

        // Target은 단일 PK
        ColumnModel targetPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("JoinTable.joinColumns 개수가 Owner PK 개수와 일치해야 합니다"), eq(field));
    }

    @Test
    void testOneToMany_JoinTable_InverseJoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);

        // JoinTable에 owner측 1개, target측 1개만 제공 → target 복합 PK와 불일치 유발
        mockJoinTableAnnotation(field, "jt_target_mismatch",
                new String[]{"owner_id"}, new String[]{"target_id"});

        // Owner 단일 PK
        ColumnModel ownerPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id", ownerPk);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk));

        // Target 복합 PK 2개
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("Long").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("JoinTable.inverseJoinColumns 개수가 Target PK 개수와 일치해야 합니다"), eq(field));
    }

    @Test
    void testManyToMany_OwnerJoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field);

        // JoinTable에 owner측 1개만, target측 1개 제공
        mockJoinTableAnnotation(field, "mtm_owner_mismatch",
                new String[]{"owner_id"}, new String[]{"target_id"});

        // Owner 복합 PK 2개 → 불일치
        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));

        // Target 단일 PK
        ColumnModel targetPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("The number of @JoinColumn annotations for " + ownerEntity.getTableName()),
                eq(field));
    }

    @Test
    void testManyToMany_InverseJoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field);

        // JoinTable에 owner측 1개, target측 1개만 제공 → target 복합 PK와 불일치 유발
        mockJoinTableAnnotation(field, "mtm_target_mismatch",
                new String[]{"owner_id"}, new String[]{"target_id"});

        // Owner 단일 PK
        ColumnModel ownerPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id", ownerPk);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk));

        // Target 복합 PK 2개
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("Long").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("The number of @InverseJoinColumn annotations for " + targetEntity.getTableName()),
                eq(field));
    }


    private VariableElement mockField(String name) {
        VariableElement field = mock(VariableElement.class);
        Name fieldName = mock(Name.class);
        lenient().when(fieldName.toString()).thenReturn(name);
        lenient().when(field.getSimpleName()).thenReturn(fieldName);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(field.getModifiers()).thenReturn(Collections.emptySet());
        when(field.getAnnotation(Transient.class)).thenReturn(null);
        return field;
    }

    private TypeElement mockOwnerTypeElement() {
        TypeElement typeElement = mock(TypeElement.class);
        VariableElement field = mockField("targetField");
        doReturn(List.of(field)).when(typeElement).getEnclosedElements();
        return typeElement;
    }

    private TypeElement mockOwnerTypeElement(VariableElement... fields) {
        TypeElement typeElement = mock(TypeElement.class);
        doReturn(Arrays.asList(fields)).when(typeElement).getEnclosedElements();
        return typeElement;
    }

    private TypeElement mockTypeElement(String qualifiedName) {
        TypeElement typeElement = mock(TypeElement.class);
        Name name = mock(Name.class);
        lenient().when(name.toString()).thenReturn(qualifiedName);
        lenient().when(typeElement.getQualifiedName()).thenReturn(name);
        return typeElement;
    }

    private void mockFieldType(VariableElement field, TypeElement targetType) {
        DeclaredType declaredType = mock(DeclaredType.class);
        when(declaredType.asElement()).thenReturn(targetType);
        when(field.asType()).thenReturn(declaredType);
    }

    private void mockCollectionFieldType(VariableElement field, String targetTypeName) {
        DeclaredType collectionType = mock(DeclaredType.class);
        DeclaredType targetType = mock(DeclaredType.class);
        TypeElement targetElement = mockTypeElement(targetTypeName);

        lenient().when(targetType.asElement()).thenReturn(targetElement);
        lenient().doReturn(List.of(targetElement)).when(targetElement).getEnclosedElements();
        DeclaredType targetGenericType = mock(DeclaredType.class);
        lenient().when(targetGenericType.asElement()).thenReturn(targetElement);
        lenient().doReturn(List.of(targetGenericType)).when(collectionType).getTypeArguments();
        lenient().when(field.asType()).thenReturn(collectionType);
    }

    private void mockManyToOneAnnotation(VariableElement field, boolean optional) {
        ManyToOne manyToOne = mock(ManyToOne.class);
        lenient().when(manyToOne.optional()).thenReturn(optional);
        lenient().when(manyToOne.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(manyToOne.fetch()).thenReturn(FetchType.EAGER);
        when(field.getAnnotation(ManyToOne.class)).thenReturn(manyToOne);

        // 다른 어노테이션들은 null
        when(field.getAnnotation(OneToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToMany.class)).thenReturn(null);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(null);
    }

    private void mockOneToManyAnnotation(VariableElement field) {
        OneToMany oneToMany = mock(OneToMany.class);
        lenient().when(oneToMany.mappedBy()).thenReturn(""); // 단방향
        lenient().when(oneToMany.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(oneToMany.fetch()).thenReturn(FetchType.LAZY);
        lenient().when(oneToMany.orphanRemoval()).thenReturn(false);
        when(field.getAnnotation(OneToMany.class)).thenReturn(oneToMany);

        // 다른 어노테이션들은 null
        when(field.getAnnotation(ManyToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToOne.class)).thenReturn(null);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(null);
    }

    private void mockManyToManyAnnotation(VariableElement field) {
        ManyToMany manyToMany = mock(ManyToMany.class);
        when(manyToMany.mappedBy()).thenReturn(""); // 소유측
        lenient().when(manyToMany.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(manyToMany.fetch()).thenReturn(FetchType.LAZY);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(manyToMany);

        // 다른 어노테이션들은 null
        when(field.getAnnotation(ManyToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToMany.class)).thenReturn(null);
    }

    private void mockJoinColumnAnnotation(VariableElement field, String name, String referencedColumnName, boolean nullable) {
        JoinColumn joinColumn = mock(JoinColumn.class);
        lenient().when(joinColumn.name()).thenReturn(name);
        lenient().when(joinColumn.referencedColumnName()).thenReturn(referencedColumnName);
        lenient().when(joinColumn.nullable()).thenReturn(nullable);

        ForeignKey foreignKey = mock(ForeignKey.class);
        lenient().when(foreignKey.name()).thenReturn("");
        lenient().when(joinColumn.foreignKey()).thenReturn(foreignKey);

        when(field.getAnnotation(JoinColumn.class)).thenReturn(joinColumn);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);
        lenient().when(field.getAnnotation(JoinTable.class)).thenReturn(null);
    }

    private JoinColumns mockJoinColumnsAnnotation(VariableElement field, String[] names, String[] referencedColumnNames) {
        JoinColumn[] joinColumns = new JoinColumn[names.length];
        for (int i = 0; i < names.length; i++) {
            JoinColumn jc = mock(JoinColumn.class);
            lenient().when(jc.name()).thenReturn(names[i]);
            lenient().when(jc.referencedColumnName()).thenReturn(referencedColumnNames[i]);
            lenient().when(jc.nullable()).thenReturn(true);

            ForeignKey fk = mock(ForeignKey.class);
            lenient().when(fk.name()).thenReturn("");
            lenient().when(jc.foreignKey()).thenReturn(fk);

            joinColumns[i] = jc;
        }

        JoinColumns joinColumnsAnno = mock(JoinColumns.class);
        when(joinColumnsAnno.value()).thenReturn(joinColumns);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(joinColumnsAnno);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);

        return joinColumnsAnno;
    }

    private void mockJoinTableAnnotation(VariableElement field, String tableName,
                                         String[] joinColumnNames, String[] inverseJoinColumnNames) {
        jakarta.persistence.JoinColumn[] joinColumns = new jakarta.persistence.JoinColumn[joinColumnNames.length];
        for (int i = 0; i < joinColumnNames.length; i++) {
            jakarta.persistence.JoinColumn jc = mock(jakarta.persistence.JoinColumn.class);
            lenient().when(jc.name()).thenReturn(joinColumnNames[i]);
            lenient().when(jc.referencedColumnName()).thenReturn("");
            jakarta.persistence.ForeignKey fk = mock(jakarta.persistence.ForeignKey.class);
            lenient().when(fk.name()).thenReturn("");
            lenient().when(jc.foreignKey()).thenReturn(fk);
            joinColumns[i] = jc;
        }

        jakarta.persistence.JoinColumn[] inverseJoinColumns = new jakarta.persistence.JoinColumn[inverseJoinColumnNames.length];
        for (int i = 0; i < inverseJoinColumnNames.length; i++) {
            jakarta.persistence.JoinColumn jc = mock(jakarta.persistence.JoinColumn.class);
            lenient().when(jc.name()).thenReturn(inverseJoinColumnNames[i]);
            lenient().when(jc.referencedColumnName()).thenReturn("");
            jakarta.persistence.ForeignKey fk = mock(jakarta.persistence.ForeignKey.class);
            lenient().when(fk.name()).thenReturn("");
            lenient().when(jc.foreignKey()).thenReturn(fk);
            inverseJoinColumns[i] = jc;
        }

        jakarta.persistence.JoinTable joinTable = mock(jakarta.persistence.JoinTable.class);
        lenient().when(joinTable.name()).thenReturn(tableName);
        lenient().when(joinTable.joinColumns()).thenReturn(joinColumns);
        lenient().when(joinTable.inverseJoinColumns()).thenReturn(inverseJoinColumns);

        lenient().when(field.getAnnotation(jakarta.persistence.JoinTable.class)).thenReturn(joinTable);
    }


    /*
     * Additional tests appended by automation.
     * Note: Test framework: JUnit 5 (org.junit.jupiter.*) with Mockito (org.mockito.junit.jupiter.MockitoExtension).
     */

    @Test
    void testManyToOneRelationship_WithExplicitJoinColumn() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false); // optional = false
        // Explicit FK name and referenced column
        mockJoinColumnAnnotation(field, "explicit_fk", "id", false);

        // Target has single PK (id: Long)
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(ownerEntity.getColumns().containsKey("explicit_fk"), "Explicit FK column should be created");
        ColumnModel fk = ownerEntity.getColumns().get("explicit_fk");
        assertEquals("Long", fk.getJavaType());
        assertFalse(fk.isNullable(), "Explicit nullable=false should result in NOT NULL FK");

        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(List.of("explicit_fk"), relationship.getColumns());
        assertEquals("target_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOne_ExistingMatchingColumn_Reused_NoError() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true); // optional = true

        // Existing FK column with matching type should be reused (no error)
        String expectedFkName = "targetField_id";
        ownerEntity.getColumns().put(expectedFkName, ColumnModel.builder()
                .columnName(expectedFkName)
                .javaType("Long")
                .build());

        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), eq(field));
        assertFalse(ownerEntity.getRelationships().isEmpty(), "Relationship should be created");
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertTrue(relationship.getColumns().contains(expectedFkName),
                "Existing matching FK column should be referenced by the relationship");
        // Ensure the reused column type remains intact
        assertEquals("Long", ownerEntity.getColumns().get(expectedFkName).getJavaType());
    }

    @Test
    void testManyToMany_NoJoinTableAnnotation_UsesDefaultNaming() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field); // No @JoinTable provided

        // Configure single-column PKs for both sides
        setupSinglePrimaryKeys();
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        String expectedJoinTable = namingStrategy.joinTableName(ownerEntity.getTableName(), targetEntity.getTableName());
        assertTrue(entities.containsKey(expectedJoinTable),
                "Default join table should be created when @JoinTable is absent");
        EntityModel joinTable = entities.get(expectedJoinTable);

        String ownerFkName = namingStrategy.foreignKeyColumnName(ownerEntity.getTableName(), "id");
        String targetFkName = namingStrategy.foreignKeyColumnName(targetEntity.getTableName(), "id");

        assertTrue(joinTable.getColumns().containsKey(ownerFkName));
        assertTrue(joinTable.getColumns().containsKey(targetFkName));

        ColumnModel ownerFk = joinTable.getColumns().get(ownerFkName);
        ColumnModel targetFk = joinTable.getColumns().get(targetFkName);

        assertTrue(ownerFk.isPrimaryKey());
        assertTrue(targetFk.isPrimaryKey());
        assertFalse(ownerFk.isNullable());
        assertFalse(targetFk.isNullable());
        assertEquals(2, joinTable.getRelationships().size(), "Join table should have two FK relationships");
    }

    @Test
    void testOneToOneRelationship_SimplePrimaryKey() {
        // Given
        VariableElement field = mockField("targetRef");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToOneAnnotation(field, true); // optional = true
        // No explicit @JoinColumn -> default naming

        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        String expectedFkName = "targetRef_id";
        assertTrue(ownerEntity.getColumns().containsKey(expectedFkName));
        ColumnModel fkColumn = ownerEntity.getColumns().get(expectedFkName);
        assertEquals("Long", fkColumn.getJavaType());
        assertTrue(fkColumn.isNullable(), "optional=true should allow null");

        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(List.of(expectedFkName), relationship.getColumns());
        assertEquals("target_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOne_JoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false);

        // Target has composite PK (2 columns)
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        // Provide only one @JoinColumn -> size mismatch with composite PK
        mockJoinColumnsAnnotation(field, new String[]{"fk_id1"}, new String[]{"id1"});

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@JoinColumns size mismatch"), eq(field));
        assertTrue(ownerEntity.getColumns().isEmpty(), "No FK should be added on mismatch");
        assertTrue(ownerEntity.getRelationships().isEmpty(), "No relationship should be added on mismatch");
    }

    // Helper to mock @OneToOne following existing conventions in this test class
    private void mockOneToOneAnnotation(VariableElement field, boolean optional) {
        OneToOne oneToOne = mock(OneToOne.class);
        lenient().when(oneToOne.optional()).thenReturn(optional);
        lenient().when(oneToOne.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(oneToOne.fetch()).thenReturn(FetchType.EAGER);
        when(field.getAnnotation(OneToOne.class)).thenReturn(oneToOne);

        // Ensure other relationship annotations are null to avoid ambiguity
        when(field.getAnnotation(ManyToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToMany.class)).thenReturn(null);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(null);

        // Default join annotations to null unless explicitly mocked
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);
        lenient().when(field.getAnnotation(JoinTable.class)).thenReturn(null);
        when(field.getAnnotation(MapsId.class)).thenReturn(null);
    }

    @Test
    void testOneToMany_FK_RawCollection_GenericMissing_ShouldErrorWithFieldName() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        // FK 경로로 타야 하므로 임의 @JoinColumn 하나 부여
        mockJoinColumnAnnotation(field, "owner_fk", "id", true);

        // raw 컬렉션: typeArguments 비어있게
        mockRawCollectionFieldType(field);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@OneToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다"),
                eq(field));
        // FK/관계가 생성되지 않아야 함
        assertTrue(targetEntity.getColumns().isEmpty());
        assertTrue(targetEntity.getRelationships().isEmpty());
    }

    @Test
    void testOneToMany_FK_NonDeclaredTypeArg_ShouldErrorWithFieldName() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        mockJoinColumnAnnotation(field, "owner_fk", "id", true);

        // 첫 번째 타입 인자가 DeclaredType이 아니게(와일드카드/프리미티브 유사) 모킹
        mockCollectionWithNonDeclaredArg(field);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@OneToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다"),
                eq(field));
        assertTrue(targetEntity.getColumns().isEmpty());
        assertTrue(targetEntity.getRelationships().isEmpty());
    }

    @Test
    void testManyToMany_RawCollection_GenericMissing_ShouldErrorWithFieldName() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field); // @ManyToMany 소유측

        // raw 컬렉션: typeArguments 비움
        mockRawCollectionFieldType(field);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                        contains("@ManyToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다"),
                eq(field));
        // 조인 테이블 생성 X
        assertEquals(2, schemaModel.getEntities().size()); // owner/target만
    }

    @Test
    void testOneToMany_FK_NormalGeneric_ShouldSucceed() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        mockJoinColumnAnnotation(field, "owner_fk", "id", true);

        // Owner PK
        ColumnModel ownerPk = ColumnModel.builder().columnName("id").javaType("Long").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id", ownerPk);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk));

        // 정상 제네릭(Collection<Target>)
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(targetEntity.getColumns().containsKey("owner_fk"));
        ColumnModel fk = targetEntity.getColumns().get("owner_fk");
        assertEquals("Long", fk.getJavaType());

        assertEquals(1, targetEntity.getRelationships().size());
        RelationshipModel rel = targetEntity.getRelationships().values().iterator().next();
        assertEquals(RelationshipType.ONE_TO_MANY, rel.getType());
        assertEquals(List.of("owner_fk"), rel.getColumns());
        assertEquals(ownerEntity.getTableName(), rel.getReferencedTable());
        assertEquals(List.of("id"), rel.getReferencedColumns());
    }

    // raw 컬렉션: typeArguments 비어있는 DeclaredType
    private void mockRawCollectionFieldType(VariableElement field) {
        DeclaredType collectionType = mock(DeclaredType.class);
        lenient().when(collectionType.getTypeArguments()).thenReturn(List.of());
        lenient().when(field.asType()).thenReturn(collectionType);
    }

    // 첫 타입 인자가 DeclaredType이 아닌 경우(와일드카드/프리미티브 유사) 모킹
    private void mockCollectionWithNonDeclaredArg(VariableElement field) {
        DeclaredType collectionType = mock(DeclaredType.class);
        // DeclaredType이 아닌 임의 TypeMirror 하나 넣기
        javax.lang.model.type.TypeMirror bogusArg = mock(javax.lang.model.type.TypeMirror.class);
        lenient().doReturn(List.of(bogusArg)).when(collectionType).getTypeArguments();
        lenient().when(field.asType()).thenReturn(collectionType);
    }

    @Test
    void testManyToOne_CompositeKey_TypeMismatch_ShouldErrorInvalidate_NoMutation() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false); // optional = false

        // Target 복합 PK
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        // @JoinColumns 명시 + 첫 FK 이름은 "fk_id1"
        mockJoinColumnsAnnotation(field, new String[]{"fk_id1", "fk_id2"}, new String[]{"id1", "id2"});

        // 충돌 유발: owner에 "fk_id1"이 이미 있고 타입이 String(원래 Long이어야 함)
        ownerEntity.getColumns().put("fk_id1",
                ColumnModel.builder().columnName("fk_id1").javaType("String").build());

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for column 'fk_id1'"),
                eq(field));

        assertFalse(ownerEntity.isValid(), "타입 충돌 시 ownerEntity가 invalid 처리되어야 함");
        // 새 컬럼/관계 미생성 (기존 충돌 컬럼 1개만 존재)
        assertEquals(1, ownerEntity.getColumns().size());
        assertTrue(ownerEntity.getRelationships().isEmpty());
    }

    @Test
    void testManyToOne_CompositeKey_DuplicateFkNames_ShouldError_NoMutation() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false);

        // Target 복합 PK
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        // 중복 FK 이름 두 개 모두 "dup_fk"
        mockJoinColumnsAnnotation(field, new String[]{"dup_fk", "dup_fk"}, new String[]{"id1", "id2"});

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Duplicate FK column name 'dup_fk'"),
                eq(field));

        assertFalse(ownerEntity.isValid(), "중복 FK 이름이면 ownerEntity invalid");
        assertTrue(ownerEntity.getColumns().isEmpty(), "컬럼 미생성");
        assertTrue(ownerEntity.getRelationships().isEmpty(), "관계 미생성");
    }

    @Test
    void testManyToOne_CompositeKey_Normal_AppliedOnceOnly() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true);

        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        mockJoinColumnsAnnotation(field, new String[]{"fk_id1", "fk_id2"}, new String[]{"id1", "id2"});
        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When: 두 번 호출(중복 반영 방지 확인)
        handler.resolveRelationships(ownerTypeElement, ownerEntity);
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertEquals(2, ownerEntity.getColumns().size(), "복합키 FK 2개만 존재해야 함(중복 생성 X)");
        assertEquals(1, ownerEntity.getRelationships().size(), "관계는 1개만 존재(키로 덮어씀)");
        assertTrue(ownerEntity.getColumns().containsKey("fk_id1"));
        assertTrue(ownerEntity.getColumns().containsKey("fk_id2"));
    }

    @Test
    void testOneToMany_FK_CompositeKey_TypeMismatch_ShouldErrorInvalidate_NoMutation() {
        // 타입 유틸/엘리먼트 유틸 모킹(컬렉션 assignable 판정 통과용)
        mockTypeSystemAssignable();

        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);

        // Owner 복합 PK
        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));
        when(field.getAnnotation(JoinTable.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);

        // 명시적 @JoinColumns (FK 이름 고정)
        mockJoinColumnsAnnotation(field, new String[]{"fk_id1", "fk_id2"}, new String[]{"id1", "id2"});

        // Target에 첫 FK 이름과 동일하지만 타입 충돌 나도록 사전 존재시킴(String vs Long)
        targetEntity.getColumns().put("fk_id1",
                ColumnModel.builder().columnName("fk_id1").javaType("String").build());

        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Type mismatch for implicit foreign key column 'fk_id1'"),
                eq(field));

        assertFalse(targetEntity.isValid(), "타입 충돌 시 targetEntity invalid");
        // 기존 충돌 컬럼 1개만 있고, 새 컬럼/관계 미생성
        assertEquals(1, targetEntity.getColumns().size());
        assertTrue(targetEntity.getRelationships().isEmpty());
    }

    @Test
    void testOneToMany_FK_CompositeKey_DuplicateFkNames_ShouldError_NoMutation() {
        mockTypeSystemAssignable();

        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        when(field.getAnnotation(JoinTable.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);

        // Owner 복합 PK
        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));

        // 중복 FK 이름 두 개 모두 "dup_fk"
        mockJoinColumnsAnnotation(field, new String[]{"dup_fk", "dup_fk"}, new String[]{"id1", "id2"});
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Duplicate FK column name 'dup_fk'"),
                eq(field));

        assertFalse(targetEntity.isValid(), "중복 FK 이름이면 targetEntity invalid");
        assertTrue(targetEntity.getColumns().isEmpty(), "컬럼 미생성");
        assertTrue(targetEntity.getRelationships().isEmpty(), "관계 미생성");
    }

    @Test
    void testOneToMany_FK_CompositeKey_Normal_AppliedOnceOnly() {
        mockTypeSystemAssignable();

        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToManyAnnotation(field);
        when(field.getAnnotation(JoinTable.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);

        ColumnModel ownerPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel ownerPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        ownerEntity.getColumns().put("id1", ownerPk1);
        ownerEntity.getColumns().put("id2", ownerPk2);
        when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(ownerPk1, ownerPk2));

        mockJoinColumnsAnnotation(field, new String[]{"fk_id1", "fk_id2"}, new String[]{"id1", "id2"});
        mockCollectionFieldType(field, "com.example.Target");

        // When: 두 번 호출(중복 반영 방지 확인)
        handler.resolveRelationships(ownerTypeElement, ownerEntity);
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(targetEntity.getColumns().containsKey("fk_id1"));
        assertTrue(targetEntity.getColumns().containsKey("fk_id2"));
        assertEquals(2, targetEntity.getColumns().size(), "FK 2개만 존재(중복 생성 X)");
        assertEquals(1, targetEntity.getRelationships().size(), "관계 1개만 존재(키로 덮어씀)");
    }

    private void mockTypeSystemAssignable() {
        javax.lang.model.util.Types types = mock(javax.lang.model.util.Types.class);
        javax.lang.model.util.Elements elems = mock(javax.lang.model.util.Elements.class);
        when(context.getTypeUtils()).thenReturn(types);
        when(context.getElementUtils()).thenReturn(elems);

        // java.util.Collection 타입 element/타입 미러
        TypeElement collectionTe = mockTypeElement("java.util.Collection");
        when(elems.getTypeElement("java.util.Collection")).thenReturn(collectionTe);
        // erasure는 그대로 돌려주도록(단순화)
        when(types.erasure(any())).thenAnswer(inv -> inv.getArgument(0));
        // 컬렉션 판정은 항상 통과
        when(types.isAssignable(any(), any())).thenReturn(true);
    }


}