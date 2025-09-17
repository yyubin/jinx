package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.naming.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.SchemaModel;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.jinx.testing.util.SchemaCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElementCollectionHandlerTest {

    // 테스트 대상
    @InjectMocks
    private ElementCollectionHandler handler;

    // Mock 객체
    @Mock
    private ProcessingContext context;
    @Mock
    private ColumnHandler columnHandler; // ElementCollectionHandler 내부에서 사용
    @Mock
    private EmbeddedHandler embeddedHandler; // ElementCollectionHandler 내부에서 사용
    @Mock
    private Types typeUtils;
    @Mock
    private Elements elementUtils;
    @Mock
    private Messager messager;
    @Mock
    private Naming naming;
    @Mock
    private SchemaModel schemaModel;
    @Mock
    private Map<String, EntityModel> entitiesMap;


    @BeforeEach
    void setUp() {
        // Mock 객체들의 기본 동작 설정
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getNaming()).thenReturn(naming);
        lenient().when(schemaModel.getEntities()).thenReturn(entitiesMap);

        when(context.isSubtype(any(DeclaredType.class), anyString())).thenAnswer(invocation -> {
            DeclaredType declaredType = invocation.getArgument(0);
            String superTypeName = invocation.getArgument(1);
            return declaredType != null && declaredType.toString().equals(superTypeName);
        });

        // NamingStrategy의 기본 동작 모의 설정
        lenient().when(naming.foreignKeyColumnName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));
        lenient().when(naming.fkName(anyString(), anyList(), anyString(), anyList()))
                .thenAnswer(inv -> "FK_" + inv.getArgument(0) + "_" + inv.getArgument(2));
    }

    @Test
    @DisplayName("가장 기본적인 Set<String> 타입의 ElementCollection을 처리하여 컬렉션 테이블을 생성한다")
    void process_BasicSetOfString_ShouldCreateCollectionTable() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User.java", "users");
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(owner.findColumn("users", "id"))
        );
        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");

        mockIsSubtype("java.util.Set", true);
        lenient().when(context.isSubtype(any(DeclaredType.class), eq("java.util.Set"))).thenReturn(true);

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collectionTable = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_tags");

        assertEquals(EntityModel.TableType.COLLECTION_TABLE, collectionTable.getTableType());
        assertEquals(2, collectionTable.getColumns().size(), "컬럼은 2개(FK, 요소)여야 합니다.");

        ColumnAssertions.assertPkNonNull(collectionTable, "users_tags", "users_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collectionTable, "users_tags", "tags", "java.lang.String");

        RelationshipAssertions.assertFkByStructure(
                collectionTable,
                "users_tags", List.of("users_id"),
                "users", List.of("id"),
                RelationshipType.ELEMENT_COLLECTION
        );
    }

    @Test
    @DisplayName("@OrderColumn이 적용된 List<String>를 처리하여 순서 컬럼을 PK로 추가한다")
    void process_ListOfStringWithOrderColumn_ShouldCreateOrderColumnAsPk() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Article", "articles");
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(owner.findColumn("articles", "id"))
        );
        OrderColumn orderAnn = AnnotationProxies.of(OrderColumn.class, Map.of("name", "line_order"));
        AttributeDescriptor attribute = AttributeDescriptorFactory.listOf("java.lang.String", "lines", orderAnn);

        mockIsSubtype("java.util.List", true);
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.List"))).thenReturn(true);

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collectionTable = SchemaCapture.capturePutIfAbsent(entitiesMap, "articles_lines");
        assertEquals(3, collectionTable.getColumns().size(), "컬럼은 3개(FK, 요소, 순서)여야 합니다.");

        ColumnAssertions.assertPkNonNull(collectionTable, "articles_lines", "articles_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collectionTable, "articles_lines", "line_order", "java.lang.Integer");
        ColumnAssertions.assertNonPkWithType(collectionTable, "articles_lines", "lines", "java.lang.String");
    }

    @Test
    @DisplayName("@Embeddable 타입을 원소로 가지는 List를 처리하여 임베디드 컬럼들을 펼친다")
    void process_ListOfEmbeddable_ShouldFlattenEmbeddableColumns() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Person", "people");
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(owner.findColumn("people", "id"))
        );
        AttributeDescriptor attribute = AttributeDescriptorFactory.listOf("com.ex.Address", "addresses");
        mockIsSubtype("java.util.List", true);
        mockIsEmbeddable("com.ex.Address", true);
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.List"))).thenReturn(true);

        // EmbeddedHandler가 호출될 때, 컬렉션 테이블에 컬럼을 추가하도록 모의 동작 설정
        doAnswer((Answer<Void>) invocation -> {
            EntityModel targetEntity = invocation.getArgument(1);
            targetEntity.putColumn(ColumnModel.builder().tableName(targetEntity.getTableName()).columnName("street").javaType("java.lang.String").build());
            targetEntity.putColumn(ColumnModel.builder().tableName(targetEntity.getTableName()).columnName("city").javaType("java.lang.String").build());
            return null;
        }).when(embeddedHandler).processEmbedded(any(), any(), anySet());

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collectionTable = SchemaCapture.capturePutIfAbsent(entitiesMap, "people_addresses");
        verify(embeddedHandler, times(1)).processEmbedded(any(), eq(collectionTable), anySet());
        assertEquals(3, collectionTable.getColumns().size(), "컬럼은 3개(FK, street, city)여야 합니다.");

        ColumnAssertions.assertPkNonNull(collectionTable, "people_addresses", "people_id", "java.lang.Long");
        ColumnAssertions.assertExists(collectionTable, "people_addresses", "street");
        ColumnAssertions.assertExists(collectionTable, "people_addresses", "city");
    }

    @Test
    @DisplayName("기본 타입을 키와 값으로 가지는 Map을 처리하여 키와 값 컬럼을 생성한다")
    void process_MapWithBasicTypes_ShouldCreateKeyAndValueColumns() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Product", "products");
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(owner.findColumn("products", "id"))
        );

        MapKeyColumn keyAnn = AnnotationProxies.of(MapKeyColumn.class, Map.of("name", "prop_key"));
        Column valAnn = AnnotationProxies.of(Column.class, Map.of("name", "prop_value"));
        AttributeDescriptor attribute = AttributeDescriptorFactory.mapOf("java.lang.Integer", "java.lang.String", "properties", keyAnn, valAnn);

        mockIsSubtype("java.util.Map", true);
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.Map"))).thenReturn(true);

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collectionTable = SchemaCapture.capturePutIfAbsent(entitiesMap, "products_properties");
        assertEquals(3, collectionTable.getColumns().size());

        ColumnAssertions.assertPkNonNull(collectionTable, "products_properties", "products_id", "java.lang.Long");
        var keyColumn = ColumnAssertions.assertPkNonNull(collectionTable, "products_properties", "prop_key", "java.lang.Integer");
        assertTrue(keyColumn.isMapKey(), "키 컬럼은 isMapKey 플래그가 true여야 합니다.");
        ColumnAssertions.assertNonPkWithType(collectionTable, "products_properties", "prop_value", "java.lang.String");
    }

    @Test
    @DisplayName("엔티티를 Map 키로 사용하는 경우 @MapKeyJoinColumn을 처리하여 FK를 생성한다")
    void process_MapWithEntityKey_ShouldCreateForeignKeyForKey() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Company", "companies");
        when(context.findAllPrimaryKeyColumns(owner)).thenReturn(
                List.of(owner.findColumn("companies", "id"))
        );
        EntityModel keyEntity = EntityModelMother.javaEntityWithPkIdLong("com.ex.Country", "countries");
        when(context.findAllPrimaryKeyColumns(keyEntity)).thenReturn(
                List.of(keyEntity.findColumn("countries", "id"))
        );
        // Key로 사용될 엔티티는 미리 스키마에 등록되어 있어야 함
        when(entitiesMap.get("com.ex.Country")).thenReturn(keyEntity);

        MapKeyJoinColumn mkjcAnn = AnnotationProxies.of(MapKeyJoinColumn.class, Map.of("name", "country_id"));
        AttributeDescriptor attribute = AttributeDescriptorFactory.mapOf("com.ex.Country", "java.lang.String", "offices", mkjcAnn);

        mockIsSubtype("java.util.Map", true);
        mockIsEntity("com.ex.Country", true);
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.Map"))).thenReturn(true);


        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collectionTable = SchemaCapture.capturePutIfAbsent(entitiesMap, "companies_offices");
        assertEquals(3, collectionTable.getColumns().size());

        ColumnAssertions.assertPkNonNull(collectionTable, "companies_offices", "companies_id", "java.lang.Long");
        var keyFkColumn = ColumnAssertions.assertPkNonNull(collectionTable, "companies_offices", "country_id", "java.lang.Long");
        assertTrue(keyFkColumn.isMapKey());
        ColumnAssertions.assertNonPkWithType(collectionTable, "companies_offices", "offices", "java.lang.String");

        // 관계(FK)가 2개(소유자, 맵 키 엔티티) 생성되었는지 확인
        assertEquals(2, collectionTable.getRelationships().size());
        RelationshipAssertions.assertFkByStructure(collectionTable, "companies_offices", List.of("companies_id"), "companies", List.of("id"), RelationshipType.ELEMENT_COLLECTION);
        RelationshipAssertions.assertFkByStructure(collectionTable, "companies_offices", List.of("country_id"), "countries", List.of("id"), RelationshipType.ELEMENT_COLLECTION);
    }

    @Test
    @DisplayName("소유자 엔티티에 PK가 없으면 유효성 검증에 실패하고 에러 메시지를 출력한다")
    void process_OwnerWithoutPrimaryKey_ShouldFailValidation() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntity("com.ex.NoPkEntity", "no_pk"); // PK가 없는 엔티티
        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "data");

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        MessagerAssertions.assertErrorContains(messager, "must have a primary key");
        verify(entitiesMap, never()).putIfAbsent(anyString(), any());
    }

    private void mockIsSubtype(String superType, boolean result) {
        lenient().when(context.isSubtype(any(DeclaredType.class), eq(superType))).thenAnswer(
                invocation -> {
                    DeclaredType dt = invocation.getArgument(0);
                    // 모의 타입의 FQCN을 기반으로 판단하도록 설정
                    if (dt.toString().equals(invocation.getArgument(1))) {
                        return result;
                    }
                    return false;
                }
        );
    }

    private void mockIsEmbeddable(String fqcn, boolean isEmbeddable) {
        TypeElement typeElement = mock(TypeElement.class);
        when(typeUtils.asElement(any())).thenAnswer(inv -> {
            if (inv.getArgument(0).toString().equals(fqcn)) return typeElement;
            return null;
        });
        when(typeElement.getAnnotation(Embeddable.class)).thenReturn(isEmbeddable ? mock(Embeddable.class) : null);
    }

    private void mockIsEntity(String fqcn, boolean isEntity) {
        TypeElement typeElement = mock(TypeElement.class);

        Name mockName = mock(Name.class);
        when(mockName.toString()).thenReturn(fqcn);

        lenient().when(typeElement.getQualifiedName()).thenReturn(mockName);

        when(typeUtils.asElement(any())).thenAnswer(inv -> {
            if (inv.getArgument(0) != null && inv.getArgument(0).toString().equals(fqcn)) {
                return typeElement;
            }
            return mock(TypeElement.class);
        });

        when(typeElement.getAnnotation(Entity.class)).thenReturn(isEntity ? mock(Entity.class) : null);
    }
}