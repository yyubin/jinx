package org.jinx.handler.relationship;

import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.naming.DefaultNaming;
import org.jinx.naming.Naming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RelationshipJoinSupportTest {

    ProcessingContext context;
    RelationshipSupport support;
    Messager messager;
    Naming naming;

    RelationshipJoinSupport joinSupport;

    AttributeDescriptor attr;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class, RETURNS_DEEP_STUBS);
        support = mock(RelationshipSupport.class);
        messager = mock(Messager.class);
        naming = new DefaultNaming(64);
        attr = mock(AttributeDescriptor.class);

        when(context.getNaming()).thenReturn(naming);
        when(context.getMessager()).thenReturn(messager);
        when(attr.elementForDiagnostics()).thenReturn(null);

        joinSupport = new RelationshipJoinSupport(context, support);
    }

    private EntityModel newEntity(String name, String table) {
        return EntityModel.builder()
                .entityName(name)
                .tableName(table)
                .build();
    }

    private EntityModel newJoinTableEntity(String jt) {
        return EntityModel.builder()
                .entityName(jt)
                .tableName(jt)
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();
    }

    @Test
    void ensureJoinTableRelationships_adds_owner_and_target_FKs_and_indexes() {
        // given
        String jtName = "member_role";
        String ownerTable = "member";
        String targetTable = "role";

        var joinTable = newJoinTableEntity(jtName);
        var ownerEntity = newEntity("Member", ownerTable);
        var targetEntity = newEntity("Role", targetTable);

        // FK 매핑(입력 순서 보존)
        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("member_id", "id");

        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("role_id", "id");

        var details = mock(JoinTableDetails.class);
        when(details.joinTableName()).thenReturn(jtName);
        when(details.ownerEntity()).thenReturn(ownerEntity);
        when(details.referencedEntity()).thenReturn(targetEntity);
        when(details.ownerFkToPkMap()).thenReturn(ownerFkToPk);
        when(details.inverseFkToPkMap()).thenReturn(targetFkToPk);
        when(details.ownerNoConstraint()).thenReturn(false);
        when(details.inverseNoConstraint()).thenReturn(false);
        when(details.ownerFkConstraintName()).thenReturn(null);
        when(details.inverseFkConstraintName()).thenReturn(null);

        // when
        joinSupport.ensureJoinTableRelationships(joinTable, details);

        // then
        // 관계 두 개 생성
        assertThat(joinTable.getRelationships()).hasSize(2);

        String expectedOwnerFkName = naming.fkName(
                jtName, List.of("member_id"), ownerTable, List.of("id"));
        String expectedTargetFkName = naming.fkName(
                jtName, List.of("role_id"), targetTable, List.of("id"));

        RelationshipModel ownerRel = joinTable.getRelationships().get(expectedOwnerFkName);
        RelationshipModel targetRel = joinTable.getRelationships().get(expectedTargetFkName);

        assertThat(ownerRel).isNotNull();
        assertThat(ownerRel.getTableName()).isEqualTo(jtName);
        assertThat(ownerRel.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
        assertThat(ownerRel.getColumns()).containsExactly("member_id");
        assertThat(ownerRel.getReferencedTable()).isEqualTo(ownerTable);
        assertThat(ownerRel.getReferencedColumns()).containsExactly("id");

        assertThat(targetRel).isNotNull();
        assertThat(targetRel.getColumns()).containsExactly("role_id");
        assertThat(targetRel.getReferencedTable()).isEqualTo(targetTable);
        assertThat(targetRel.getReferencedColumns()).containsExactly("id");

        // 인덱스 자동 생성 호출 확인
        verify(support).addForeignKeyIndex(eq(joinTable), eq(List.of("member_id")), eq(jtName));
        verify(support).addForeignKeyIndex(eq(joinTable), eq(List.of("role_id")), eq(jtName));
    }

    @Test
    void addOneToManyJoinTableUnique_adds_unique_on_target_fks_with_order_preserved() {
        // given
        String jtName = "a_b";
        EntityModel jt = newJoinTableEntity(jtName);

        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("b_id", "id");
        targetFkToPk.put("b_code", "code"); // 순서 보존

        // when
        joinSupport.addOneToManyJoinTableUnique(jt, targetFkToPk);

        // then
        assertThat(jt.getConstraints()).hasSize(1);
        ConstraintModel uc = jt.getConstraints().values().iterator().next();
        assertThat(uc.getType()).isEqualTo(ConstraintType.UNIQUE);
        assertThat(uc.getTableName()).isEqualTo(jtName);
        assertThat(uc.getColumns()).containsExactly("b_id", "b_code"); // 입력 순서 유지
        assertThat(uc.getName()).isEqualTo(naming.uqName(jtName, uc.getColumns()));
    }

    @Test
    void addJoinTableUniqueConstraints_valid_and_named_and_generated() {
        // given
        String jtName = "post_tag";
        EntityModel jt = newJoinTableEntity(jtName);
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("post_id").javaType("Long").isNullable(false).build());
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("tag_id").javaType("Long").isNullable(false).build());
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("tenant_id").javaType("Long").isNullable(false).build());

        UniqueConstraint ucNamed = mock(UniqueConstraint.class);
        when(ucNamed.name()).thenReturn("uc_post_tag_named");
        when(ucNamed.columnNames()).thenReturn(new String[]{"post_id", "tag_id"});

        UniqueConstraint ucGenerated = mock(UniqueConstraint.class);
        when(ucGenerated.name()).thenReturn(""); // 이름 비워서 네이밍 전략 사용
        when(ucGenerated.columnNames()).thenReturn(new String[]{"tenant_id"});

        UniqueConstraint[] arr = new UniqueConstraint[]{ucNamed, ucGenerated};

        // when
        joinSupport.addJoinTableUniqueConstraints(jt, arr, attr);

        // then: 제약 생성 검증
        assertThat(jt.getConstraints()).hasSize(2);
        assertThat(jt.getConstraints()).containsKey("uc_post_tag_named");
        String generated = naming.uqName(jtName, List.of("tenant_id"));
        assertThat(jt.getConstraints()).containsKey(generated);

        // messager NOTE 로그 2건 발생 검증
        @SuppressWarnings("unchecked")
        var noteCaptor = org.mockito.ArgumentCaptor.forClass(CharSequence.class);
        verify(messager, times(2)).printMessage(eq(Diagnostic.Kind.NOTE), noteCaptor.capture());
        var notes = noteCaptor.getAllValues();
        assertThat(notes.get(0).toString()).contains("Added unique constraint");
        assertThat(notes.get(1).toString()).contains("Added unique constraint");

        // WARNING/ERROR 는 없어야 함
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.WARNING), anyString(), any());
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), any());
    }


    @Test
    void addJoinTableUniqueConstraints_warn_on_empty_columns_and_error_on_missing_column() {
        // given
        String jtName = "order_item";
        EntityModel jt = newJoinTableEntity(jtName);
        // 실제 존재하는 컬럼은 하나만
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("order_id").javaType("Long").isNullable(false).build());

        UniqueConstraint emptyCols = mock(UniqueConstraint.class);
        when(emptyCols.name()).thenReturn("");
        when(emptyCols.columnNames()).thenReturn(new String[]{}); // 경고

        UniqueConstraint missingCol = mock(UniqueConstraint.class);
        when(missingCol.name()).thenReturn("");
        when(missingCol.columnNames()).thenReturn(new String[]{"order_id", "missing"}); // 에러

        // when
        joinSupport.addJoinTableUniqueConstraints(jt, new UniqueConstraint[]{emptyCols, missingCol}, attr);

        // then
        // 첫 번째는 경고, 두 번째는 에러 후 return (추가 없음)
        verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("UniqueConstraint with empty columnNames"), any());
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("non-existent column 'missing'"), any());

        assertThat(jt.getConstraints()).isEmpty();
    }

    @Test
    void createJoinTableEntity_success_creates_columns_with_not_null_and_types() {
        // given
        String jtName = "user_role";
        var ownerEntity = newEntity("User", "users");
        var refEntity = newEntity("Role", "roles");

        // owner/ref PK 스키마
        List<ColumnModel> ownerPks = List.of(
                ColumnModel.builder().tableName("users").columnName("id").javaType("Long").isPrimaryKey(true).build());
        List<ColumnModel> refPks = List.of(
                ColumnModel.builder().tableName("roles").columnName("id").javaType("Long").isPrimaryKey(true).build());

        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("user_id", "id");
        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("role_id", "id");

        var details = mock(JoinTableDetails.class);
        when(details.joinTableName()).thenReturn(jtName);
        when(details.ownerEntity()).thenReturn(ownerEntity);
        when(details.referencedEntity()).thenReturn(refEntity);
        when(details.ownerFkToPkMap()).thenReturn(ownerFkToPk);
        when(details.inverseFkToPkMap()).thenReturn(targetFkToPk);

        // when
        var result = joinSupport.createJoinTableEntity(details, ownerPks, refPks);

        // then
        assertThat(result).isPresent();
        EntityModel jt = result.get();
        assertThat(jt.getTableName()).isEqualTo(jtName);
        assertThat(jt.findColumn(jtName, "user_id")).isNotNull();
        assertThat(jt.findColumn(jtName, "role_id")).isNotNull();
        assertThat(jt.findColumn(jtName, "user_id").isNullable()).isFalse();
        assertThat(jt.findColumn(jtName, "user_id").getJavaType()).isEqualTo("Long");
        verifyNoInteractions(messager);
    }

    @Test
    void createJoinTableEntity_error_if_pk_missing() {
        // given
        String jtName = "user_role";
        var ownerEntity = newEntity("User", "users");
        var refEntity = newEntity("Role", "roles");

        // owner/ref PK 스키마 — owner의 id는 없음(에러 유도)
        List<ColumnModel> ownerPks = List.of(
                ColumnModel.builder().tableName("users").columnName("other").javaType("Long").isPrimaryKey(true).build());
        List<ColumnModel> refPks = List.of(
                ColumnModel.builder().tableName("roles").columnName("id").javaType("Long").isPrimaryKey(true).build());

        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("user_id", "id"); // 존재하지 않는 PK
        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("role_id", "id");

        var details = mock(JoinTableDetails.class);
        when(details.joinTableName()).thenReturn(jtName);
        when(details.ownerEntity()).thenReturn(ownerEntity);
        when(details.referencedEntity()).thenReturn(refEntity);
        when(details.ownerFkToPkMap()).thenReturn(ownerFkToPk);
        when(details.inverseFkToPkMap()).thenReturn(targetFkToPk);

        // when
        var result = joinSupport.createJoinTableEntity(details, ownerPks, refPks);

        // then
        assertThat(result).isEmpty();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("Owner primary key 'id' not found"));
    }

    @Test
    void addRelationshipsToJoinTable_adds_two_relationships_and_indexes() {
        // given
        String jtName = "author_book";
        var ownerEntity = newEntity("Author", "author");
        var targetEntity = newEntity("Book", "book");

        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("author_id", "id");
        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("book_id", "id");

        var jt = newJoinTableEntity(jtName);

        var details = mock(JoinTableDetails.class);
        when(details.joinTableName()).thenReturn(jtName);
        when(details.ownerEntity()).thenReturn(ownerEntity);
        when(details.referencedEntity()).thenReturn(targetEntity);
        when(details.ownerFkToPkMap()).thenReturn(ownerFkToPk);
        when(details.inverseFkToPkMap()).thenReturn(targetFkToPk);
        when(details.ownerNoConstraint()).thenReturn(false);
        when(details.inverseNoConstraint()).thenReturn(false);
        when(details.ownerFkConstraintName()).thenReturn(null);
        when(details.inverseFkConstraintName()).thenReturn(null);

        // when
        joinSupport.addRelationshipsToJoinTable(jt, details);

        // then
        assertThat(jt.getRelationships()).hasSize(2);
        String ownerFkName = naming.fkName(jtName, List.of("author_id"), "author", List.of("id"));
        String targetFkName = naming.fkName(jtName, List.of("book_id"), "book", List.of("id"));
        assertThat(jt.getRelationships()).containsKeys(ownerFkName, targetFkName);

        verify(support).addForeignKeyIndex(eq(jt), eq(List.of("author_id")), eq(jtName));
        verify(support).addForeignKeyIndex(eq(jt), eq(List.of("book_id")), eq(jtName));
    }

    @Test
    void ensureJoinTableColumns_creates_missing_and_fixes_nullable_and_reports_type_mismatch() {
        // given
        String jtName = "team_user";
        var jt = newJoinTableEntity(jtName);

        // PK 정의
        var ownerPk = ColumnModel.builder().tableName("team").columnName("id").javaType("Long").isPrimaryKey(true).build();
        var targetPk = ColumnModel.builder().tableName("users").columnName("uid").javaType("UUID").isPrimaryKey(true).build();

        List<ColumnModel> ownerPks = List.of(ownerPk);
        List<ColumnModel> targetPks = List.of(targetPk);

        // FK 매핑
        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("team_id", "id"); // 새로 생성될 컬럼

        Map<String, String> targetFkToPk = new LinkedHashMap<>();
        targetFkToPk.put("user_uid", "uid"); // 이미 존재하지만 nullable=true & 타입 불일치로 에러 유도

        // 기존에 잘못된 정의: 타입 불일치 + nullable
        jt.putColumn(ColumnModel.builder()
                .tableName(jtName).columnName("user_uid").javaType("String").isNullable(true).build());

        // when
        joinSupport.ensureJoinTableColumns(jt, ownerPks, targetPks, ownerFkToPk, targetFkToPk, attr);

        // then
        // team_id 생성됨
        var teamId = jt.findColumn(jtName, "team_id");
        assertThat(teamId).isNotNull();
        assertThat(teamId.getJavaType()).isEqualTo("Long");
        assertThat(teamId.isNullable()).isFalse();

        // user_uid 는 nullable 이 false로 고쳐짐
        var userUid = jt.findColumn(jtName, "user_uid");
        assertThat(userUid).isNotNull();
        assertThat(userUid.isNullable()).isFalse();

        // 타입 불일치 에러 메시지 출력
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("Join table column type mismatch for 'user_uid'"), any());
    }

    @Test
    void validateJoinTableNameConflict_detects_conflicts() {
        // given
        var owner = newEntity("Owner", "owner");
        var target = newEntity("Target", "target");

        // when / then
        assertThat(joinSupport.validateJoinTableNameConflict("owner", owner, target, attr)).isFalse();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("conflicts with owner entity table name"), any());

        assertThat(joinSupport.validateJoinTableNameConflict("target", owner, target, attr)).isFalse();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("conflicts with referenced entity table name"), any());

        assertThat(joinSupport.validateJoinTableNameConflict("owner_target", owner, target, attr)).isTrue();
    }

    @Test
    void validateJoinTableFkConsistency_checks_exact_column_set() {
        // given
        String jtName = "student_course";
        var jt = newJoinTableEntity(jtName);

        // 실제 존재 컬럼
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("student_id").javaType("Long").isNullable(false).build());
        jt.putColumn(ColumnModel.builder().tableName(jtName).columnName("course_id").javaType("Long").isNullable(false).build());

        Map<String, String> ownerFkToPk = new LinkedHashMap<>();
        ownerFkToPk.put("student_id", "id");
        Map<String, String> inverseFkToPk = new LinkedHashMap<>();
        inverseFkToPk.put("course_id", "id");

        var detailsOk = mock(JoinTableDetails.class);
        when(detailsOk.joinTableName()).thenReturn(jtName);
        when(detailsOk.ownerFkToPkMap()).thenReturn(ownerFkToPk);
        when(detailsOk.inverseFkToPkMap()).thenReturn(inverseFkToPk);

        // OK 케이스
        assertThat(joinSupport.validateJoinTableFkConsistency(jt, detailsOk, attr)).isTrue();

        // 누락/초과 유도 케이스
        var detailsBad = mock(JoinTableDetails.class);
        when(detailsBad.joinTableName()).thenReturn(jtName);
        when(detailsBad.ownerFkToPkMap()).thenReturn(Map.of("student_id", "id"));
        when(detailsBad.inverseFkToPkMap()).thenReturn(Map.of("extra_col", "id")); // 존재하지 않는 FK 요구

        assertThat(joinSupport.validateJoinTableFkConsistency(jt, detailsBad, attr)).isFalse();
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("FK column set mismatch"), any());
    }
}
