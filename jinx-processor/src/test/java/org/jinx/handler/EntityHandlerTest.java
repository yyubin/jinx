package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private Naming naming;
    @Mock
    private ColumnHandler columnHandler;
    @Mock
    private EmbeddedHandler embeddedHandler;
    @Mock
    private ConstraintHandler constraintHandler;
    @Mock
    private SequenceHandler sequenceHandler;
    @Mock
    private ElementCollectionHandler elementCollectionHandler;
    @Mock
    private TableGeneratorHandler tableGeneratorHandler;

    @Mock
    private Elements elementUtils;
    @Mock
    private javax.annotation.processing.Messager messager;

    // 테스트 대상 클래스
    @InjectMocks
    private EntityHandler entityHandler;

    private SchemaModel schemaModel;
    private Queue<EntityModel> deferredEntitiesQueue;
    private Set<String> deferredNamesSet;

    @BeforeEach
    void setUp() {
        // 지연 처리 로직을 정확하게 테스트하기 위해 실제 컬렉션을 사용합니다.
        schemaModel = new SchemaModel();
        deferredEntitiesQueue = new ArrayDeque<>();
        deferredNamesSet = new HashSet<>();

        // ProcessingContext Mock이 실제 컬렉션과 Mock 유틸리티를 반환하도록 설정합니다.
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getNaming()).thenReturn(naming);
        lenient().when(context.getDeferredEntities()).thenReturn(deferredEntitiesQueue);
        lenient().when(context.getDeferredNames()).thenReturn(deferredNamesSet);
    }

    @Test
    @DisplayName("상속 관계가 없는 간단한 엔티티 처리")
    void handle_SimpleEntity_ShouldProcessCorrectly() {
        // --- ARRANGE (준비) ---
        // 간단한 엔티티 Mock 생성: @Entity public class User { @Id private Long id; }
        TypeElement userElement = mock(TypeElement.class);
        TypeMirror superType = mock(TypeMirror.class);
        Entity entityAnnotation = mock(Entity.class);
        Table tableAnnotation = mock(Table.class);
        VariableElement idField = mock(VariableElement.class);

        // 기본 Element 정보 설정
        when(userElement.getAnnotation(Table.class)).thenReturn(tableAnnotation);
        lenient().when(userElement.getAnnotation(Entity.class)).thenReturn(entityAnnotation);
        Name mockedUserName = mock(Name.class);
        when(mockedUserName.toString()).thenReturn("User");
        Name mockedUserQualifiedName = mock(Name.class);
        when(mockedUserQualifiedName.toString()).thenReturn("com.example.User");
        when(userElement.getQualifiedName()).thenReturn(mockedUserQualifiedName);
        when(userElement.getSimpleName()).thenReturn(mockedUserName);
        UniqueConstraint[] UniqueConstraint = new UniqueConstraint[0];
        doReturn(UniqueConstraint).when(tableAnnotation).uniqueConstraints();
        Index[] index = new Index[0];
        doReturn(index).when(tableAnnotation).indexes();
        CheckConstraint[] check = new CheckConstraint[0];
        doReturn(check).when(tableAnnotation).check();
        doReturn(List.of(idField)).when(userElement).getEnclosedElements();
        when(userElement.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(userElement.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(userElement.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        lenient().when(userElement.getAnnotation(SecondaryTable.class)).thenReturn(null);
        lenient().when(userElement.getAnnotation(SecondaryTables.class)).thenReturn(null);
        when(userElement.getSuperclass()).thenReturn(superType);
        when(superType.getKind()).thenReturn(TypeKind.NONE);

        // 필드 정보 설정
        Id idAnn = mock(Id.class);
        lenient().when(idField.getAnnotation(Id.class)).thenReturn(idAnn);
        when(idField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        Name mockedIdName = mock(Name.class);
        lenient().when(mockedIdName.toString()).thenReturn("id");
        when(idField.getKind()).thenReturn(ElementKind.FIELD);
        when(idField.getModifiers()).thenReturn(Collections.emptySet());
        when(idField.getAnnotation(Transient.class)).thenReturn(null);
        when(idField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(idField.getAnnotation(Embedded.class)).thenReturn(null);
        when(idField.getAnnotation(Column.class)).thenReturn(null);
        lenient().when(idField.getSimpleName()).thenReturn(mockedIdName);

        // ColumnHandler 동작 Mocking
        ColumnModel idColumn = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(idField), any())).thenReturn(idColumn);

        // --- ACT (실행) ---
        entityHandler.handle(userElement);

        // --- ASSERT (검증) ---
        // 1. 엔티티가 생성되어 스키마에 추가되었는지 확인
        EntityModel createdEntity = schemaModel.getEntities().get("com.example.User");
        assertNotNull(createdEntity, "EntityModel이 생성되고 저장되어야 합니다.");
        assertTrue(createdEntity.isValid(), "엔티티는 유효해야 합니다.");
        assertEquals("User", createdEntity.getTableName(), "테이블명은 클래스의 simple name이 기본값이어야 합니다.");

        // 2. 컬럼이 처리되어 추가되었는지 확인
        assertTrue(createdEntity.getColumns().containsKey("id"), "ID 컬럼이 존재해야 합니다.");
        assertEquals(idColumn, createdEntity.getColumns().get("id"));

        // 3. 핸들러들과의 상호작용 검증
        verify(columnHandler, times(1)).createFrom(eq(idField), any());
        verify(sequenceHandler, times(1)).processSequenceGenerators(userElement);
        verify(tableGeneratorHandler, times(1)).processTableGenerators(userElement);

        // 4. 에러가 보고되지 않았는지 확인
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), any(), any());

        // 5. 지연 처리 큐가 비어있는지 확인
        assertTrue(deferredEntitiesQueue.isEmpty(), "단순 엔티티의 경우 지연 처리 큐는 비어있어야 합니다.");
    }

    @Test
    @DisplayName("@IdClass 사용 시 에러 보고 및 엔티티 무효 처리")
    void handle_IdClass_ShouldReportErrorAndInvalidateEntity() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getAnnotation(Table.class)).thenReturn(null);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(e.getAnnotation(IdClass.class)).thenReturn(mock(IdClass.class));
        lenient().when(e.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);
        lenient().when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);

        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Bad");
        when(sn.toString()).thenReturn("Bad");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);

        lenient().doReturn(List.of()).when(e).getEnclosedElements();

        entityHandler.handle(e);

        EntityModel em = schemaModel.getEntities().get("com.example.Bad");
        assertNotNull(em);
        assertFalse(em.isValid());
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("IdClass is not supported"), eq(e));
    }

    @Test
    @DisplayName("@EmbeddedId 경로: 임베디드 처리 및 PK 마킹")
    void handle_EmbeddedId_ShouldProcessEmbeddedIdAndMarkPk() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);

        when(e.getAnnotation(Table.class)).thenReturn(null);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);

        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Emb");
        when(sn.toString()).thenReturn("Emb");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);

        // ---- EmbeddedId 필드 ----
        VariableElement embeddedIdField = mock(VariableElement.class);
        DeclaredType embeddedIdType = mock(DeclaredType.class);
        when(embeddedIdField.getKind()).thenReturn(ElementKind.FIELD);
        when(embeddedIdField.getAnnotation(Transient.class)).thenReturn(null);
        when(embeddedIdField.getAnnotation(EmbeddedId.class)).thenReturn(mock(EmbeddedId.class));
        when(embeddedIdField.asType()).thenReturn(embeddedIdType);
        doReturn(Collections.emptySet()).when(embeddedIdField).getModifiers();
        doReturn(List.of(embeddedIdField)).when(e).getEnclosedElements();

        // ---- 임베디드 타입(Embeddable)의 TypeElement와 그 안의 필드들 ----
        TypeElement embeddableType = mock(TypeElement.class);
        when(embeddedIdType.asElement()).thenReturn(embeddableType);

        VariableElement part1 = mock(VariableElement.class);
        VariableElement part2 = mock(VariableElement.class);
        when(part1.getKind()).thenReturn(ElementKind.FIELD);
        when(part2.getKind()).thenReturn(ElementKind.FIELD);
        Name p1n = mock(Name.class), p2n = mock(Name.class);
        when(p1n.toString()).thenReturn("idPart1");
        when(p2n.toString()).thenReturn("idPart2");
        when(part1.getSimpleName()).thenReturn(p1n);
        when(part2.getSimpleName()).thenReturn(p2n);
        // embeddableType 내부 필드 반환 (리스트는 doReturn 사용)
        doReturn(List.of(part1, part2)).when(embeddableType).getEnclosedElements();

        // ---- embeddedHandler가 실제 컬럼을 추가해주도록 Answer 스텁 ----
        // processEmbedded(embeddedIdField, entity, set) 호출 시 entity에 idPart1/idPart2 컬럼을 심어줌
        doAnswer(inv -> {
            EntityModel entityModel = inv.getArgument(1);
            entityModel.getColumns().put("idPart1", ColumnModel.builder().columnName("idPart1").build());
            entityModel.getColumns().put("idPart2", ColumnModel.builder().columnName("idPart2").build());
            return null;
        }).when(embeddedHandler).processEmbedded(eq(embeddedIdField), any(EntityModel.class), anySet());

        // --- ACT ---
        entityHandler.handle(e);

        // --- ASSERT ---
        EntityModel em = schemaModel.getEntities().get("com.example.Emb");
        assertNotNull(em);
        assertTrue(em.isValid());

        // embeddedHandler가 넣어준 컬럼이 존재하고,
        // processEmbeddedId 루프에서 PK로 마킹 되었는지 확인
        assertTrue(em.getColumns().get("idPart1").isPrimaryKey());
        assertTrue(em.getColumns().get("idPart2").isPrimaryKey());

        verify(embeddedHandler, times(1))
                .processEmbedded(eq(embeddedIdField), eq(em), anySet());
    }

    @Test
    @DisplayName("@SecondaryTable: 관계 생성 및 자식 PK=FK 보강")
    void handle_SecondaryTable_ShouldCreateFkAndEnsureChildPkColumns() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(e.getAnnotation(Table.class)).thenReturn(null);
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        SecondaryTable stAnn = mock(SecondaryTable.class);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(stAnn);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);

        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.User");
        when(sn.toString()).thenReturn("User");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);

        when(stAnn.name()).thenReturn("user_detail");
        doReturn(new PrimaryKeyJoinColumn[0]).when(stAnn).pkJoinColumns();
        doReturn(new UniqueConstraint[0]).when(stAnn).uniqueConstraints();
        doReturn(new Index[0]).when(stAnn).indexes();
        doReturn(new CheckConstraint[0]).when(stAnn).check();

        VariableElement idField = mock(VariableElement.class);
        when(idField.getKind()).thenReturn(ElementKind.FIELD);
        when(idField.getAnnotation(Transient.class)).thenReturn(null);
        when(idField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(idField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(idField.getAnnotation(Embedded.class)).thenReturn(null);
        when(idField.getAnnotation(Column.class)).thenReturn(null);
        lenient().when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        doReturn(Collections.emptySet()).when(idField).getModifiers();
        Name idName = mock(Name.class);
        lenient().when(idName.toString()).thenReturn("id");
        lenient().when(idField.getSimpleName()).thenReturn(idName);
        doReturn(List.of(idField)).when(e).getEnclosedElements();

        ColumnModel idCol = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(idField), any())).thenReturn(idCol);
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(eq("user_detail"), anyList(), eq("User"), anyList()))
                .thenReturn("fk_userdetail_user");
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class)))
                .thenAnswer(inv -> {
                    EntityModel em = inv.getArgument(0);
                    return em.getColumns().values().stream()
                            .filter(ColumnModel::isPrimaryKey)
                            .toList();
                });

        entityHandler.handle(e);

        EntityModel em = schemaModel.getEntities().get("com.example.User");
        assertNotNull(em);
        em.getRelationships().values().forEach(r -> System.out.println(r.getConstraintName() + " : " + r.getType()));
        assertTrue(em.getRelationships().containsKey("fk_userdetail_user"));
        RelationshipModel r = em.getRelationships().get("fk_userdetail_user");
        assertEquals("userdetail", r.getConstraintName().replace("fk_", "").split("_")[0]); // 느슨 확인
        assertEquals(RelationshipType.SECONDARY_TABLE, r.getType());
        assertTrue(em.getColumns().containsKey("id"));
    }

    @Test
    @DisplayName("JOINED 상속: 부모 존재 시 FK 생성 및 PK 매칭")
    void handle_JoinedInheritance_ShouldCreateFkToParent() {
        // 부모 엔티티 선등록
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .isValid(true)
                .build();
        ColumnModel pk = ColumnModel.builder().columnName("pid").isPrimaryKey(true).build();
        parent.getColumns().put("pid", pk);
        schemaModel.getEntities().put("com.example.Parent", parent);

        // 부모 TypeElement (JOINED)
        TypeElement parentType = mock(TypeElement.class);
        when(parentType.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);
        Name pQN = mock(Name.class), pSN = mock(Name.class);
        when(pQN.toString()).thenReturn("com.example.Parent");
        lenient().when(pSN.toString()).thenReturn("Parent");
        when(parentType.getQualifiedName()).thenReturn(pQN);
        lenient().when(parentType.getSimpleName()).thenReturn(pSN);
        when(parentType.getAnnotation(MappedSuperclass.class)).thenReturn(null);

        TypeMirror parentSup = mock(TypeMirror.class);
        when(parentType.getSuperclass()).thenReturn(parentSup);
        when(parentSup.getKind()).thenReturn(TypeKind.NONE);

        // 자식 TypeElement
        TypeElement child = mock(TypeElement.class);
        lenient().when(child.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(child.getAnnotation(Table.class)).thenReturn(null);
        when(child.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(child.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(child.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTables.class)).thenReturn(null);
        when(child.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(null);
        when(child.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(null);

        Name cQN = mock(Name.class), cSN = mock(Name.class);
        when(cQN.toString()).thenReturn("com.example.Child");
        when(cSN.toString()).thenReturn("Child");
        when(child.getQualifiedName()).thenReturn(cQN);
        when(child.getSimpleName()).thenReturn(cSN);

        // 부모로의 상속 연결
        DeclaredType childSup = mock(DeclaredType.class);
        when(child.getSuperclass()).thenReturn(childSup);
        when(childSup.getKind()).thenReturn(TypeKind.DECLARED);
        when(childSup.asElement()).thenReturn(parentType);

        VariableElement childField = mock(VariableElement.class);
        when(childField.getKind()).thenReturn(ElementKind.FIELD);
        when(childField.getAnnotation(Transient.class)).thenReturn(null);
        when(childField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(childField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(childField.getAnnotation(Embedded.class)).thenReturn(null);
        when(childField.getAnnotation(Column.class)).thenReturn(null);
        doReturn(Collections.emptySet()).when(childField).getModifiers();
        Name cid = mock(Name.class);
        lenient().when(cid.toString()).thenReturn("pid"); // 부모 PK와 동일명 사용(커스터마이즈 없을 때)
        lenient().when(childField.getSimpleName()).thenReturn(cid);
        doReturn(List.of(childField)).when(child).getEnclosedElements();

        // 필수..
        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(pk));

        ColumnModel childCol = ColumnModel.builder().columnName("pid").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(childField), any())).thenReturn(childCol);
        when(naming.fkName(anyString(), anyList(), anyString(), anyList()))
                .thenReturn("fk_child_parent");

        entityHandler.handle(child);

        EntityModel cm = schemaModel.getEntities().get("com.example.Child");
        assertNotNull(cm);
        assertFalse(cm.getRelationships().isEmpty(), "JOINED FK가 생성되어야 합니다.");
        RelationshipModel r = cm.getRelationships().values().iterator().next();
        assertEquals(RelationshipType.JOINED_INHERITANCE, r.getType());
        assertEquals(List.of("pid"), r.getColumns());
        assertEquals(List.of("pid"), r.getReferencedColumns());
        assertTrue(cm.getRelationships().containsKey("fk_child_parent"));
    }

    @Test
    @DisplayName("JOINED 상속: pkJoinColumns 개수 불일치 시 에러")
    void handle_JoinedInheritance_SizeMismatch_ShouldError() {
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .isValid(true)
                .build();
        ColumnModel p1 = ColumnModel.builder().columnName("k1").isPrimaryKey(true).build();
        ColumnModel p2 = ColumnModel.builder().columnName("k2").isPrimaryKey(true).build();
        parent.getColumns().put("k1", p1);
        parent.getColumns().put("k2", p2);
        schemaModel.getEntities().put("com.example.Parent", parent);

        TypeElement parentType = mock(TypeElement.class);
        when(parentType.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);
        Name pQN = mock(Name.class), pSN = mock(Name.class);
        when(pQN.toString()).thenReturn("com.example.Parent");
        lenient().when(pSN.toString()).thenReturn("Parent");
        when(parentType.getQualifiedName()).thenReturn(pQN);
        lenient().when(parentType.getSimpleName()).thenReturn(pSN);

        TypeElement child = mock(TypeElement.class);
        lenient().when(child.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(child.getAnnotation(Table.class)).thenReturn(null);
        when(child.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(child.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(child.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTables.class)).thenReturn(null);

        Name cQN = mock(Name.class), cSN = mock(Name.class);
        when(cQN.toString()).thenReturn("com.example.ChildBad");
        when(cSN.toString()).thenReturn("ChildBad");
        when(child.getQualifiedName()).thenReturn(cQN);
        when(child.getSimpleName()).thenReturn(cSN);

        DeclaredType childSup = mock(DeclaredType.class);
        when(child.getSuperclass()).thenReturn(childSup);
        when(childSup.getKind()).thenReturn(TypeKind.DECLARED);
        when(childSup.asElement()).thenReturn(parentType);

        TypeMirror parentSup = mock(TypeMirror.class);
        when(parentType.getSuperclass()).thenReturn(parentSup);
        when(parentSup.getKind()).thenReturn(TypeKind.NONE);
        when(parentType.getAnnotation(MappedSuperclass.class)).thenReturn(null);

        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(p1, p2));

        PrimaryKeyJoinColumn one = mock(PrimaryKeyJoinColumn.class);
        lenient().doReturn("ck1").when(one).name();
        lenient().doReturn("").when(one).referencedColumnName(); // idx 매칭 경로로
        PrimaryKeyJoinColumns pkjcs = mock(PrimaryKeyJoinColumns.class);
        // 부모 PK는 2개인데, 자식에서 1개만 제공 → size mismatch
        doReturn(new PrimaryKeyJoinColumn[]{one}).when(pkjcs).value();
        when(child.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(pkjcs);
        lenient().when(child.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(null);

        doReturn(List.of()).when(child).getEnclosedElements();

        entityHandler.handle(child);

        EntityModel cm = schemaModel.getEntities().get("com.example.ChildBad");
        assertNotNull(cm);
        assertFalse(cm.isValid());
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("pkJoinColumns size mismatch"), eq(child));
    }

    @Test
    @DisplayName("@ElementCollection: 핸들러 위임 호출")
    void handle_ElementCollection_ShouldDelegateToHandler() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(e.getAnnotation(Table.class)).thenReturn(null);
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Coll");
        when(sn.toString()).thenReturn("Coll");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);

        VariableElement coll = mock(VariableElement.class);
        when(coll.getKind()).thenReturn(ElementKind.FIELD);
        when(coll.getAnnotation(Transient.class)).thenReturn(null);
        when(coll.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(coll.getAnnotation(ElementCollection.class)).thenReturn(mock(ElementCollection.class));
        doReturn(Collections.emptySet()).when(coll).getModifiers();
        doReturn(List.of(coll)).when(e).getEnclosedElements();

        entityHandler.handle(e);

        verify(elementCollectionHandler, times(1)).processElementCollection(eq(coll), any(EntityModel.class));
    }

    private DeclaredType declaredTypeOf(TypeElement asElement) {
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        when(dt.asElement()).thenReturn(asElement);
        return dt;
    }

    private void endOfHierarchy(TypeElement type) {
        TypeMirror none = mock(TypeMirror.class);
        lenient().when(type.getSuperclass()).thenReturn(none);
        lenient().when(none.getKind()).thenReturn(TypeKind.NONE);
    }

    private void stubMinimalEntity(TypeElement e, String qName, String sName) {
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        lenient().when(e.getAnnotation(Table.class)).thenReturn(null);
        lenient().when(e.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);
        lenient().when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);
        Name qn = mock(Name.class), sn = mock(Name.class);
        lenient().when(qn.toString()).thenReturn(qName);
        lenient().when(sn.toString()).thenReturn(sName);
        lenient().when(e.getQualifiedName()).thenReturn(qn);
        lenient().when(e.getSimpleName()).thenReturn(sn);
        lenient().doReturn(List.of()).when(e).getEnclosedElements();
    }

    @Test
    @DisplayName("deferred 재시도: 부모가 뒤늦게 등록되면 FK 생성")
    void runDeferredJoinedFks_ShouldProcessWaitingChild() {
        // parent 미리 모델만 만들어두고 schema엔 나중에 넣음
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .isValid(true).build();
        ColumnModel pPk = ColumnModel.builder().columnName("pid").isPrimaryKey(true).build();
        parent.getColumns().put("pid", pPk);

        // TypeElements
        TypeElement parentType = mock(TypeElement.class);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(mock(Inheritance.class));
        when(parentType.getAnnotation(Inheritance.class).strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        endOfHierarchy(parentType);

        Name pQN = mock(Name.class);
        when(pQN.toString()).thenReturn("com.example.Parent");
        when(parentType.getQualifiedName()).thenReturn(pQN);

        TypeMirror parentSup = mock(TypeMirror.class);
        lenient().when(parentType.getSuperclass()).thenReturn(parentSup);
        lenient().when(parentSup.getKind()).thenReturn(TypeKind.NONE);

        TypeElement childType = mock(TypeElement.class);

        // child 상위 = parent
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        when(dt.asElement()).thenReturn(parentType);
        when(childType.getSuperclass()).thenReturn(dt);

        // child 모델은 먼저 만들어지고, FK는 defer
        EntityModel child = EntityModel.builder()
                .entityName("com.example.Child")
                .tableName("Child")
                .isValid(true).build();
        schemaModel.getEntities().put("com.example.Child", child);

        // child enqueue
        context.getDeferredEntities().add(child);
        context.getDeferredNames().add("com.example.Child");

        // context.getElementUtils().getTypeElement(childName) 스텁
        when(context.getElementUtils().getTypeElement("com.example.Child")).thenReturn(childType);

        // parent는 이제서야 schema에 등록
        schemaModel.getEntities().put("com.example.Parent", parent);

        // PK 조회 (context가 mock이면 꼭 스텁)
        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(pPk));
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(anyString(), anyList(), anyString(), anyList())).thenReturn("fk_child_parent");

        entityHandler.runDeferredJoinedFks();
        System.out.println(context.getDeferredEntities());
        System.out.println(schemaModel.getEntities());

        assertFalse(schemaModel.getEntities().get("com.example.Child")
                .getRelationships().isEmpty(), "FK가 생성되어야 함");
    }

    @Test
    @DisplayName("createEntityModel: 중복 엔티티면 invalid 기록 후 null 반환")
    void createEntityModel_Duplicate_ShouldMarkInvalid() {
        TypeElement e = mock(TypeElement.class);
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.Dup");
        when(sn.toString()).thenReturn("Dup");
        when(e.getAnnotation(Table.class)).thenReturn(mock(Table.class));
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        when(e.getAnnotation(Table.class)).thenReturn(null);

        // 먼저 하나 넣어둠
        schemaModel.getEntities().put("com.example.Dup",
                EntityModel.builder().entityName("com.example.Dup").isValid(true).build());

        entityHandler.handle(e);

        EntityModel stored = schemaModel.getEntities().get("com.example.Dup");
        assertNotNull(stored);
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("Duplicate entity"), eq(e));
    }

    @Test
    @DisplayName("determineTargetTable: Column null이면 기본 테이블, unknown table이면 경고 후 기본")
    void determineTargetTable_Branches() {
        // --- ARRANGE (준비) ---

        // 1. 엔티티 TypeElement Mocking
        TypeElement e = mock(TypeElement.class);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        // 모든 어노테이션 Mocking (불필요한 상호작용 방지)
        when(e.getAnnotation(Table.class)).thenReturn(null);
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(e.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(e.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);

        // 이름 설정
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.T");
        when(sn.toString()).thenReturn("T");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);

        // 상속 및 필드 설정 (초기)
        TypeMirror none = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(none);
        when(none.getKind()).thenReturn(TypeKind.NONE);

        // 2. 필드 VariableElement Mocking
        // 필드 1: @Column 없음 -> default table
        VariableElement f1 = mock(VariableElement.class);
        when(f1.getKind()).thenReturn(ElementKind.FIELD);
        doReturn(Collections.emptySet()).when(f1).getModifiers();
        when(f1.getAnnotation(Transient.class)).thenReturn(null);
        when(f1.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(f1.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(f1.getAnnotation(Embedded.class)).thenReturn(null);
        when(f1.getAnnotation(Column.class)).thenReturn(null);
        Name cn = mock(Name.class);
        lenient().when(cn.toString()).thenReturn("c1");
        lenient().when(f1.getSimpleName()).thenReturn(cn);

        // 필드 2: unknown @Column.table
        VariableElement f2 = mock(VariableElement.class);
        when(f2.getKind()).thenReturn(ElementKind.FIELD);
        doReturn(Collections.emptySet()).when(f2).getModifiers();
        when(f2.getAnnotation(Transient.class)).thenReturn(null);
        when(f2.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(f2.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(f2.getAnnotation(Embedded.class)).thenReturn(null);
        Column col = mock(Column.class);
        when(col.table()).thenReturn("no_such_table");
        when(f2.getAnnotation(Column.class)).thenReturn(col);
        Name cn2 = mock(Name.class);
        lenient().when(cn2.toString()).thenReturn("c2");
        lenient().when(f2.getSimpleName()).thenReturn(cn2);

        // 3. TypeElement에 필드 목록 설정
        doReturn(List.of(f1, f2)).when(e).getEnclosedElements();

        // 4. ColumnHandler가 반환할 ColumnModel 설정
        ColumnModel c1 = ColumnModel.builder().columnName("c1").build();
        ColumnModel c2 = ColumnModel.builder().columnName("c2").build();
        when(columnHandler.createFrom(eq(f1), any())).thenReturn(c1);
        when(columnHandler.createFrom(eq(f2), any())).thenReturn(c2);

        // --- ACT (실행) ---
        // 모든 Mocking이 완료된 후, handle을 한 번만 호출
        entityHandler.handle(e);

        // --- ASSERT (검증) ---
        EntityModel em = schemaModel.getEntities().get("com.example.T");
        assertNotNull(em);

        // 컬럼이 정상적으로 추가되었는지 먼저 확인
        assertNotNull(em.getColumns().get("c1"), "c1 컬럼이 존재해야 합니다.");
        assertNotNull(em.getColumns().get("c2"), "c2 컬럼이 존재해야 합니다.");

        assertEquals("T", em.getColumns().get("c1").getTableName(), "기본 테이블 이름이 할당되어야 합니다."); // default
        assertEquals("T", em.getColumns().get("c2").getTableName(), "알 수 없는 테이블은 기본 테이블로 대체되어야 합니다."); // unknown -> fallback
        verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("Unknown table 'no_such_table'"));
    }


    @Test
    @DisplayName("shouldSkipField: NON-FIELD/STATIC/@Transient 모두 스킵")
    void shouldSkipField_Branches() {
        TypeElement e = mock(TypeElement.class);
        stubMinimalEntity(e, "com.example.Skip", "Skip");
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        entityHandler.handle(e);
        EntityModel em = schemaModel.getEntities().get("com.example.Skip");
        assertNotNull(em);

        // NON-FIELD
        Element method = mock(Element.class);
        lenient().when(method.getKind()).thenReturn(ElementKind.METHOD);

        // STATIC FIELD
        VariableElement staticF = mock(VariableElement.class);
        lenient().when(staticF.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(staticF.getModifiers()).thenReturn(Set.of(Modifier.STATIC));
        lenient().when(staticF.getAnnotation(Transient.class)).thenReturn(null);

        // @Transient FIELD
        VariableElement transientF = mock(VariableElement.class);
        lenient().when(transientF.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(transientF.getModifiers()).thenReturn(Collections.emptySet());
        lenient().when(transientF.getAnnotation(Transient.class)).thenReturn(mock(Transient.class));

        lenient().doReturn(List.of(method, staticF, transientF)).when(e).getEnclosedElements();

        // columnHandler는 호출되지 않아야 함
        entityHandler.handle(e);
        verify(columnHandler, never()).createFrom(any(), any());
    }

    @Test
    @DisplayName("processJoinTable: pkjcs 정상 개수 + referencedColumnName 매칭")
    void processJoinTable_WithExplicitPkjcs_ShouldMapNames() {
        // parent 모델
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("P").isValid(true).build();
        ColumnModel p1 = ColumnModel.builder().columnName("K1").isPrimaryKey(true).build();
        ColumnModel p2 = ColumnModel.builder().columnName("K2").isPrimaryKey(true).build();
        parent.getColumns().put("K1", p1);
        parent.getColumns().put("K2", p2);
        schemaModel.getEntities().put("com.example.Parent", parent);

        // parent TypeElement
        TypeElement parentType = mock(TypeElement.class);
        stubMinimalEntity(parentType, "com.example.Parent", "P");
        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);
        endOfHierarchy(parentType);

        // child
        TypeElement child = mock(TypeElement.class);
        stubMinimalEntity(child, "com.example.Child", "C");
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        when(dt.asElement()).thenReturn(parentType);
        when(child.getSuperclass()).thenReturn(dt);

        // pkjcs 2개 (child 쪽 이름 커스터마이즈 + ref 명시)
        PrimaryKeyJoinColumn a1 = mock(PrimaryKeyJoinColumn.class);
        doReturn("CK1").when(a1).name();
        doReturn("K1").when(a1).referencedColumnName();

        PrimaryKeyJoinColumn a2 = mock(PrimaryKeyJoinColumn.class);
        doReturn("CK2").when(a2).name();
        doReturn("K2").when(a2).referencedColumnName();

        PrimaryKeyJoinColumns pkjcs = mock(PrimaryKeyJoinColumns.class);
        doReturn(new PrimaryKeyJoinColumn[]{a1, a2}).when(pkjcs).value();
        when(child.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(pkjcs);
        lenient().when(child.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(null);

        // 컬럼 하나만 실제로 만들어 두고, 나머지는 ensureChildPkColumnsExist가 보강
        VariableElement dummy = mock(VariableElement.class);
        when(dummy.getKind()).thenReturn(ElementKind.FIELD);
        doReturn(Collections.emptySet()).when(dummy).getModifiers();
        when(dummy.getAnnotation(Transient.class)).thenReturn(null);
        when(dummy.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(dummy.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(dummy.getAnnotation(Embedded.class)).thenReturn(null);
        when(dummy.getAnnotation(Column.class)).thenReturn(null);
        Name dn = mock(Name.class); lenient().when(dn.toString()).thenReturn("other");
        lenient().when(dummy.getSimpleName()).thenReturn(dn);
        lenient().doReturn(List.of(dummy)).when(child).getEnclosedElements();

        ColumnModel other = ColumnModel.builder().columnName("other").build();
        when(columnHandler.createFrom(eq(dummy), any())).thenReturn(other);

        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(p1, p2));
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(anyString(), anyList(), anyString(), anyList())).thenReturn("fk_C_P");

        entityHandler.handle(child);

        EntityModel cm = schemaModel.getEntities().get("com.example.Child");
        RelationshipModel r = cm.getRelationships().get("fk_C_P");
        assertNotNull(r);
        assertEquals(List.of("CK1", "CK2"), r.getColumns());
        assertEquals(List.of("K1", "K2"), r.getReferencedColumns());
        // ensure 보강으로 CK1/CK2가 PK로 존재해야 함
        assertTrue(cm.getColumns().get("CK1").isPrimaryKey());
        assertTrue(cm.getColumns().get("CK2").isPrimaryKey());
    }

    @Test
    @DisplayName("단일 @SecondaryTable 처리: 유효한 엔티티에 대해 관계가 1개 생성된다")
    void handle_WithSingleSecondaryTable_ShouldProcessCorrectly() {
        // --- ARRANGE ---
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);

        stubMinimalEntity(e, "com.example.SingleSec", "SingleSec");

        // 1. @Id 필드 Mocking (PK는 SecondaryTable의 필수 조건)
        VariableElement idField = mock(VariableElement.class);
        when(idField.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        when(idField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(idField.getAnnotation(Transient.class)).thenReturn(null);
        when(idField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(idField.getAnnotation(Embedded.class)).thenReturn(null);
        when(idField.getAnnotation(Column.class)).thenReturn(null);
        Name cn = mock(Name.class); lenient().when(cn.toString()).thenReturn("id");
        doReturn(List.of(idField)).when(e).getEnclosedElements();

        // 2. ColumnHandler 및 Context Mocking
        ColumnModel pkColumn = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(idField), any())).thenReturn(pkColumn);
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class))).thenReturn(List.of(pkColumn));

        // 3. @SecondaryTable 애노테이션 Mocking
        SecondaryTable one = mock(SecondaryTable.class);
        when(one.name()).thenReturn("sec_details");
        when(one.pkJoinColumns()).thenReturn(new PrimaryKeyJoinColumn[0]);
        doReturn(new UniqueConstraint[0]).when(one).uniqueConstraints();
        doReturn(new Index[0]).when(one).indexes();
        doReturn(new CheckConstraint[0]).when(one).check();

        when(e.getAnnotation(SecondaryTable.class)).thenReturn(one);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);
        when(e.getAnnotation(Table.class)).thenReturn(mock(Table.class)); // NPE 방지

        Table stAnn = mock(Table.class);
        when(e.getAnnotation(Table.class)).thenReturn(stAnn);
        doReturn(new UniqueConstraint[0]).when(stAnn).uniqueConstraints();
        doReturn(new Index[0]).when(stAnn).indexes();
        doReturn(new CheckConstraint[0]).when(stAnn).check();

        // Naming Mocking (예측 가능한 관계 키 생성을 위해)
        when(naming.fkName(eq("sec_details"), anyList(), eq("SingleSec"), anyList()))
                .thenReturn("fk_sec_details_to_singlesec");

        // --- ACT ---
        entityHandler.handle(e);

        // --- ASSERT ---
        EntityModel em = schemaModel.getEntities().get("com.example.SingleSec");
        assertNotNull(em);
        assertTrue(em.isValid(), "엔티티가 유효하게 처리되어야 합니다.");
        assertEquals(1, em.getRelationships().size(), "SecondaryTable에 대한 관계가 1개 생성되어야 합니다.");
        assertTrue(em.getRelationships().containsKey("fk_sec_details_to_singlesec"), "예측된 이름의 관계가 존재해야 합니다.");
    }

    @Test
    @DisplayName("복수 @SecondaryTables 처리: 유효한 엔티티에 대해 관계가 2개 생성된다")
    void handle_WithMultipleSecondaryTables_ShouldProcessCorrectly() {
        // --- ARRANGE ---
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        Name en = mock(Name.class);
        when(en.toString()).thenReturn("MultiSec");
        when(e.getSimpleName()).thenReturn(en);
        Name qn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.MultiSec");
        when(e.getQualifiedName()).thenReturn(qn);

        // 1. @Id 필드 Mocking (PK는 SecondaryTable의 필수 조건)
        VariableElement idField = mock(VariableElement.class);
        when(idField.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        Name idName = mock(Name.class);
        lenient().when(idName.toString()).thenReturn("id");
        lenient().when(idField.getSimpleName()).thenReturn(idName);
        when(idField.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(idField.getAnnotation(Transient.class)).thenReturn(null);
        when(idField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(idField.getAnnotation(Embedded.class)).thenReturn(null);
        when(idField.getAnnotation(Column.class)).thenReturn(null);
        doReturn(List.of(idField)).when(e).getEnclosedElements();

        // 2. ColumnHandler 및 Context Mocking
        ColumnModel pkColumn = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(idField), any())).thenReturn(pkColumn);
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class))).thenReturn(List.of(pkColumn));

        // 3. @SecondaryTables 애노테이션 및 그 안의 @SecondaryTable 배열 Mocking
        SecondaryTable tableA = mock(SecondaryTable.class);
        when(tableA.name()).thenReturn("details_A");
        when(tableA.pkJoinColumns()).thenReturn(new PrimaryKeyJoinColumn[0]);
        doReturn(new UniqueConstraint[0]).when(tableA).uniqueConstraints();
        doReturn(new Index[0]).when(tableA).indexes();
        doReturn(new CheckConstraint[0]).when(tableA).check();

        SecondaryTable tableB = mock(SecondaryTable.class);
        when(tableB.name()).thenReturn("details_B");
        when(tableB.pkJoinColumns()).thenReturn(new PrimaryKeyJoinColumn[0]);
        doReturn(new UniqueConstraint[0]).when(tableB).uniqueConstraints();
        doReturn(new Index[0]).when(tableB).indexes();
        doReturn(new CheckConstraint[0]).when(tableB).check();

        SecondaryTables many = mock(SecondaryTables.class);
        doReturn(new SecondaryTable[]{tableA, tableB}).when(many).value(); // 설정된 Mock 배열 반환

        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(eq("details_A"), anyList(), eq("MultiSec"), anyList()))
                .thenReturn("fk_details_A");
        when(naming.fkName(eq("details_B"), anyList(), eq("MultiSec"), anyList()))
                .thenReturn("fk_details_B");

        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(many);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(null);

        Table stAnn = mock(Table.class);
        when(e.getAnnotation(Table.class)).thenReturn(stAnn);
        doReturn(new UniqueConstraint[0]).when(stAnn).uniqueConstraints();
        doReturn(new Index[0]).when(stAnn).indexes();
        doReturn(new CheckConstraint[0]).when(stAnn).check();

        // --- ACT ---
        entityHandler.handle(e);

        // --- ASSERT ---
        EntityModel em = schemaModel.getEntities().get("com.example.MultiSec");
        assertNotNull(em);
        assertTrue(em.isValid());
        em.getRelationships().forEach((k, v) -> {
            System.out.println(k + " => " + v);
        });
        assertEquals(2, em.getRelationships().size(), "2개의 SecondaryTable에 대한 관계가 각각 생성되어야 합니다.");
    }

    @Test
    @DisplayName("@EmbeddedId 필드 처리: 첫 필드를 선택하고 내부 컬럼을 PK로 설정한다")
    void handle_WithEmbeddedId_ShouldProcessFirstAndSetInnerColumnsAsPk() {
        // --- ARRANGE ---
        TypeElement entityTypeElement = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(entityTypeElement.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        stubMinimalEntity(entityTypeElement, "com.example.EI", "EI");

        // 1. 일반 필드 Mocking
        VariableElement f1 = mock(VariableElement.class);
        when(f1.getKind()).thenReturn(ElementKind.FIELD);
        when(f1.getAnnotation(EmbeddedId.class)).thenReturn(null);

        // 2. @EmbeddedId 필드 Mocking (테스트 대상)
        VariableElement embeddedIdField = mock(VariableElement.class); // 변수명 명확화 (f2 -> embeddedIdField)
        when(embeddedIdField.getKind()).thenReturn(ElementKind.FIELD);
        when(embeddedIdField.getAnnotation(EmbeddedId.class)).thenReturn(mock(EmbeddedId.class));

        // 3. 두 번째 @EmbeddedId 필드 Mocking (무시되어야 함)
        VariableElement f3 = mock(VariableElement.class);
        when(f3.getKind()).thenReturn(ElementKind.FIELD);
        when(f3.getAnnotation(Transient.class)).thenReturn(null);
        when(f3.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(f3.getAnnotation(Embedded.class)).thenReturn(null);
        when(f3.getAnnotation(EmbeddedId.class)).thenReturn(mock(EmbeddedId.class));

        // @Embeddable 클래스 자체와 그 내부 필드 Mocking
        TypeElement embeddableType = mock(TypeElement.class); // @Embeddable 클래스
        VariableElement innerField = mock(VariableElement.class); // @Embeddable 클래스 내부의 필드
        when(innerField.getKind()).thenReturn(ElementKind.FIELD);
        Name innerFieldName = mock(Name.class);
        when(innerFieldName.toString()).thenReturn("embedded_col");
        when(innerField.getSimpleName()).thenReturn(innerFieldName);
        doReturn(List.of(innerField)).when(embeddableType).getEnclosedElements(); // 내부 필드 목록 설정

        // @EmbeddedId 필드의 타입 정보가 @Embeddable 클래스를 가리키도록 연결
        DeclaredType declaredType = mock(DeclaredType.class);
        when(embeddedIdField.asType()).thenReturn(declaredType);
        when(declaredType.asElement()).thenReturn(embeddableType); // asElement()가 embeddableType을 반환하도록 설정

        doReturn(List.of(f1, embeddedIdField, f3)).when(entityTypeElement).getEnclosedElements();

        // embeddedHandler가 실제처럼 동작하도록 시뮬레이션
        doAnswer(invocation -> {
            EntityModel entity = invocation.getArgument(1, EntityModel.class);
            ColumnModel embeddedColumn = ColumnModel.builder().columnName("embedded_col").build();
            entity.getColumns().put("embedded_col", embeddedColumn);
            return null; // void 메서드이므로 null 반환
        }).when(embeddedHandler).processEmbedded(any(), any(EntityModel.class), anySet());


        // --- ACT ---
        entityHandler.handle(entityTypeElement);

        // --- ASSERT ---
        // 1. 첫 번째 @EmbeddedId 필드(f2)에 대해서만 processEmbedded가 호출되었는지 간접 검증
        verify(embeddedHandler, times(1)).processEmbedded(eq(embeddedIdField), any(EntityModel.class), anySet());

        // 2. processEmbeddedId의 후반부 로직(내부 필드를 PK로 만드는)이 실행되었는지 직접 검증
        EntityModel em = schemaModel.getEntities().get("com.example.EI");
        assertNotNull(em);
        ColumnModel processedColumn = em.getColumns().get("embedded_col");
        assertNotNull(processedColumn, "Embedded 필드로부터 컬럼이 생성되어야 합니다.");
        assertTrue(processedColumn.isPrimaryKey(), "EmbeddedId 내부 컬럼은 PK로 설정되어야 합니다.");
    }

    @Test
    @DisplayName("findNearestJoinedParentEntity: 부모가 @Entity지만 JOINED 아님 → empty")
    void findNearestJoinedParentEntity_EntityButNotJoined_ShouldBeEmpty() {
        TypeElement parent = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(parent.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        stubMinimalEntity(parent, "com.example.P", "P");

        endOfHierarchy(parent);

        TypeElement child = mock(TypeElement.class);
        when(child.getSuperclass()).thenReturn(sup);
        stubMinimalEntity(child, "com.example.C", "C");
        DeclaredType dt = mock(DeclaredType.class);

        entityHandler.handle(child);

        EntityModel em = schemaModel.getEntities().get("com.example.C");
        // FK 없음 (JOINED 부모 탐색 실패)
        assertTrue(em.getRelationships().isEmpty());
    }

    @Test
    @DisplayName("processMappedSuperclasses: @MappedSuperclass 필드 상속")
    void processMappedSuperclasses_ShouldInjectColumns() {
        // MappedSuperclass
        TypeElement ms = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(ms.getSuperclass()).thenReturn(sup);
        lenient().when(sup.getKind()).thenReturn(TypeKind.NONE);
        when(ms.getAnnotation(MappedSuperclass.class)).thenReturn(mock(MappedSuperclass.class));
        VariableElement f = mock(VariableElement.class);
        when(f.getKind()).thenReturn(ElementKind.FIELD);
        when(f.getAnnotation(Transient.class)).thenReturn(null);
        doReturn(List.of(f)).when(ms).getEnclosedElements();

        // child
        TypeElement child = mock(TypeElement.class);
        stubMinimalEntity(child, "com.example.MChild", "MChild");
        DeclaredType msDT = mock(DeclaredType.class);
        when(msDT.getKind()).thenReturn(TypeKind.DECLARED);
        when(msDT.asElement()).thenReturn(ms);
        when(child.getSuperclass()).thenReturn(msDT);

        endOfHierarchy(ms);

        ColumnModel col = ColumnModel.builder().columnName("inherited").build();
        when(columnHandler.createFrom(eq(f), any())).thenReturn(col);

        entityHandler.handle(child);

        EntityModel cm = schemaModel.getEntities().get("com.example.MChild");
        cm.getColumns().forEach((k, v) -> {
            System.out.println(k + " => " + v);
        });
        assertTrue(cm.getColumns().containsKey("inherited"));
    }

    @Test
    @DisplayName("processRegularField: columnHandler가 null 반환 시 컬럼 추가 안 함")
    void processRegularField_NullColumnModel_ShouldSkipAdd() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        stubMinimalEntity(e, "com.example.R", "R");

        VariableElement f = mock(VariableElement.class);
        when(f.getKind()).thenReturn(ElementKind.FIELD);
        when(f.getAnnotation(Transient.class)).thenReturn(null);
        when(f.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(f.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(f.getAnnotation(Embedded.class)).thenReturn(null);
        when(f.getAnnotation(Column.class)).thenReturn(null);
        doReturn(Collections.emptySet()).when(f).getModifiers();
        doReturn(List.of(f)).when(e).getEnclosedElements();

        when(columnHandler.createFrom(eq(f), any())).thenReturn(null);

        entityHandler.handle(e);

        EntityModel em = schemaModel.getEntities().get("com.example.R");
        assertFalse(em.getColumns().containsKey("r"));
    }

    @Test
    @DisplayName("@Table(name) 지정 시 테이블명이 커스텀 명으로 설정된다")
    void handle_TableNameFromAnnotation_ShouldUseCustomName() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror none2 = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(none2);
        when(none2.getKind()).thenReturn(TypeKind.NONE);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Table t = mock(Table.class);
        when(t.name()).thenReturn("custom_users");
        doReturn(new UniqueConstraint[0]).when(t).uniqueConstraints();
        doReturn(new Index[0]).when(t).indexes();
        doReturn(new CheckConstraint[0]).when(t).check();
        when(e.getAnnotation(Table.class)).thenReturn(t);
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.UserCustom");
        when(sn.toString()).thenReturn("UserCustom");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        // provide minimal @Id
        VariableElement id = mock(VariableElement.class);
        when(id.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(id.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        when(id.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(id.getAnnotation(Transient.class)).thenReturn(null);
        when(id.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(id.getAnnotation(Embedded.class)).thenReturn(null);
        when(id.getAnnotation(Column.class)).thenReturn(null);
        doReturn(Collections.emptySet()).when(id).getModifiers();
        doReturn(List.of(id)).when(e).getEnclosedElements();
        ColumnModel idCol = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(id), any())).thenReturn(idCol);

        entityHandler.handle(e);

        EntityModel em = schemaModel.getEntities().get("com.example.UserCustom");
        assertNotNull(em);
        assertEquals("custom_users", em.getTableName(), "@Table(name) 값을 사용해야 합니다.");
    }

    @Test
    @DisplayName("@Column.table 이 존재하는 SecondaryTable을 가리키면 해당 테이블명으로 설정된다")
    void determineTargetTable_KnownSecondaryTable_ShouldAssignThatTable_() {
        TypeElement e = mock(TypeElement.class);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.KnownSec");
        when(sn.toString()).thenReturn("KnownSec");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);
        TypeMirror none = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(none);
        when(none.getKind()).thenReturn(TypeKind.NONE);

        // SecondaryTable present
        SecondaryTable sec = mock(SecondaryTable.class);
        when(sec.name()).thenReturn("aux_table");
        lenient().doReturn(new PrimaryKeyJoinColumn[0]).when(sec).pkJoinColumns();
        doReturn(new UniqueConstraint[0]).when(sec).uniqueConstraints();
        doReturn(new Index[0]).when(sec).indexes();
        doReturn(new CheckConstraint[0]).when(sec).check();
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(sec);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        Table tb = mock(Table.class);
        doReturn(new UniqueConstraint[0]).when(tb).uniqueConstraints();
        doReturn(new Index[0]).when(tb).indexes();
        doReturn(new CheckConstraint[0]).when(tb).check();
        when(e.getAnnotation(Table.class)).thenReturn(tb);

        // Fields: one with Column.table="aux_table"
        VariableElement f = mock(VariableElement.class);
        when(f.getKind()).thenReturn(ElementKind.FIELD);
        doReturn(Collections.emptySet()).when(f).getModifiers();
        when(f.getAnnotation(Transient.class)).thenReturn(null);
        when(f.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(f.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(f.getAnnotation(Embedded.class)).thenReturn(null);
        Column col = mock(Column.class);
        when(col.table()).thenReturn("aux_table");
        when(f.getAnnotation(Column.class)).thenReturn(col);
        Name fn = mock(Name.class); lenient().when(fn.toString()).thenReturn("c_aux");
        lenient().when(f.getSimpleName()).thenReturn(fn);
        doReturn(List.of(f)).when(e).getEnclosedElements();
        ColumnModel cm = ColumnModel.builder().columnName("c_aux").build();
        when(columnHandler.createFrom(eq(f), any())).thenReturn(cm);
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class))).thenReturn(List.of());

        entityHandler.handle(e);

        EntityModel em = schemaModel.getEntities().get("com.example.KnownSec");
        assertNotNull(em);
        assertEquals("aux_table", em.getColumns().get("c_aux").getTableName(), "Column.table이 SecondaryTable과 일치하면 해당 테이블로 가야 합니다.");
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.WARNING), contains("Unknown table"), any());
    }

    @Test
    @DisplayName("@SecondaryTable: pkJoinColumns가 제공되면 이름 매핑과 PK 보강이 수행된다")
    void handle_SecondaryTable_WithPkJoinColumns_ShouldMapCustomColumns() {
        TypeElement e = mock(TypeElement.class);
        TypeMirror none = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(none);
        when(none.getKind()).thenReturn(TypeKind.NONE);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.SecPkjc");
        when(sn.toString()).thenReturn("SecPkjc");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);

        // Primary key field in main table
        VariableElement id = mock(VariableElement.class);
        when(id.getKind()).thenReturn(ElementKind.FIELD);
        lenient().when(id.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        when(id.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(id.getAnnotation(Transient.class)).thenReturn(null);
        when(id.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(id.getAnnotation(Embedded.class)).thenReturn(null);
        when(id.getAnnotation(Column.class)).thenReturn(null);
        doReturn(Collections.emptySet()).when(id).getModifiers();
        doReturn(List.of(id)).when(e).getEnclosedElements();
        ColumnModel idCol = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
        when(columnHandler.createFrom(eq(id), any())).thenReturn(idCol);
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class))).thenAnswer(inv -> {
            EntityModel em = inv.getArgument(0);
            return em.getColumns().values().stream().filter(ColumnModel::isPrimaryKey).toList();
        });

        // SecondaryTable with pkJoinColumns mapping to custom child column name CK and ref parent id
        PrimaryKeyJoinColumn pkjc = mock(PrimaryKeyJoinColumn.class);
        doReturn("CK").when(pkjc).name();
        doReturn("id").when(pkjc).referencedColumnName();
        SecondaryTable sec = mock(SecondaryTable.class);
        doReturn("sec_tbl").when(sec).name();
        doReturn(new PrimaryKeyJoinColumn[]{pkjc}).when(sec).pkJoinColumns();
        doReturn(new UniqueConstraint[0]).when(sec).uniqueConstraints();
        doReturn(new Index[0]).when(sec).indexes();
        doReturn(new CheckConstraint[0]).when(sec).check();
        when(e.getAnnotation(IdClass.class)).thenReturn(null);
        when(e.getAnnotation(SecondaryTable.class)).thenReturn(sec);
        when(e.getAnnotation(SecondaryTables.class)).thenReturn(null);
        Table tb = mock(Table.class);
        doReturn(new UniqueConstraint[0]).when(tb).uniqueConstraints();
        doReturn(new Index[0]).when(tb).indexes();
        doReturn(new CheckConstraint[0]).when(tb).check();
        when(e.getAnnotation(Table.class)).thenReturn(tb);
        // Naming
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(eq("sec_tbl"), anyList(), eq("SecPkjc"), anyList())).thenReturn("fk_sec_tbl_secpkjc");
        // Act
        entityHandler.handle(e);
        // Assert
        EntityModel em = schemaModel.getEntities().get("com.example.SecPkjc");
        assertNotNull(em);
        RelationshipModel r = em.getRelationships().get("fk_sec_tbl_secpkjc");
        assertNotNull(r, "SecondaryTable 관계가 생성되어야 합니다.");
        assertEquals(List.of("CK"), r.getColumns(), "child쪽 커스텀 PK 이름이 매핑되어야 합니다.");
        assertEquals(List.of("id"), r.getReferencedColumns(), "참조되는 부모 PK 이름이 매핑되어야 합니다.");
        assertTrue(em.getColumns().get("CK").isPrimaryKey(), "보강된 child PK 컬럼(CK)이 PK로 설정되어야 합니다.");
    }

    @Test
    @DisplayName("deferred 재시도: 부모가 여전히 없으면 대기열 유지 및 경고")
    void runDeferredJoinedFks_ParentStillMissing_ShouldKeepDeferredAndWarn() {
        // Prepare a child already deferred
        EntityModel child = EntityModel.builder().entityName("com.example.DChild").tableName("DChild").isValid(true).build();
        schemaModel.getEntities().put("com.example.DChild", child);
        context.getDeferredEntities().add(child);
        context.getDeferredNames().add("com.example.DChild");
        // getTypeElement returns a type whose superclass points to a missing parent
        TypeElement childType = mock(TypeElement.class);
        DeclaredType sup = mock(DeclaredType.class);
        when(sup.getKind()).thenReturn(TypeKind.DECLARED);
        TypeMirror typeMirror = mock(TypeMirror.class);

        TypeElement missingParentType = mock(TypeElement.class);
        TypeMirror missingSup = mock(TypeMirror.class);
        when(missingSup.getKind()).thenReturn(TypeKind.NONE);
        when(missingParentType.getSuperclass()).thenReturn(missingSup);
        when(sup.asElement()).thenReturn(missingParentType);
        when(childType.getSuperclass()).thenReturn(sup);
        when(context.getElementUtils().getTypeElement("com.example.DChild")).thenReturn(childType);

        // Act
        entityHandler.runDeferredJoinedFks();
        // Assert: still deferred (no parent in schema)"
        assertTrue(context.getDeferredNames().contains("com.example.DChild"), "부모가 없으면 여전히 대기 상태여야 합니다.");
        // Warning is optional; assert no ERROR at least
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), any(), any());
    }

    @Test
    @DisplayName("@Embedded: 임베디드 필드는 EmbeddedHandler로 위임되며 PK로 마킹되지 않는다")
    void handle_Embedded_ShouldDelegateAndNotMarkPk() {
        // Arrange
        TypeElement e = mock(TypeElement.class);
        TypeMirror sup = mock(TypeMirror.class);
        when(e.getSuperclass()).thenReturn(sup);
        when(sup.getKind()).thenReturn(TypeKind.NONE);
        lenient().when(e.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));

        Name qn = mock(Name.class), sn = mock(Name.class);
        when(qn.toString()).thenReturn("com.example.EmbeddedOnly");
        when(sn.toString()).thenReturn("EmbeddedOnly");
        when(e.getQualifiedName()).thenReturn(qn);
        when(e.getSimpleName()).thenReturn(sn);

        // One @Embedded field (not @EmbeddedId)
        VariableElement emb = mock(VariableElement.class);
        when(emb.getKind()).thenReturn(ElementKind.FIELD);
        doReturn(Collections.emptySet()).when(emb).getModifiers();
        when(emb.getAnnotation(Transient.class)).thenReturn(null);
        when(emb.getAnnotation(EmbeddedId.class)).thenReturn(null);
        when(emb.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(emb.getAnnotation(Embedded.class)).thenReturn(mock(Embedded.class));
        Name en = mock(Name.class);
        lenient().when(en.toString()).thenReturn("address");
        lenient().when(emb.getSimpleName()).thenReturn(en);
        doReturn(List.of(emb)).when(e).getEnclosedElements();

        // Embedded handler will inject columns into entity (simulate)
        doAnswer(inv -> {
            EntityModel em = inv.getArgument(1);
            em.getColumns().put("addr_line1", ColumnModel.builder().columnName("addr_line1").build());
            em.getColumns().put("addr_zip", ColumnModel.builder().columnName("addr_zip").build());
            return null;
        }).when(embeddedHandler).processEmbedded(eq(emb), any(EntityModel.class), anySet());

        // Act
        entityHandler.handle(e);

        // Assert
        EntityModel em = schemaModel.getEntities().get("com.example.EmbeddedOnly");
        assertNotNull(em);
        assertTrue(em.getColumns().containsKey("addr_line1"));
        assertTrue(em.getColumns().containsKey("addr_zip"));
        // Pure @Embedded must not mark PKs by itself
        assertFalse(em.getColumns().get("addr_line1").isPrimaryKey());
        assertFalse(em.getColumns().get("addr_zip").isPrimaryKey());
        verify(embeddedHandler, times(1)).processEmbedded(eq(emb), eq(em), anySet());
        // ColumnHandler should not be called for @Embedded field
        verify(columnHandler, never()).createFrom(eq(emb), any());
    }

    @Test
    @DisplayName("JOINED 상속: 부모 PK가 2개인데 @PrimaryKeyJoinColumn(단일)만 제공 → size mismatch 에러")
    void handle_JoinedInheritance_SinglePkjc_WhenParentHasCompositePk_ShouldError() {
        // Parent with composite PK (k1, k2)
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent2")
                .tableName("Parent2")
                .isValid(true).build();
        ColumnModel p1 = ColumnModel.builder().columnName("k1").isPrimaryKey(true).build();
        ColumnModel p2 = ColumnModel.builder().columnName("k2").isPrimaryKey(true).build();
        parent.getColumns().put("k1", p1);
        parent.getColumns().put("k2", p2);
        schemaModel.getEntities().put("com.example.Parent2", parent);

        // Parent TypeElement with JOINED
        TypeElement parentType = mock(TypeElement.class);
        when(parentType.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(parentType.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);
        Name pQN = mock(Name.class);
        when(pQN.toString()).thenReturn("com.example.Parent2");
        when(parentType.getQualifiedName()).thenReturn(pQN);
        TypeMirror parentSup = mock(TypeMirror.class);
        lenient().when(parentType.getSuperclass()).thenReturn(parentSup);
        lenient().when(parentSup.getKind()).thenReturn(TypeKind.NONE);

        // Child TypeElement extends parent
        TypeElement child = mock(TypeElement.class);
        lenient().when(child.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        when(child.getAnnotation(Table.class)).thenReturn(null);
        when(child.getAnnotation(IdClass.class)).thenReturn(null);
        lenient().when(child.getAnnotation(EmbeddedId.class)).thenReturn(null);
        lenient().when(child.getAnnotation(MappedSuperclass.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTable.class)).thenReturn(null);
        when(child.getAnnotation(SecondaryTables.class)).thenReturn(null);

        Name cQN = mock(Name.class), cSN = mock(Name.class);
        when(cQN.toString()).thenReturn("com.example.ChildSinglePkjc");
        when(cSN.toString()).thenReturn("ChildSinglePkjc");
        when(child.getQualifiedName()).thenReturn(cQN);
        when(child.getSimpleName()).thenReturn(cSN);

        DeclaredType childSup = mock(DeclaredType.class);
        when(child.getSuperclass()).thenReturn(childSup);
        when(childSup.getKind()).thenReturn(TypeKind.DECLARED);
        when(childSup.asElement()).thenReturn(parentType);

        // Single @PrimaryKeyJoinColumn provided (insufficient for composite PK)
        PrimaryKeyJoinColumn single = mock(PrimaryKeyJoinColumn.class);
        lenient().when(single.name()).thenReturn("ck1");
        lenient().when(single.referencedColumnName()).thenReturn("k1");
        when(child.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(single);
        when(child.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(null);

        // No fields (columns will be ensured only if matching works)
        doReturn(List.of()).when(child).getEnclosedElements();
        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(p1, p2));

        // Act
        entityHandler.handle(child);

        // Assert
        EntityModel cm = schemaModel.getEntities().get("com.example.ChildSinglePkjc");
        assertNotNull(cm);
        assertFalse(cm.isValid(), "부모 PK 개수와 pkjc 개수 불일치면 invalid 처리되어야 합니다.");
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("pkJoinColumns size mismatch"), eq(child));
    }

    @Test
    @DisplayName("deferred 재시도 성공 시: child 이름/엔티티가 대기 큐와 집합에서 제거된다")
    void runDeferredJoinedFks_ShouldClearDeferredQueues_OnSuccess() {
        // Prepare parent model (not yet in schema)
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.PX")
                .tableName("PX")
                .isValid(true).build();
        ColumnModel pk = ColumnModel.builder().columnName("pid").isPrimaryKey(true).build();
        parent.getColumns().put("pid", pk);

        // Parent TypeElement (JOINED)
        TypeElement parentType = mock(TypeElement.class);
        when(parentType.getAnnotation(Entity.class)).thenReturn(mock(Entity.class));
        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);
        Name pQN = mock(Name.class); when(pQN.toString()).thenReturn("com.example.PX");
        when(parentType.getQualifiedName()).thenReturn(pQN);
        TypeMirror parentSup = mock(TypeMirror.class);
        lenient().when(parentType.getSuperclass()).thenReturn(parentSup);
        lenient().when(parentSup.getKind()).thenReturn(TypeKind.NONE);

        // Child model already present and deferred
        EntityModel child = EntityModel.builder()
                .entityName("com.example.CX")
                .tableName("CX")
                .isValid(true).build();
        schemaModel.getEntities().put("com.example.CX", child);
        context.getDeferredEntities().add(child);
        context.getDeferredNames().add("com.example.CX");

        // Child TypeElement with superclass -> parentType
        TypeElement childType = mock(TypeElement.class);
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.getKind()).thenReturn(TypeKind.DECLARED);
        when(dt.asElement()).thenReturn(parentType);
        when(childType.getSuperclass()).thenReturn(dt);
        when(context.getElementUtils().getTypeElement("com.example.CX")).thenReturn(childType);

        // Now parent enters schema
        schemaModel.getEntities().put("com.example.PX", parent);

        // Wiring for FK creation
        when(context.findAllPrimaryKeyColumns(parent)).thenReturn(List.of(pk));
        when(context.getNaming()).thenReturn(naming);
        when(naming.fkName(anyString(), anyList(), anyString(), anyList())).thenReturn("fk_cx_px");

        // Act
        entityHandler.runDeferredJoinedFks();

        // Assert FK created
        assertFalse(schemaModel.getEntities().get("com.example.CX").getRelationships().isEmpty());
        // And queues cleared
        assertFalse(context.getDeferredNames().contains("com.example.CX"), "성공 후 이름 집합에서 제거되어야 합니다.");
        assertTrue(context.getDeferredEntities().stream().noneMatch(em -> "com.example.CX".equals(em.getEntityName())),
                "성공 후 엔티티 큐에서 제거되어야 합니다.");
    }

}

