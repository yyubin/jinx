package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipType;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.SchemaCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class ElementCollectionHandlerTwoPhaseTest {

    @InjectMocks
    private ElementCollectionHandler handler;

    @Mock private ProcessingContext context;
    @Mock private ColumnHandler columnHandler;
    @Mock private EmbeddedHandler embeddedHandler;
    @Mock private Types typeUtils;
    @Mock private Elements elementUtils;
    @Mock private javax.annotation.processing.Messager messager;
    @Mock private Naming naming;
    @Mock private org.jinx.model.SchemaModel schemaModel;
    @Mock private Map<String, EntityModel> entitiesMap;

    @BeforeEach
    void setUp() {
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getNaming()).thenReturn(naming);
        lenient().when(schemaModel.getEntities()).thenReturn(entitiesMap);

        // 기본 네이밍 규칙
        lenient().when(naming.foreignKeyColumnName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));
        lenient().when(naming.fkName(anyString(), anyList(), anyString(), anyList()))
                .thenAnswer(inv -> "FK_" + inv.getArgument(0) + "_" + inv.getArgument(2));

        // 기본 isSubtype 더미(필요한 테스트에서 명시적 true로 오버라이드)
        lenient().when(context.isSubtype(any(DeclaredType.class), anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("검증 실패 시: 에러 메시지 출력 & 커밋/등록 없음 (owner에 PK 없음)")
    void validatePhase_Fails_ShouldNotCommitOrRegister() {
        // Arrange: PK가 없는 소유자 엔티티
        EntityModel ownerNoPk = EntityModelMother.javaEntity("com.ex.NoPk", "no_pk");
        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");

        // Act
        handler.processElementCollection(attribute, ownerNoPk);

        // Assert: APT 에러가 찍히고, 스키마 등록은 없어야 함
        MessagerAssertions.assertErrorContains(messager, "must have a primary key");
        verify(entitiesMap, never()).putIfAbsent(anyString(), any());
        // 컬럼/관계 핸들러 호출도 없어야 함(부분 커밋 금지)
        verifyNoInteractions(columnHandler);
        verifyNoInteractions(embeddedHandler);
    }

    @Test
    @DisplayName("검증 성공 시: 일괄 커밋 후 스키마에 putIfAbsent로 등록(Set<String> 기본 케이스)")
    void validatePhase_Succeeds_ShouldCommitAndRegister() {
        // Arrange: PK(id: Long)가 있는 소유자 + Set<String> 속성
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User", "users");
        when(context.findAllPrimaryKeyColumns(owner))
                .thenReturn(List.of(owner.findColumn("users", "id")));

        AttributeDescriptor attribute = AttributeDescriptorFactory.setOf("java.lang.String", "tags");

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert: 스키마에 등록되었는지 캡처
        EntityModel collection = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_tags");

        // 검증 성공 → 커밋 결과(컬럼/관계)가 모두 반영되어 있어야 함
        assertEquals(EntityModel.TableType.COLLECTION_TABLE, collection.getTableType());
        assertEquals(2, collection.getColumns().size(), "FK + 값 = 2 컬럼");

        // FK(owner PK 승격) + 값 컬럼 확인
        ColumnAssertions.assertPkNonNull(collection, "users_tags", "users_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collection, "users_tags", "tags", "java.lang.String");

        // FK 관계 1개(ElementCollection) 생성 확인
        RelationshipAssertions.assertFkByStructure(
                collection,
                "users_tags", List.of("users_id"),
                "users",      List.of("id"),
                RelationshipType.ELEMENT_COLLECTION
        );
    }

    @Test
    @DisplayName("검증 성공 시: List + @OrderColumn 이면 (ownerPK + order) PK 구성 & 스키마 등록")
    void validatePhase_Succeeds_ListWithOrderColumn_ShouldAddOrderPkAndRegister() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Article", "articles");
        when(context.findAllPrimaryKeyColumns(owner))
                .thenReturn(List.of(owner.findColumn("articles", "id")));

        // List 판별은 명시적으로 true
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.List"))).thenReturn(true);

        OrderColumn orderAnn = org.jinx.testing.util.AnnotationProxies.of(OrderColumn.class, Map.of("name", "line_order"));
        AttributeDescriptor attribute = AttributeDescriptorFactory.listOf("java.lang.String", "lines", orderAnn);

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collection = SchemaCapture.capturePutIfAbsent(entitiesMap, "articles_lines");
        assertEquals(3, collection.getColumns().size(), "FK + 값 + 순서 = 3 컬럼");

        ColumnAssertions.assertPkNonNull(collection, "articles_lines", "articles_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collection, "articles_lines", "line_order",  "java.lang.Integer");
        ColumnAssertions.assertNonPkWithType(collection, "articles_lines", "lines",   "java.lang.String");
    }

    @Test
    @DisplayName("검증 성공 시: Map<K,V> 기본타입 키/값 → 키 PK + 값 NonPK & 스키마 등록")
    void validatePhase_Succeeds_MapBasicKey_ShouldCommitAndRegister() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Product", "products");
        when(context.findAllPrimaryKeyColumns(owner))
                .thenReturn(List.of(owner.findColumn("products", "id")));

        // Map 판별 true
        when(context.isSubtype(any(DeclaredType.class), eq("java.util.Map"))).thenReturn(true);

        var keyAnn = org.jinx.testing.util.AnnotationProxies.of(MapKeyColumn.class, Map.of("name", "prop_key"));
        var valAnn = org.jinx.testing.util.AnnotationProxies.of(Column.class, Map.of("name", "prop_value"));
        AttributeDescriptor attribute = AttributeDescriptorFactory.mapOf("java.lang.Integer", "java.lang.String", "properties", keyAnn, valAnn);

        // Act
        handler.processElementCollection(attribute, owner);

        // Assert
        EntityModel collection = SchemaCapture.capturePutIfAbsent(entitiesMap, "products_properties");
        assertEquals(3, collection.getColumns().size(), "FK + 키 + 값 = 3");

        ColumnAssertions.assertPkNonNull(collection, "products_properties", "products_id", "java.lang.Long");
        var keyCol = ColumnAssertions.assertPkNonNull(collection, "products_properties", "prop_key", "java.lang.Integer");
        assertTrue(keyCol.isMapKey());
        ColumnAssertions.assertNonPkWithType(collection, "products_properties", "prop_value", "java.lang.String");
    }

}
