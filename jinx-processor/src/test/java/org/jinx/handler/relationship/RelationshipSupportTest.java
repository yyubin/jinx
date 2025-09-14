package org.jinx.handler.relationship;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;

import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationshipSupportTest {

    private ProcessingContext ctx;
    private Messager messager;
    private Naming naming;
    private RelationshipSupport support;

    @BeforeEach
    void setUp() {
        ctx = mock(ProcessingContext.class);
        messager = mock(Messager.class);
        naming = mock(Naming.class);

        when(ctx.getMessager()).thenReturn(messager);
        when(ctx.getNaming()).thenReturn(naming);

        support = new RelationshipSupport(ctx);
    }

    // ---------- resolveJoinColumnTable ----------

    @Test
    @DisplayName("resolveJoinColumnTable: null JoinColumn 또는 빈 table → 기본 테이블")
    void resolveJoinColumnTable_nullOrEmpty() {
        EntityModel owner = mock(EntityModel.class);
        when(owner.getTableName()).thenReturn("users");

        assertEquals("users", support.resolveJoinColumnTable(null, owner));

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.table()).thenReturn("");
        assertEquals("users", support.resolveJoinColumnTable(jc, owner));
        verifyNoInteractions(messager);
    }

    @Test
    @DisplayName("resolveJoinColumnTable: secondary table로 유효하게 지정된 경우 그대로 사용")
    void resolveJoinColumnTable_secondaryOk() {
        EntityModel owner = mock(EntityModel.class);
        when(owner.getTableName()).thenReturn("users");
        when(owner.getEntityName()).thenReturn("User");

        SecondaryTableModel st = mock(SecondaryTableModel.class);
        when(st.getName()).thenReturn("user_ext");
        when(owner.getSecondaryTables()).thenReturn(List.of(st));

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.table()).thenReturn("user_ext");

        assertEquals("user_ext", support.resolveJoinColumnTable(jc, owner));
        verifyNoInteractions(messager);
    }

    @Test
    @DisplayName("resolveJoinColumnTable: 존재하지 않는 테이블 지정 → 경고 후 기본 테이블 fallback")
    void resolveJoinColumnTable_invalidWarns() {
        EntityModel owner = mock(EntityModel.class);
        when(owner.getTableName()).thenReturn("users");
        when(owner.getEntityName()).thenReturn("User");
        when(owner.getSecondaryTables()).thenReturn(List.of());

        JoinColumn jc = mock(JoinColumn.class);
        when(jc.table()).thenReturn("bad_table");

        String resolved = support.resolveJoinColumnTable(jc, owner);
        assertEquals("users", resolved);
        verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("bad_table"));
    }

    // ---------- resolveTargetEntity ----------

    @Test
    @DisplayName("resolveTargetEntity: 명시적 targetEntity 우선")
    void resolveTargetEntity_explicitAnnotation() {
        Elements elements = mock(Elements.class);
        when(ctx.getElementUtils()).thenReturn(elements);

        TypeElement te = mock(TypeElement.class);
        when(elements.getTypeElement("java.lang.String")).thenReturn(te);

        ManyToOne m2o = mock(ManyToOne.class);
        doReturn(String.class).when(m2o).targetEntity();

        AttributeDescriptor attr = mock(AttributeDescriptor.class);
        when(attr.genericArg(anyInt())).thenReturn(Optional.empty());
        when(attr.type()).thenReturn(mock(TypeMirror.class));

        Optional<TypeElement> found = support.resolveTargetEntity(attr, m2o, null, null, null);
        assertTrue(found.isPresent());
        assertSame(te, found.get());
    }

    @Test
    @DisplayName("resolveTargetEntity: 컬렉션이면 genericArg(0) 사용")
    void resolveTargetEntity_collectionGeneric() {
        AttributeDescriptor attr = mock(AttributeDescriptor.class);

        TypeElement te = mock(TypeElement.class);
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.asElement()).thenReturn(te);
        when(attr.genericArg(0)).thenReturn(Optional.of(dt));

        Optional<TypeElement> found = support.resolveTargetEntity(attr, null, null, mock(OneToMany.class), null);
        assertTrue(found.isPresent());
        assertSame(te, found.get());
    }

    @Test
    @DisplayName("resolveTargetEntity: fallback으로 속성 타입 사용")
    void resolveTargetEntity_fallbackPropertyType() {
        AttributeDescriptor attr = mock(AttributeDescriptor.class);
        TypeElement te = mock(TypeElement.class);
        DeclaredType dt = mock(DeclaredType.class);
        when(dt.asElement()).thenReturn(te);
        when(attr.genericArg(0)).thenReturn(Optional.empty());
        when(attr.type()).thenReturn(dt);

        Optional<TypeElement> found = support.resolveTargetEntity(attr, null, null, null, null);
        assertTrue(found.isPresent());
        assertSame(te, found.get());
    }

    // ---------- findColumn / putColumn delegate ----------

    @Test
    @DisplayName("findColumn/putColumn: EntityModel 위임 확인")
    void delegate_find_put_column() {
        EntityModel entity = mock(EntityModel.class);
        ColumnModel col = mock(ColumnModel.class);

        when(entity.findColumn("t", "c")).thenReturn(col);

        assertSame(col, support.findColumn(entity, "t", "c"));
        support.putColumn(entity, col);
        verify(entity).putColumn(col);
    }

    // ---------- allSameConstraintMode ----------

    @Test
    @DisplayName("allSameConstraintMode: 모두 동일하면 true, 아니면 false")
    void allSameConstraintMode_cases() {
        assertTrue(support.allSameConstraintMode(List.of()));

        JoinColumn a = joinColumnWithMode(ConstraintMode.CONSTRAINT);
        JoinColumn b = joinColumnWithMode(ConstraintMode.CONSTRAINT);
        JoinColumn c = joinColumnWithMode(ConstraintMode.NO_CONSTRAINT);

        assertTrue(support.allSameConstraintMode(List.of(a, b)));
        assertFalse(support.allSameConstraintMode(List.of(a, c)));
    }

    // ---------- toCascadeList ----------

    @Test
    @DisplayName("toCascadeList: null은 빈 리스트, 배열은 List로 변환")
    void toCascadeList_cases() {
        assertTrue(support.toCascadeList(null).isEmpty());

        var list = support.toCascadeList(new CascadeType[]{CascadeType.PERSIST, CascadeType.MERGE});
        assertEquals(2, list.size());
        assertEquals(CascadeType.PERSIST, list.get(0));
        assertEquals(CascadeType.MERGE, list.get(1));
    }

    // ---------- coveredByPkOrUnique / hasSameIndex / addForeignKeyIndex ----------

    @Test
    @DisplayName("coveredByPkOrUnique: 동일 집합이면 true, 아니면 false")
    void coveredByPkOrUnique_cases() {
        EntityModel e = mock(EntityModel.class);
        Map<String, ConstraintModel> cons = new HashMap<>();
        when(e.getConstraints()).thenReturn(cons);

        cons.put("pk_users_id",
                ConstraintModel.builder()
                        .name("pk_users_id")
                        .type(ConstraintType.PRIMARY_KEY)
                        .tableName("users")
                        .columns(List.of("id"))
                        .build());

        assertTrue(support.coveredByPkOrUnique(e, "users", List.of("id")));
        assertFalse(support.coveredByPkOrUnique(e, "users", List.of("org_id", "id")));
        assertFalse(support.coveredByPkOrUnique(e, "orders", List.of("id")));
    }

    @Test
    @DisplayName("hasSameIndex: 동일 테이블+컬럼 순서 완전일치만 true")
    void hasSameIndex_cases() {
        EntityModel e = mock(EntityModel.class);
        Map<String, IndexModel> idx = new HashMap<>();
        when(e.getIndexes()).thenReturn(idx);

        idx.put("ix_users_org_id_id",
                IndexModel.builder()
                        .indexName("ix_users_org_id_id")
                        .tableName("users")
                        .columnNames(List.of("org_id", "id"))
                        .build());

        assertTrue(support.hasSameIndex(e, "users", List.of("org_id", "id")));
        assertFalse(support.hasSameIndex(e, "users", List.of("id", "org_id"))); // 순서 다르면 false
        assertFalse(support.hasSameIndex(e, "orders", List.of("org_id", "id")));
    }

    @Test
    @DisplayName("addForeignKeyIndex: PK/UNIQUE로 커버되면 생략, 동일 인덱스 있으면 생략, 아니면 생성")
    void addForeignKeyIndex_flow() {
        EntityModel e = mock(EntityModel.class);
        Map<String, ConstraintModel> cons = new HashMap<>();
        Map<String, IndexModel> idx = new HashMap<>();
        when(e.getConstraints()).thenReturn(cons);
        when(e.getIndexes()).thenReturn(idx);

        // PK(id) 존재 → (id) 인덱스는 생략
        cons.put("pk_users_id",
                ConstraintModel.builder()
                        .name("pk_users_id")
                        .type(ConstraintType.PRIMARY_KEY)
                        .tableName("users")
                        .columns(List.of("id"))
                        .build());

        support.addForeignKeyIndex(e, List.of("id"), "users");
        assertTrue(idx.isEmpty());

        // 동일 인덱스가 이미 있으면 생략
        when(naming.ixName("users", List.of("org_id", "id"))).thenReturn("ix_users_org_id_id");
        idx.put("ix_users_org_id_id",
                IndexModel.builder().indexName("ix_users_org_id_id").tableName("users")
                        .columnNames(List.of("org_id", "id")).build());

        support.addForeignKeyIndex(e, List.of("org_id", "id"), "users");
        assertEquals(1, idx.size());

        // 새 인덱스 생성
        idx.clear();
        when(naming.ixName("users", List.of("org_id", "id"))).thenReturn("ix_new");
        support.addForeignKeyIndex(e, List.of("org_id", "id"), "users");

        assertEquals(1, idx.size());
        IndexModel created = idx.get("ix_new");
        assertNotNull(created);
        assertEquals(List.of("org_id", "id"), created.getColumnNames());
        assertEquals("users", created.getTableName());
    }

    // ---------- promoteColumnsToPrimaryKey ----------

    @Test
    @DisplayName("promoteColumnsToPrimaryKey: 누락 컬럼이면 ERROR 로그 및 entity.setValid(false)")
    void promoteColumnsToPrimaryKey_missingColumn() {
        EntityModel e = mock(EntityModel.class);
        when(e.findColumn("users", "missing")).thenReturn(null);

        support.promoteColumnsToPrimaryKey(e, "users", List.of("missing"));

        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("Missing FK column"));
        verify(e).setValid(false);
        verify(e, never()).getConstraints();
    }

    @Test
    @DisplayName("promoteColumnsToPrimaryKey: 컬럼 PK/NN 설정 및 PK 제약 추가")
    void promoteColumnsToPrimaryKey_success() {
        EntityModel e = mock(EntityModel.class);
        Map<String, ConstraintModel> cons = new HashMap<>();
        when(e.getConstraints()).thenReturn(cons);

        ColumnModel col = mock(ColumnModel.class);
        when(col.getTableName()).thenReturn(null); // 테이블명도 세팅해보자
        when(e.findColumn("users", "order_id")).thenReturn(col);

        when(naming.pkName("users", List.of("order_id"))).thenReturn("pk_users_order_id");

        support.promoteColumnsToPrimaryKey(e, "users", List.of("order_id"));

        // 컬럼 속성 변경 호출되었는지
        verify(col).setPrimaryKey(true);
        verify(col).setNullable(false);
        verify(col).setTableName("users");

        // 제약 추가 확인
        ConstraintModel pk = cons.get("pk_users_order_id");
        assertNotNull(pk);
        assertEquals(ConstraintType.PRIMARY_KEY, pk.getType());
        assertEquals(List.of("order_id"), pk.getColumns());
        assertEquals("users", pk.getTableName());
    }

    private JoinColumn joinColumnWithMode(ConstraintMode mode) {
        JoinColumn jc = mock(JoinColumn.class);
        ForeignKey fk = mock(ForeignKey.class);
        when(fk.value()).thenReturn(mode);
        when(jc.foreignKey()).thenReturn(fk);
        return jc;
    }
}
