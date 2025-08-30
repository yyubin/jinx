package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.NamingTestUtil;
import org.jinx.testing.util.SchemaCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElementCollectionHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private ColumnHandler columnHandler;
    @Mock
    private EmbeddedHandler embeddedHandler;
    @Mock
    private Types typeUtils;
    @Mock
    private Elements elementUtils;
    @Mock
    private Messager messager;
    @Mock
    private SchemaModel schemaModel;
    @Mock
    private Naming naming;
    @Mock
    private Map<String, EntityModel> entitiesMap;

    private ElementCollectionHandler elementCollectionHandler;

    @BeforeEach
    void setUp() {
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getNaming()).thenReturn(naming);
        // << [FIX-1] schemaModel.getEntities()가 Mock Map을 반환하도록 설정
        lenient().when(schemaModel.getEntities()).thenReturn(entitiesMap);

        // fkName 기본 스텁 (검증에서 null/blank 방지)
        lenient().when(naming.fkName(any(), anyList(), any(), anyList()))
                .thenAnswer(inv -> {
                    String t = (String) inv.getArgument(0);
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) inv.getArgument(1);
                    String rt = (String) inv.getArgument(2);
                    @SuppressWarnings("unchecked")
                    List<String> rcols = (List<String>) inv.getArgument(3);
                    return org.jinx.testing.util.NamingTestUtil.fk(t, cols, rt, rcols);
                });

        elementCollectionHandler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);

    }

    private EntityModel createMockOwnerEntity() {
        EntityModel owner = EntityModelMother.usersWithPkIdLong();
        // EntityModel의 키 형태는 "tableName::columnName"이므로 getColumn 메서드 사용
        ColumnModel idColumn = owner.findColumn("users", "id");
        List<ColumnModel> pkColumns = List.of(idColumn);
        lenient().when(context.findAllPrimaryKeyColumns(owner)).thenReturn(pkColumns);
        return owner;
    }

    @Test
    @DisplayName("기본적인 Set<String> 타입의 ElementCollection 처리 테스트")
    void processElementCollection_withBasicSet_shouldCreateCorrectTableAndColumns() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");
        DeclaredType setType = (DeclaredType) attribute.type();
        
        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(setType, "java.util.List")).thenReturn(false);
        
        String expectedFkName = NamingTestUtil.fk("users_tags", List.of("users_id"), "users", List.of("id"));
        when(naming.fkName(any(), any(), any(), any())).thenReturn(expectedFkName);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        EntityModel collectionEntity = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_tags");

        assertEquals("users_tags", collectionEntity.getTableName());
        assertEquals(EntityModel.TableType.COLLECTION_TABLE, collectionEntity.getTableType());
        assertEquals(2, collectionEntity.getColumns().size());

        ColumnAssertions.assertPkNonNull(collectionEntity, "users_tags::users_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collectionEntity, "users_tags::tags", "java.lang.String");

        assertEquals(1, collectionEntity.getRelationships().size());
        RelationshipModel relationship = collectionEntity.getRelationships().get(expectedFkName);
        assertNotNull(relationship);
        assertEquals(RelationshipType.ELEMENT_COLLECTION, relationship.getType());
        assertEquals("users_tags", relationship.getTableName());
        assertEquals(List.of("users_id"), relationship.getColumns());
        assertEquals("users", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    @DisplayName("List<Integer>와 @OrderColumn 처리 테스트")
    void processElementCollection_withListAndOrderColumn_shouldCreateOrderColumn() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        OrderColumn orderColumn = mock(OrderColumn.class);
        when(orderColumn.name()).thenReturn("score_order");

        AttributeDescriptor attribute = AttributeDescriptorFactory.listOf("java.lang.Integer", "scores", orderColumn);

        DeclaredType listType = (DeclaredType) attribute.type();
        when(context.isSubtype(listType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(listType, "java.util.List")).thenReturn(true);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        EntityModel collectionEntity = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_scores");

        assertEquals(3, collectionEntity.getColumns().size(),
                "cols=" + collectionEntity.getColumns().keySet());

        // order는 PK
        ColumnAssertions.assertPkNonNull(collectionEntity, "users_scores::score_order", "java.lang.Integer");
        // 값 컬럼은 PK 아님
        ColumnAssertions.assertNonPkWithType(collectionEntity, "users_scores::scores", "java.lang.Integer");
    }


    @Test
    @DisplayName("Map<String, String>과 @MapKeyColumn 처리 테스트")
    void processElementCollection_withMapAndMapKeyColumn_shouldCreateMapKeyColumn() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();
        
        MapKeyColumn mapKeyColumnAnnotation = mock(MapKeyColumn.class);
        when(mapKeyColumnAnnotation.name()).thenReturn("attr_key");
        
        AttributeDescriptor attribute = AttributeDescriptorFactory.mapOf("java.lang.String", "java.lang.String", "attributes", mapKeyColumnAnnotation);
        DeclaredType mapType = (DeclaredType) attribute.type();

        when(context.isSubtype(mapType, "java.util.Map")).thenReturn(true);
        when(attribute.getAnnotation(MapKeyColumn.class)).thenReturn(mapKeyColumnAnnotation);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        EntityModel collectionEntity = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_attributes");

        assertEquals(3, collectionEntity.getColumns().size());
        
        ColumnModel mapKeyColumn = ColumnAssertions.assertPkNonNull(collectionEntity, "users_attributes::attr_key", "java.lang.String");
        assertTrue(mapKeyColumn.isMapKey());
        
        assertNotNull(collectionEntity.getColumns().get("users_attributes::attributes"));
    }

    @Test
    @DisplayName("Embeddable 값 타입을 가진 컬렉션 처리 테스트")
    void processElementCollection_withEmbeddableValue_shouldDelegateToEmbeddedHandler() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();
        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("addresses");

        // [FIX-2] 어노테이션 기본 모킹 설정 (이 테스트에서는 CollectionTable만 필요)
        when(attribute.getAnnotation(CollectionTable.class)).thenReturn(null);

        DeclaredType setType = mock(DeclaredType.class);
        TypeMirror embeddableTypeMirror = mock(TypeMirror.class);
        TypeElement embeddableElement = mock(TypeElement.class);
        Embeddable embeddableAnnotation = mock(Embeddable.class);

        doReturn(List.of(embeddableTypeMirror)).when(setType).getTypeArguments();
        when(attribute.type()).thenReturn(setType);
        when(typeUtils.asElement(embeddableTypeMirror)).thenReturn(embeddableElement);
        when(embeddableElement.getAnnotation(Embeddable.class)).thenReturn(embeddableAnnotation);

        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        verify(embeddedHandler).processEmbeddableFields(
                eq(embeddableElement),
                any(EntityModel.class),
                anySet(),
                isNull(),
                isNull()
        );

        // 테이블이 생성되고 스키마에 등록되는지 확인
        verify(entitiesMap).putIfAbsent(eq("users_addresses"), any(EntityModel.class));
    }

    @Test
    @DisplayName("소유자 엔티티에 PK가 없을 때 에러 처리 테스트")
    void processElementCollection_whenOwnerHasNoPrimaryKey_shouldLogError() {
        // Arrange
        EntityModel ownerWithoutPk = EntityModel.builder().tableName("orphan").entityName("Orphan").build();
        when(context.findAllPrimaryKeyColumns(ownerWithoutPk)).thenReturn(Collections.emptyList());

        VariableElement fieldElement = mock(VariableElement.class);
        AttributeDescriptor attribute = AttributeDescriptorFactory.withDiagnostic(
            AttributeDescriptorFactory.setOf("java.lang.String", "orphaned"), 
            fieldElement
        );

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerWithoutPk);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("must have a primary key for @ElementCollection"),
                eq(fieldElement)
        );

        // Mock Map에 아무것도 추가되지 않았는지 검증
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }

    @Test
    @DisplayName("[검증 실패] 값 컬럼명이 FK와 중복되면 롤백된다")
    void rollback_whenValueColumnNameDuplicatesFk() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        // Set<String> tags
        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");
        DeclaredType setType = (DeclaredType) attribute.type();
        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(setType, "java.util.List")).thenReturn(false);

        // 값 컬럼명을 FK(users_id)와 동일하게 강제
        Column columnAnn = mock(Column.class);
        when(columnAnn.name()).thenReturn("users_id");
        when(attribute.getAnnotation(Column.class)).thenReturn(columnAnn);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert: 에러 로그가 찍히고, 스키마 등록이 없어야 한다
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("Duplicate column name in collection table"),
                any() // element
        );
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }

    @Test
    @DisplayName("[검증 실패] owner tableName 누락 시 롤백된다")
    void rollback_whenOwnerTableNameMissing() {
        // Arrange
        EntityModel owner = EntityModel.builder()
                .entityName("User")
                .tableName(null) // intentionally missing
                .build();
        lenient().when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(ColumnModel.builder().columnName("id").javaType("java.lang.Long").isPrimaryKey(true).tableName(null).build())
        );

        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("tags");

        // Act
        elementCollectionHandler.processElementCollection(attribute, owner);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("Owner entity has no tableName"),
                any()
        );
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }
    @Test
    @DisplayName("[검증 실패] attribute name blank 시 롤백된다")
    void rollback_whenAttributeNameBlank() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("   "); // blank
        // type() 호출 전이라도 validate 단계에서 name 검사를 먼저 함
        // 안전하게 type()은 안 쓰게 두거나 필요 시 doReturn으로 선언만

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("Attribute name is blank"),
                any()
        );
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }

    @Test
    @DisplayName("[검증 실패] DeclaredType가 아니면 컬렉션 타입 판별 실패로 롤백")
    void rollback_whenNotDeclaredType() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("tags");
        // DeclaredType가 아닌 TypeMirror
        TypeMirror notDeclared = mock(TypeMirror.class);
        when(attribute.type()).thenReturn(notDeclared);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("Cannot determine collection type"),
                any()
        );
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }

    @Test
    @DisplayName("[검증 실패] @CollectionTable.joinColumns 개수 불일치 시 롤백")
    void rollback_whenJoinColumnsCountMismatch() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();

        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");
        DeclaredType setType = (DeclaredType) attribute.type();
        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        lenient().when(context.isSubtype(setType, "java.util.List")).thenReturn(false);

        // owner PK는 1개인데, joinColumns 2개로 설정 → 불일치 유도
        JoinColumn jc1 = mock(JoinColumn.class);
        lenient().when(jc1.name()).thenReturn("uid1");
        lenient().when(jc1.referencedColumnName()).thenReturn("id");

        JoinColumn jc2 = mock(JoinColumn.class);
        lenient().when(jc2.name()).thenReturn("uid2");
        lenient().when(jc2.referencedColumnName()).thenReturn("id");

        CollectionTable ct = mock(CollectionTable.class);
        when(ct.name()).thenReturn("users_tags");
        when(ct.joinColumns()).thenReturn(new JoinColumn[]{jc1, jc2});
        when(attribute.getAnnotation(CollectionTable.class)).thenReturn(ct);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("joinColumns size mismatch"),
                any()
        );
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }


}