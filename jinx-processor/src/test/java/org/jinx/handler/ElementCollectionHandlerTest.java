package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.ColumnHandler;
import org.jinx.handler.EmbeddedHandler;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.HashMap;
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
    private Map<String, EntityModel> entitiesMap; // << [FIX-1] 실제 HashMap 대신 Mock Map 사용

    @Captor
    private ArgumentCaptor<EntityModel> entityModelCaptor;

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

        elementCollectionHandler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);
    }

    private EntityModel createMockOwnerEntity() {
        EntityModel owner = EntityModel.builder()
                .entityName("User")
                .tableName("users")
                .build();
        ColumnModel pk = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();
        owner.putColumn(pk);
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(pk));
        return owner;
    }

    // [FIX-2] 테스트에서 공통적으로 필요한 'null' 어노테이션 모킹을 미리 설정하는 헬퍼 메서드
    private void setupDefaultAnnotationMocks(AttributeDescriptor attribute) {
        when(attribute.getAnnotation(CollectionTable.class)).thenReturn(null);
        when(attribute.getAnnotation(Column.class)).thenReturn(null);
        when(attribute.getAnnotation(OrderColumn.class)).thenReturn(null);
        when(attribute.getAnnotation(MapKeyColumn.class)).thenReturn(null);
        when(attribute.getAnnotation(MapKeyEnumerated.class)).thenReturn(null);
        when(attribute.getAnnotation(MapKeyTemporal.class)).thenReturn(null);
    }


    @Test
    @DisplayName("기본적인 Set<String> 타입의 ElementCollection 처리 테스트")
    void processElementCollection_withBasicSet_shouldCreateCorrectTableAndColumns() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();
        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("tags");

        // [FIX-2] 어노테이션 기본 모킹 설정
        setupDefaultAnnotationMocks(attribute);

        DeclaredType setType = mock(DeclaredType.class);
        TypeMirror stringType = mock(TypeMirror.class);
        when(stringType.toString()).thenReturn("java.lang.String");
        doReturn(List.of(stringType)).when(setType).getTypeArguments();
        when(attribute.type()).thenReturn(setType);

        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(setType, "java.util.List")).thenReturn(false);

        when(naming.fkName(any(), any(), any(), any())).thenReturn("FK_users_tags_users");

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        // [FIX-1] 검증 대상을 Mock Map으로 변경
        verify(entitiesMap).putIfAbsent(eq("users_tags"), entityModelCaptor.capture());
        EntityModel collectionEntity = entityModelCaptor.getValue();

        assertEquals("users_tags", collectionEntity.getTableName());
        assertEquals(EntityModel.TableType.COLLECTION_TABLE, collectionEntity.getTableType());
        assertEquals(2, collectionEntity.getColumns().size());

        ColumnModel fkColumn = collectionEntity.getColumns().get("users_id");
        assertNotNull(fkColumn);
        assertTrue(fkColumn.isPrimaryKey());
        assertFalse(fkColumn.isNullable());
        assertEquals("java.lang.Long", fkColumn.getJavaType());

        ColumnModel valueColumn = collectionEntity.getColumns().get("tags");
        assertNotNull(valueColumn);
        assertTrue(valueColumn.isPrimaryKey());
        assertEquals("java.lang.String", valueColumn.getJavaType());

        assertEquals(1, collectionEntity.getRelationships().size());
        RelationshipModel relationship = collectionEntity.getRelationships().get("FK_users_tags_users");
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
        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("scores");

        // [FIX-2] 어노테이션 기본 모킹 설정
        setupDefaultAnnotationMocks(attribute);

        // 테스트에 필요한 어노테이션만 재정의
        OrderColumn orderColumnAnnotation = mock(OrderColumn.class);
        when(orderColumnAnnotation.name()).thenReturn("score_order");
        when(attribute.getAnnotation(OrderColumn.class)).thenReturn(orderColumnAnnotation);

        DeclaredType listType = mock(DeclaredType.class);
        TypeMirror intType = mock(TypeMirror.class);
        when(intType.toString()).thenReturn("java.lang.Integer");
        doReturn(List.of(intType)).when(listType).getTypeArguments();
        when(attribute.type()).thenReturn(listType);

        when(context.isSubtype(listType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(listType, "java.util.List")).thenReturn(true);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        // [FIX-1] 검증 대상을 Mock Map으로 변경
        verify(entitiesMap).putIfAbsent(eq("users_scores"), entityModelCaptor.capture());
        EntityModel collectionEntity = entityModelCaptor.getValue();

        assertEquals(3, collectionEntity.getColumns().size());

        ColumnModel orderColumn = collectionEntity.getColumns().get("score_order");
        assertNotNull(orderColumn);
        assertTrue(orderColumn.isPrimaryKey());
        assertEquals("java.lang.Integer", orderColumn.getJavaType());
    }

    @Test
    @DisplayName("Map<String, String>과 @MapKeyColumn 처리 테스트")
    void processElementCollection_withMapAndMapKeyColumn_shouldCreateMapKeyColumn() {
        // Arrange
        EntityModel ownerEntity = createMockOwnerEntity();
        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        when(attribute.name()).thenReturn("attributes");

        // [FIX-2] 어노테이션 기본 모킹 설정
        setupDefaultAnnotationMocks(attribute);

        // 테스트에 필요한 어노테이션만 재정의
        MapKeyColumn mapKeyColumnAnnotation = mock(MapKeyColumn.class);
        when(mapKeyColumnAnnotation.name()).thenReturn("attr_key");
        when(attribute.getAnnotation(MapKeyColumn.class)).thenReturn(mapKeyColumnAnnotation);

        DeclaredType mapType = mock(DeclaredType.class);
        TypeMirror stringType = mock(TypeMirror.class);
        when(stringType.toString()).thenReturn("java.lang.String");
        doReturn(List.of(stringType, stringType)).when(mapType).getTypeArguments();
        when(attribute.type()).thenReturn(mapType);

        when(context.isSubtype(mapType, "java.util.Map")).thenReturn(true);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerEntity);

        // Assert
        // [FIX-1] 검증 대상을 Mock Map으로 변경
        verify(entitiesMap).putIfAbsent(eq("users_attributes"), entityModelCaptor.capture());
        EntityModel collectionEntity = entityModelCaptor.getValue();

        assertEquals(3, collectionEntity.getColumns().size());

        ColumnModel mapKeyColumn = collectionEntity.getColumns().get("attr_key");
        assertNotNull(mapKeyColumn);
        assertTrue(mapKeyColumn.isPrimaryKey());
        assertTrue(mapKeyColumn.isMapKey());
        assertEquals("java.lang.String", mapKeyColumn.getJavaType());

        assertNotNull(collectionEntity.getColumns().get("attributes"));
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

        // [FIX-1] 테이블이 생성되고 스키마에 등록되는지 확인
        verify(entitiesMap).putIfAbsent(eq("users_addresses"), any(EntityModel.class));
    }

    @Test
    @DisplayName("소유자 엔티티에 PK가 없을 때 에러 처리 테스트")
    void processElementCollection_whenOwnerHasNoPrimaryKey_shouldLogError() {
        // Arrange
        EntityModel ownerWithoutPk = EntityModel.builder().entityName("Orphan").build();
        when(context.findAllPrimaryKeyColumns(ownerWithoutPk)).thenReturn(Collections.emptyList());

        AttributeDescriptor attribute = mock(AttributeDescriptor.class);
        VariableElement fieldElement = mock(VariableElement.class);
        when(attribute.elementForDiagnostics()).thenReturn(fieldElement);

        // Act
        elementCollectionHandler.processElementCollection(attribute, ownerWithoutPk);

        // Assert
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains("must have a primary key for @ElementCollection"),
                eq(fieldElement)
        );

        // [FIX-1] Mock Map에 아무것도 추가되지 않았는지 검증
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }
}