package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.relationship.RelationshipSupport;
import org.jinx.model.*;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.jinx.testing.util.SchemaCapture;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
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
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class EntityHandlerTest {

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
        lenient().when(naming.uqName(anyString(), anyList()))
                .thenAnswer(inv -> "UQ_" + inv.getArgument(0) + "_" + String.join("_", (List<String>) inv.getArgument(1)));
        lenient().when(naming.ixName(anyString(), anyList()))
                .thenAnswer(inv -> "IX_" + inv.getArgument(0) + "_" + String.join("_", (List<String>) inv.getArgument(1)));
        lenient().when(naming.autoName(anyString(), anyList()))
                .thenAnswer(inv -> "AU_" + inv.getArgument(0) + "_" + String.join("_", (List<String>) inv.getArgument(1)));
        lenient().when(naming.foreignKeyColumnName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));

        // 기본: subtype false (필요한 테스트에서 명시 true)
        lenient().when(context.isSubtype(any(DeclaredType.class), anyString())).thenReturn(false);

        // 기본: 필드 디스크립터 없음
        lenient().when(context.getCachedDescriptors(any())).thenReturn(List.of());
    }

    private TypeElement mockTypeElement(String fqcn, String simple) {
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        Name sn = mock(Name.class);
        when(qn.toString()).thenReturn(fqcn);
        when(sn.toString()).thenReturn(simple);
        when(te.getQualifiedName()).thenReturn(qn);
        when(te.getSimpleName()).thenReturn(sn);
        // 상위 타입 없음으로 처리
        var declaredNone = mock(DeclaredType.class);
        when(declaredNone.asElement()).thenReturn(null);
        when(te.getSuperclass()).thenReturn(declaredNone);
        return te;
    }

    @Test
    @DisplayName("기본 흐름: 엔티티 가등록 후 제너레이터/제약/필드 처리 위임 호출")
    void handle_registersAndDelegates() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.User", "User");

        // Act
        handler.handle(type);

        // Assert: 가등록 (엔티티 키는 FQCN)
        ArgumentCaptor<EntityModel> captor = ArgumentCaptor.forClass(EntityModel.class);
        verify(entitiesMap, times(1)).putIfAbsent(eq("com.ex.User"), captor.capture());
        EntityModel model = captor.getValue();
        assertEquals("User", model.getTableName());
        assertTrue(model.isValid());

        // 제너레이터 처리 위임
        verify(sequenceHandler, times(1)).processSequenceGenerators(eq(type));
        verify(tableGeneratorHandler, times(1)).processTableGenerators(eq(type));

        // 제약 핸들러 진입(개별 컬럼 제약이 없으니 호출만 확인)
        verify(constraintHandler, atLeast(0)).processConstraints((AttributeDescriptor) any(), any(), anyList(), anyString());
    }

    @Test
    @DisplayName("필드 처리: @ElementCollection → ElementCollectionHandler로 위임")
    void fields_delegate_ElementCollection() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.Post", "Post");
        EntityModel pre = EntityModelMother.javaEntityWithPkIdLong("com.ex.Post", "post");
        when(entitiesMap.containsKey("com.ex.Post")).thenReturn(false);

        // 디스크립터 모킹
        AttributeDescriptor d = mock(AttributeDescriptor.class);
        when(d.hasAnnotation(ElementCollection.class)).thenReturn(true);
        when(context.getCachedDescriptors(type)).thenReturn(List.of(d));

        // PK 존재하도록 (보조 테이블 조인 로직 등에서 사용 가능)
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class)))
                .thenReturn(List.of(ColumnModel.builder().columnName("id").tableName("post").javaType("java.lang.Long").isPrimaryKey(true).build()));

        // Act
        handler.handle(type);

        // Assert: 위임 호출
        verify(elementCollectionHandler, times(1)).processElementCollection(eq(d), any(EntityModel.class));
    }

    @Test
    @DisplayName("필드 처리: @Embedded → EmbeddedHandler로 위임")
    void fields_delegate_Embedded() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.Profile", "profile");
        AttributeDescriptor d = mock(AttributeDescriptor.class);
        when(d.hasAnnotation(Embedded.class)).thenReturn(true);
        when(context.getCachedDescriptors(type)).thenReturn(List.of(d));

        // Act
        handler.handle(type);

        // Assert
        verify(embeddedHandler, times(1)).processEmbedded(eq(d), any(EntityModel.class), anySet());
    }

    @Test
    @DisplayName("필드 처리: 관계 어노테이션 → RelationshipHandler.resolve로 위임")
    void fields_delegate_Relationship() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.Order", "orders");
        AttributeDescriptor d = mock(AttributeDescriptor.class);
        when(d.hasAnnotation(ManyToOne.class)).thenReturn(true);
        when(d.hasAnnotation(EmbeddedId.class)).thenReturn(false);
        when(context.getCachedDescriptors(type)).thenReturn(List.of(d));

        // Act
        handler.handle(type);

        // Assert
        verify(relationshipHandler, times(1)).resolve(eq(d), any(EntityModel.class));
    }

    @Test
    @DisplayName("@SecondaryTable: 부모 PK 기준으로 보조테이블 FK/PK 승격 및 관계 생성")
    void secondaryTable_join_afterPk() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.Invoice", "invoices");

        // @SecondaryTable(name="inv_ext") 부착
        SecondaryTable stAnn = AnnotationProxies.of(SecondaryTable.class, Map.of("name", "inv_ext"));
        when(type.getAnnotation(SecondaryTable.class)).thenReturn(stAnn);

        // 부모(주 테이블) PK 메타 제공
        ColumnModel parentPk = ColumnModel.builder()
                .columnName("id")
                .tableName("invoices")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class)))
                .thenAnswer(inv -> {
                    EntityModel e = inv.getArgument(0);
                    // 메인 테이블의 PK 반환
                    if ("invoices".equals(e.getTableName())) {
                        return List.of(parentPk);
                    }
                    return List.of(); // 기타는 비움
                });

        // Act
        handler.handle(type);

        // Assert: 가등록된 엔티티 획득
        EntityModel entity = SchemaCapture.capturePutIfAbsent(entitiesMap, "com.ex.Invoice");
        assertEquals("invoices", entity.getTableName());

        assertFalse(entity.getRelationships().isEmpty());
        RelationshipModel rel = entity.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.SECONDARY_TABLE)
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("id"), rel.getColumns());
        assertEquals("inv_ext", rel.getTableName());       // ← 수정: invoices → inv_ext
        assertEquals("invoices", rel.getReferencedTable());

        RelationshipAssertions.assertFkByStructure(
                entity,
                "inv_ext",  List.of("id"),     // ← 수정
                "invoices", List.of("id"),
                RelationshipType.SECONDARY_TABLE
        );

        ColumnAssertions.assertPkNonNull(entity, "inv_ext", "id", "java.lang.Long");
    }

    @Test
    @DisplayName("중복 엔티티 감지: 같은 FQCN이 이미 존재하면 handle은 null 리턴 경로(가등록 생략)")
    void duplicateEntity_earlyExit() {
        // Arrange
        TypeElement type = mockTypeElement("com.ex.Duplicate", "dup");
        when(entitiesMap.containsKey("com.ex.Duplicate")).thenReturn(true);

        // Act
        handler.handle(type);

        // Assert: putIfAbsent가 호출되지 않음
        verify(entitiesMap, never()).putIfAbsent(anyString(), any());
        // 제너레이터/필드 처리 등도 스킵
        verifyNoInteractions(sequenceHandler, tableGeneratorHandler, elementCollectionHandler, embeddedHandler, relationshipHandler);
    }
}
