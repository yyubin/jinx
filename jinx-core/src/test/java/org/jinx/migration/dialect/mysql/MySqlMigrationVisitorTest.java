//package org.jinx.migration.dialect.mysql;
//
//import org.jinx.descriptor.*;
//import org.jinx.model.*;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//
//import java.util.Collections;
//import java.util.Map;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.BDDMockito.*;
//import static org.mockito.Mockito.mock;
//
//@DisplayName("MySqlMigrationVisitor")
//class MySqlMigrationVisitorTest {
//
//    private Dialect dialect;
//    private MySqlMigrationVisitor visitor;
//    private static final String TBL = "users";
//
//    @BeforeEach
//    void setUp() {
//        dialect = mock(Dialect.class);
//
//        // 모든 Dialect 메서드가 "토큰" 형식의 SQL을 반환하도록 간단히 stub
//        // Column
//        given(dialect.getAddColumnSql(any(), any())).willAnswer(i -> "ADD-COL-" + i.getArgument(1, ColumnModel.class).getColumnName() + "\n");
//        given(dialect.getDropColumnSql(any(), any())).willAnswer(i -> "DROP-COL-" + i.getArgument(1, ColumnModel.class).getColumnName() + "\n");
//        given(dialect.getModifyColumnSql(any(), any(), any())).willAnswer(i -> "MODIFY-COL-" + i.getArgument(1, ColumnModel.class).getColumnName() + "\n");
//        given(dialect.getRenameColumnSql(any(), any(), any())).willAnswer(i -> "RENAME-COL-" + i.getArgument(2, ColumnModel.class).getColumnName() + "-TO-" + i.getArgument(1, ColumnModel.class).getColumnName() + "\n");
//        // PK
//        given(dialect.getDropPrimaryKeySql(anyString(), anyCollection())).willReturn("DROP-PK\n");
//        given(dialect.getAddPrimaryKeySql(anyString(), anyList())).willReturn("ADD-PK\n");
//        // Table
//        given(dialect.getRenameTableSql(anyString(), anyString())).willAnswer(i -> "RENAME-TABLE-" + i.getArgument(0) + "-TO-" + i.getArgument(1) + "\n");
//        // Index
//        given(dialect.indexStatement(any(), any())).willAnswer(i -> "ADD-INDEX-" + i.getArgument(0, IndexModel.class).getIndexName() + "\n");
//        given(dialect.getDropIndexSql(any(), any())).willAnswer(i -> "DROP-INDEX-" + i.getArgument(1, IndexModel.class).getIndexName() + "\n");
//        given(dialect.getModifyIndexSql(any(), any(), any())).willAnswer(i -> "MODIFY-INDEX-" + i.getArgument(1, IndexModel.class).getIndexName() + "\n");
//        // Constraint
//        given(dialect.getAddConstraintSql(any(), any())).willAnswer(i -> "ADD-CONSTRAINT-" + i.getArgument(1, ConstraintModel.class).getName() + "\n");
//        given(dialect.getDropConstraintSql(any(), any())).willAnswer(i -> "DROP-CONSTRAINT-" + i.getArgument(1, ConstraintModel.class).getName() + "\n");
//        given(dialect.getModifyConstraintSql(any(), any(), any())).willAnswer(i -> "MODIFY-CONSTRAINT-" + i.getArgument(1, ConstraintModel.class).getName() + "\n");
//        // Relationship
//        given(dialect.getAddRelationshipSql(any(), any())).willAnswer(i -> "ADD-REL-" + i.getArgument(1, RelationshipModel.class).getConstraintName() + "\n");
//        given(dialect.getDropRelationshipSql(any(), any())).willAnswer(i -> "DROP-REL-" + i.getArgument(1, RelationshipModel.class).getConstraintName() + "\n");
//        given(dialect.getModifyRelationshipSql(any(), any(), any())).willAnswer(i -> "MODIFY-REL-" + i.getArgument(1, RelationshipModel.class).getConstraintName() + "\n");
//        // TableGenerator - Dialect에 관련 메서드가 없으므로 Contributor만 테스트
//
//        // visitor 는 table 이름을 알아야 하므로 최소 구성의 ModifiedEntity 를 만들어 전달
//        EntityModel dummy = EntityModel.builder()
//                .entityName("User")
//                .tableName(TBL)
//                .columns(Map.of())
//                .build();
//        DiffResult.ModifiedEntity diff =
//                DiffResult.ModifiedEntity.builder().newEntity(dummy).oldEntity(dummy).build();
//
//        visitor = new MySqlMigrationVisitor(diff, dialect);
//    }
//
//    @Nested
//    @DisplayName("생성자 및 초기화")
//    class ConstructorAndInitialization {
//        @Test
//        @DisplayName("diff가 null일 때 예외 없이 생성되어야 함")
//        void constructorWithNullDiff() {
//            MySqlMigrationVisitor nullVisitor = new MySqlMigrationVisitor(null, dialect);
//            assertThat(nullVisitor.getGeneratedSql()).isEmpty();
//        }
//
//        @Test
//        @DisplayName("방문한 변경점이 없으면 getGeneratedSql은 빈 문자열 반환")
//        void getGeneratedSqlReturnsEmptyForNoChanges() {
//            assertThat(visitor.getGeneratedSql()).isEmpty();
//        }
//    }
//
//    @Nested @DisplayName("컬럼 작업")
//    class ColumnOperations {
//
//        @Test @DisplayName("visitAddedColumn → ADD-COL 토큰 포함")
//        void addsAddColumnContributor() {
//            visitor.visitAddedColumn(ColumnModel.builder().columnName("email").build());
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("ADD-COL-email");
//        }
//
//        @Test @DisplayName("visitDroppedColumn → DROP-COL 토큰 포함")
//        void addsDropColumnContributor() {
//            visitor.visitDroppedColumn(ColumnModel.builder().columnName("email").build());
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("DROP-COL-email");
//        }
//
//        @Test @DisplayName("PK 컬럼 수정 시 PK 재생성 (DROP → MODIFY → ADD 순)")
//        void pkColumnModifyRecreatesPk() {
//            ColumnModel oldPk = ColumnModel.builder().columnName("id").javaType("bigint").isPrimaryKey(true).build();
//            ColumnModel newPk = ColumnModel.builder().columnName("id").javaType("int").isPrimaryKey(true).build();
//            visitor.visitModifiedColumn(newPk, oldPk);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).containsSubsequence("DROP-PK", "MODIFY-COL-id", "ADD-PK");
//        }
//
//        @Test @DisplayName("일반 컬럼 수정 시 PK 재생성 없이 MODIFY만 수행")
//        void nonPkColumnModify() {
//            ColumnModel oldCol = ColumnModel.builder().columnName("name").isPrimaryKey(false).build();
//            ColumnModel newCol = ColumnModel.builder().columnName("name").isPrimaryKey(false).length(500).build();
//            visitor.visitModifiedColumn(newCol, oldCol);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("MODIFY-COL-name");
//            assertThat(sql).doesNotContain("DROP-PK", "ADD-PK");
//        }
//
//        @Test @DisplayName("PK 컬럼 이름 변경 시 DROP_PK → RENAME → ADD_PK 순")
//        void pkRenameGeneratesThreeSteps() {
//            ColumnModel oldPk = ColumnModel.builder().columnName("id").isPrimaryKey(true).build();
//            ColumnModel newPk = ColumnModel.builder().columnName("user_id").isPrimaryKey(true).build();
//            visitor.visitRenamedColumn(newPk, oldPk);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).containsSubsequence("DROP-PK", "RENAME-COL-id-TO-user_id", "ADD-PK");
//        }
//
//        @Test @DisplayName("일반 컬럼 이름 변경 시 RENAME만 수행")
//        void nonPkRenameGeneratesOneStep() {
//            ColumnModel oldCol = ColumnModel.builder().columnName("name").isPrimaryKey(false).build();
//            ColumnModel newCol = ColumnModel.builder().columnName("user_name").isPrimaryKey(false).build();
//            visitor.visitRenamedColumn(newCol, oldCol);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("RENAME-COL-name-TO-user_name");
//            assertThat(sql).doesNotContain("DROP-PK", "ADD-PK");
//        }
//    }
//
//    @Nested @DisplayName("테이블 이름 변경")
//    class TableRename {
//        @Test @DisplayName("visitRenamedTable이 RENAME-TABLE 토큰을 만든다")
//        void tableRename() {
//            EntityModel oldE = EntityModel.builder().entityName("Old").tableName("old_tbl").build();
//            EntityModel newE = EntityModel.builder().entityName("New").tableName("new_tbl").build();
//            visitor.visitRenamedTable(DiffResult.RenamedTable.builder().oldEntity(oldE).newEntity(newE).build());
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("RENAME-TABLE-old_tbl-TO-new_tbl");
//            then(dialect).should().getRenameTableSql("old_tbl", "new_tbl");
//        }
//    }
//
//    @Nested @DisplayName("인덱스, 제약조건, 관계 등 기타 작업")
//    class OtherOperations {
//        @Test @DisplayName("Index Add/Drop/Modify")
//        void visitIndexOperations() {
//            visitor.visitAddedIndex(IndexModel.builder().indexName("idx1").build());
//            visitor.visitDroppedIndex(IndexModel.builder().indexName("idx2").build());
//            visitor.visitModifiedIndex(IndexModel.builder().indexName("idx3").build(), null);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("ADD-INDEX-idx1", "DROP-INDEX-idx2", "MODIFY-INDEX-idx3");
//        }
//
//        @Test @DisplayName("Constraint Add/Drop/Modify")
//        void visitConstraintOperations() {
//            visitor.visitAddedConstraint(ConstraintModel.builder().name("c1").build());
//            visitor.visitDroppedConstraint(ConstraintModel.builder().name("c2").build());
//            visitor.visitModifiedConstraint(ConstraintModel.builder().name("c3").build(), null);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("ADD-CONSTRAINT-c1", "DROP-CONSTRAINT-c2", "MODIFY-CONSTRAINT-c3");
//        }
//
//        @Test @DisplayName("Relationship Add/Drop/Modify")
//        void visitRelationshipOperations() {
//            visitor.visitAddedRelationship(RelationshipModel.builder().constraintName("r1").build());
//            visitor.visitDroppedRelationship(RelationshipModel.builder().constraintName("r2").build());
//            visitor.visitModifiedRelationship(RelationshipModel.builder().constraintName("r3").build(), null);
//            String sql = visitor.getGeneratedSql();
//            assertThat(sql).contains("ADD-REL-r1", "DROP-REL-r2", "MODIFY-REL-r3");
//        }
//
//        @Test @DisplayName("TableGenerator Add/Drop/Modify")
//        void visitTableGeneratorOperations() {
//            // 이 메서드들은 Contributor를 추가하는지만 확인 (Dialect 메서드 없음)
//            visitor.visitAddedTableGenerator(null);
//            visitor.visitDroppedTableGenerator(null);
//            visitor.visitModifiedTableGenerator(null, null);
//            assertThat(visitor.getAlterBuilder().getUnits()).hasSize(3);
//        }
//
//        @Test @DisplayName("PrimaryKey Add/Drop/Modify")
//        void visitPrimaryKeyOperations() {
//            visitor.visitAddedPrimaryKey(Collections.emptyList());
//            assertThat(visitor.getGeneratedSql()).contains("ADD-PK");
//
//            visitor.visitDroppedPrimaryKey();
//            assertThat(visitor.getGeneratedSql()).contains("DROP-PK");
//
//            visitor.visitModifiedPrimaryKey(null, null);
//            assertThat(visitor.getGeneratedSql()).containsSubsequence("DROP-PK", "ADD-PK");
//        }
//    }
//}
