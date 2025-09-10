package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.naming.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.SchemaModel;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.jinx.testing.util.SchemaCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class EntityHandlerInheritanceTest {

    @InjectMocks private EntityHandler handler;

    @Mock private ProcessingContext context;
    @Mock private ColumnHandler columnHandler;
    @Mock private EmbeddedHandler embeddedHandler;
    @Mock private ConstraintHandler constraintHandler;
    @Mock private SequenceHandler sequenceHandler;
    @Mock private ElementCollectionHandler elementCollectionHandler;
    @Mock private TableGeneratorHandler tableGeneratorHandler;
    @Mock private RelationshipHandler relationshipHandler;

    @Mock private Types typeUtils;
    @Mock private Elements elementUtils;
    @Mock private javax.annotation.processing.Messager messager;
    @Mock private Naming naming;

    @Mock private SchemaModel schemaModel;
    @Mock private Map<String, EntityModel> entitiesMap;

    @BeforeEach
    void setUp() {
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getNaming()).thenReturn(naming);
        lenient().when(schemaModel.getEntities()).thenReturn(entitiesMap);

        // 기본 네이밍
        lenient().when(naming.fkName(anyString(), anyList(), anyString(), anyList()))
                .thenAnswer(inv -> "FK_" + inv.getArgument(0) + "_" + inv.getArgument(2));

        // 필드 디스크립터 없음(이 테스트는 상속 로직 중심)
        lenient().when(context.getCachedDescriptors(any())).thenReturn(List.of());
    }

    // === 유틸 ===

    private static TypeElement mockTypeElement(String fqcn, String simple) {
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        Name sn = mock(Name.class);
        when(qn.toString()).thenReturn(fqcn);
        when(sn.toString()).thenReturn(simple);
        when(te.getQualifiedName()).thenReturn(qn);
        when(te.getSimpleName()).thenReturn(sn);
        return te;
    }

    private static DeclaredType declaredOf(TypeElement el) {
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        when(dt.asElement()).thenReturn(el);
        return dt;
    }

    private static TypeMirror noneSuper() {
        TypeMirror t = mock(TypeMirror.class);
        when(t.getKind()).thenReturn(TypeKind.NONE);
        return t;
    }

    // === 테스트 시나리오 ===

    @Test
    @DisplayName("중간 부모가 @Inheritance 미지정이어도 상위에서 JOINED를 찾아 FK 생성")
    void joinedParent_found_through_intermediate() {
        // 체인: Child -> Mid(entity, @Inheritance 없음) -> Root(entity, @Inheritance JOINED) -> (없음)
        TypeElement root = mockTypeElement("com.ex.Root", "Root");
        when(root.getAnnotation(Entity.class)).thenReturn(AnnotationProxies.of(Entity.class, Map.of()));
        Inheritance inhJoined = AnnotationProxies.of(Inheritance.class, Map.of("strategy", InheritanceType.JOINED));
        when(root.getAnnotation(Inheritance.class)).thenReturn(inhJoined);
        TypeMirror none = noneSuper();
        when(root.getSuperclass()).thenReturn(none);

        TypeElement mid = mockTypeElement("com.ex.Mid", "Mid");
        when(mid.getAnnotation(Entity.class)).thenReturn(AnnotationProxies.of(Entity.class, Map.of()));
        when(mid.getAnnotation(Inheritance.class)).thenReturn(null); // 명시 X
        DeclaredType dtMid = declaredOf(root);
        when(mid.getSuperclass()).thenReturn(dtMid);

        TypeElement child = mockTypeElement("com.ex.Child", "child");
        DeclaredType dtChild = declaredOf(mid);
        when(child.getSuperclass()).thenReturn(dtChild);

        // 스키마: Root 엔티티는 이미 존재하며 PK가 준비되어 있어야 함
        EntityModel rootEntity = EntityModelMother.javaEntityWithPkIdLong("com.ex.Root", "root_tbl");
        when(entitiesMap.get("com.ex.Root")).thenReturn(rootEntity);
        when(entitiesMap.containsKey("com.ex.Child")).thenReturn(false);

        // 부모 PK 메타 제공
        when(context.findAllPrimaryKeyColumns(rootEntity))
                .thenReturn(List.of(
                        ColumnModel.builder().columnName("id").tableName("root_tbl").javaType("java.lang.Long").isPrimaryKey(true).build()
                ));

        // Act
        handler.handle(child);

        // Assert: Child 가등록
        EntityModel childModel = SchemaCapture.capturePutIfAbsent(entitiesMap, "com.ex.Child");
        assertEquals("child", childModel.getTableName());

        // JOINED 상속 FK가 child 테이블에 생성되어 있어야 함
        RelationshipModel rel = childModel.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .findFirst()
                .orElseThrow();

        assertEquals("child", rel.getTableName());         // FK가 걸리는 곳 = 자식 테이블
        assertEquals("root_tbl", rel.getReferencedTable()); // 참조 = 루트(부모) 테이블
        assertEquals(List.of("id"), rel.getColumns());     // 부모 PK 컬럼과 동일명 매핑

        RelationshipAssertions.assertFkByStructure(
                childModel,
                "child",     List.of("id"),
                "root_tbl",  List.of("id"),
                RelationshipType.JOINED_INHERITANCE
        );
    }

    @Test
    @DisplayName("중간에 SINGLE_TABLE이 명시되면 JOINED 탐색 충돌로 에러 처리하고 관계 생성 중단")
    void explicitSingleTable_conflict_reportsError() {
        // 체인: Child -> Mid(entity, @Inheritance SINGLE_TABLE) -> Root(entity, @Inheritance JOINED)
        TypeElement root = mockTypeElement("com.ex.Root", "Root");
        when(root.getAnnotation(Entity.class)).thenReturn(AnnotationProxies.of(Entity.class, Map.of()));
        Inheritance inhJoined = AnnotationProxies.of(Inheritance.class, Map.of("strategy", InheritanceType.JOINED));
        when(root.getAnnotation(Inheritance.class)).thenReturn(inhJoined);
        TypeMirror none = noneSuper();
        when(root.getSuperclass()).thenReturn(none);

        TypeElement mid = mockTypeElement("com.ex.Mid", "Mid");
        when(mid.getAnnotation(Entity.class)).thenReturn(AnnotationProxies.of(Entity.class, Map.of()));
        Inheritance inhSingle = AnnotationProxies.of(Inheritance.class, Map.of("strategy", InheritanceType.SINGLE_TABLE));
        when(mid.getAnnotation(Inheritance.class)).thenReturn(inhSingle);
        DeclaredType declaredOfRoot = declaredOf(root);
        when(mid.getSuperclass()).thenReturn(declaredOfRoot);

        TypeElement child = mockTypeElement("com.ex.Child", "child");
        DeclaredType declaredOfMid = declaredOf(mid);
        when(child.getSuperclass()).thenReturn(declaredOfMid);

        // 루트 엔티티는 스키마에 존재(있어도 충돌 때문에 못 씀)
        EntityModel rootEntity = EntityModelMother.javaEntityWithPkIdLong("com.ex.Root", "root_tbl");
        when(entitiesMap.get("com.ex.Root")).thenReturn(rootEntity);
        when(entitiesMap.containsKey("com.ex.Child")).thenReturn(false);

        // 부모 PK 메타
        when(context.findAllPrimaryKeyColumns(rootEntity))
                .thenReturn(List.of(
                        ColumnModel.builder().columnName("id").tableName("root_tbl").javaType("java.lang.Long").isPrimaryKey(true).build()
                ));

        // Act
        handler.handle(child);

        // Assert: Child 가등록은 됨
        EntityModel childModel = SchemaCapture.capturePutIfAbsent(entitiesMap, "com.ex.Child");
        assertEquals("child", childModel.getTableName());

        // 관계는 생성되지 않아야 함(충돌로 중단)
        assertTrue(
                childModel.getRelationships().values().stream()
                        .noneMatch(r -> r.getType() == RelationshipType.JOINED_INHERITANCE),
                "JOINED_INHERITANCE 관계가 생성되면 안 됩니다"
        );

        // 에러 메시지 검증
        MessagerAssertions.assertErrorContains(
                messager,
                "inheritance strategy 'SINGLE_TABLE'"
        );
    }

    @Test
    @DisplayName("바로 위 부모가 JOINED이면 즉시 매핑")
    void immediate_joined_parent() {
        // 체인: Child -> Parent(entity, @Inheritance JOINED)
        TypeElement parent = mockTypeElement("com.ex.Parent", "parent_tbl");
        when(parent.getAnnotation(Entity.class)).thenReturn(AnnotationProxies.of(Entity.class, Map.of()));
        Inheritance inhJoined = AnnotationProxies.of(Inheritance.class, Map.of("strategy", InheritanceType.JOINED));
        when(parent.getAnnotation(Inheritance.class)).thenReturn(inhJoined);
        TypeMirror typeMirror = noneSuper();
        lenient().when(parent.getSuperclass()).thenReturn(typeMirror);

        TypeElement child = mockTypeElement("com.ex.Child2", "child2");
        DeclaredType declaredOf = declaredOf(parent);
        when(child.getSuperclass()).thenReturn(declaredOf);

        // 스키마: 부모 엔티티 존재
        EntityModel parentEntity = EntityModelMother.javaEntityWithPkIdLong("com.ex.Parent", "parent_tbl");
        when(entitiesMap.get("com.ex.Parent")).thenReturn(parentEntity);
        when(entitiesMap.containsKey("com.ex.Child2")).thenReturn(false);

        // 부모 PK
        when(context.findAllPrimaryKeyColumns(parentEntity))
                .thenReturn(List.of(
                        ColumnModel.builder().columnName("id").tableName("parent_tbl").javaType("java.lang.Long").isPrimaryKey(true).build()
                ));

        // Act
        handler.handle(child);

        // Assert
        EntityModel childModel = SchemaCapture.capturePutIfAbsent(entitiesMap, "com.ex.Child2");
        RelationshipModel rel = childModel.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .findFirst()
                .orElseThrow();

        assertEquals("child2", rel.getTableName());
        assertEquals("parent_tbl", rel.getReferencedTable());
        assertEquals(List.of("id"), rel.getColumns());
    }
}
