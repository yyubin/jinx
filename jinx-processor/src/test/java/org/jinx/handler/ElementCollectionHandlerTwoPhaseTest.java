package org.jinx.handler;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
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
import org.jinx.testing.util.AnnotationMocks;
import org.jinx.testing.util.NamingTestUtil;
import org.jinx.testing.util.SchemaCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElementCollectionHandlerTwoPhaseTest {

    @Mock ProcessingContext context;
    @Mock ColumnHandler columnHandler;
    @Mock EmbeddedHandler embeddedHandler;
    @Mock Types typeUtils;
    @Mock Elements elementUtils;
    @Mock Messager messager;
    @Mock Naming naming;
    @Mock Map<String, EntityModel> entitiesMap;
    @Mock org.jinx.model.SchemaModel schemaModel;

    ElementCollectionHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getNaming()).thenReturn(naming);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(schemaModel.getEntities()).thenReturn(entitiesMap);
        handler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);
    }

    private EntityModel ownerWithPk() {
        EntityModel owner = EntityModelMother.usersWithPkIdLong();
        ColumnModel id = owner.findColumn("users", "id");
        lenient().when(context.findAllPrimaryKeyColumns(owner)).thenReturn(List.of(id));
        return owner;
    }

    @Test
    @DisplayName("[롤백] joinColumns 개수 불일치 → 에러로그만, 스키마 미등록")
    void rollback_whenJoinColumnsSizeMismatch() {
        // owner PK는 1개
        EntityModel owner = ownerWithPk();

        // Set<String> tags
        AttributeDescriptor attr = AttributeDescriptorFactory.setOf("java.lang.String", "tags");
        DeclaredType setType = (DeclaredType) attr.type();
        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        lenient().when(context.isSubtype(setType, "java.util.List")).thenReturn(false);

        // @CollectionTable(joinColumns=2) ← 의도적으로 불일치
        JoinColumn jc1 = AnnotationMocks.joinColumn("u_id1", "id");
        JoinColumn jc2 = AnnotationMocks.joinColumn("u_id2", "id");
        CollectionTable ct = AnnotationMocks.collectionTable("users_tags", jc1, jc2);
        when(attr.getAnnotation(CollectionTable.class)).thenReturn(ct);

        // Act
        handler.processElementCollection(attr, owner);

        // Assert: 에러 로그 발생
        MessagerAssertions.assertErrorContains(messager, "joinColumns size mismatch");
        // 스키마에 등록되지 않아야 함 (2단계 커밋 불가)
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
        // 임베디드/컬럼 핸들러 호출도 없어야 함
        verifyNoInteractions(embeddedHandler);
    }

    @Test
    @DisplayName("[롤백] referencedColumnName 미스매치 → 에러로그만, 스키마 미등록")
    void rollback_whenReferencedColumnNotFound() {
        EntityModel owner = ownerWithPk();

        AttributeDescriptor attr = AttributeDescriptorFactory.setOf("java.lang.String", "tags");
        DeclaredType setType = (DeclaredType) attr.type();
        when(context.isSubtype(setType, "java.util.Map")).thenReturn(false);
        lenient().when(context.isSubtype(setType, "java.util.List")).thenReturn(false);

        // owner PK는 'id'인데, 존재하지 않는 ref 사용
        JoinColumn bad = AnnotationMocks.joinColumn("owner_x", "not_exists");
        CollectionTable ct = AnnotationMocks.collectionTable("users_tags", bad);
        when(attr.getAnnotation(CollectionTable.class)).thenReturn(ct);

        handler.processElementCollection(attr, owner);

        MessagerAssertions.assertErrorContains(messager, "referencedColumnName not found");
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
        verifyNoInteractions(embeddedHandler);
    }

    @Test
    @DisplayName("[롤백] DeclaredType 아님 → 컬렉션 타입 판별 실패로 스키마 미등록")
    void rollback_whenNotDeclaredType() {
        EntityModel owner = ownerWithPk();

        // AttributeDescriptor에 DeclaredType이 아닌 TypeMirror를 반환하도록
        AttributeDescriptor attr = mock(AttributeDescriptor.class);
        when(attr.name()).thenReturn("tags");
        when(attr.type()).thenReturn(mock(javax.lang.model.type.TypeMirror.class)); // DeclaredType가 아님

        handler.processElementCollection(attr, owner);

        MessagerAssertions.assertErrorContains(messager, "Cannot determine collection type");
        verify(entitiesMap, never()).putIfAbsent(anyString(), any(EntityModel.class));
    }

    @Test
    @DisplayName("[커밋] 성공 시 FK/값/정렬 컬럼과 FK 관계가 일괄 반영된다")
    void commit_allPendingAppliedOnSuccess() {
        EntityModel owner = ownerWithPk();

        // List<Integer> + @OrderColumn
        var order = AnnotationMocks.orderColumn("score_order");
        AttributeDescriptor attr = AttributeDescriptorFactory.listOf("java.lang.Integer", "scores", order);
        DeclaredType listType = (DeclaredType) attr.type();
        when(context.isSubtype(listType, "java.util.Map")).thenReturn(false);
        when(context.isSubtype(listType, "java.util.List")).thenReturn(true);

        String fkName = NamingTestUtil.fk("users_scores", List.of("users_id"), "users", List.of("id"));
        when(context.getNaming().fkName(any(), any(), any(), any())).thenReturn(fkName);

        handler.processElementCollection(attr, owner);

        EntityModel collection = SchemaCapture.capturePutIfAbsent(entitiesMap, "users_scores");
        // FK + 값 + order = 3
        ColumnAssertions.assertPkNonNull(collection, "users_scores::users_id", "java.lang.Long");
        ColumnAssertions.assertPkNonNull(collection, "users_scores::score_order", "java.lang.Integer");
        // 값 컬럼은 PK가 아니다
        ColumnAssertions.assertNonPkWithType(collection, "users_scores::scores", "java.lang.Integer");

        RelationshipAssertions.assertFk(
                collection,
                fkName,
                "users_scores",
                List.of("users_id"),
                "users",
                List.of("id"),
                RelationshipType.ELEMENT_COLLECTION
        );
    }
}
