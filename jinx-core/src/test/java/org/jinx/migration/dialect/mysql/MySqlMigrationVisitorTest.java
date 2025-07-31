package org.jinx.migration.dialect.mysql;

import org.jinx.migration.*;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;

@DisplayName("MySqlMigrationVisitor")
class MySqlMigrationVisitorTest {

    private Dialect dialect;
    private MySqlMigrationVisitor visitor;
    private static final String TBL = "users";

    @BeforeEach
    void setUp() {
        dialect = mock(Dialect.class);

        // 모든 Dialect 메서드가 “토큰” 형식의 SQL을 반환하도록 간단히 stub
        given(dialect.getAddColumnSql(any(), any()))
                .willAnswer(i -> "ADD-" + i.getArgument(1, ColumnModel.class).getColumnName());

        given(dialect.getDropPrimaryKeySql(anyString(), anyCollection()))
                .willReturn("DROP_PK");
        given(dialect.getAddPrimaryKeySql(anyString(), anyList()))
                .willReturn("ADD_PK");
        given(dialect.getModifyColumnSql(any(), any(), any()))
                .willAnswer(i -> "MODIFY-" + i.getArgument(1, ColumnModel.class).getColumnName());
        given(dialect.getRenameColumnSql(any(), any(), any()))
                .willAnswer(i -> "RENAME-" + i.getArgument(2, ColumnModel.class).getColumnName()
                        + "-TO-" + i.getArgument(1, ColumnModel.class).getColumnName());
        given(dialect.getRenameTableSql(anyString(), anyString()))
                .willAnswer(i -> "RENAME‑TABLE‑" + i.getArgument(0) + "‑TO‑" + i.getArgument(1));

        // visitor 는 table 이름을 알아야 하므로 최소 구성의 ModifiedEntity 를 만들어 전달
        EntityModel dummy = EntityModel.builder()
                .entityName("User")
                .tableName(TBL)
                .columns(Map.of())        // 내용은 이번 테스트에서 쓰이지 않음
                .build();
        DiffResult.ModifiedEntity diff =
                DiffResult.ModifiedEntity.builder().newEntity(dummy).oldEntity(dummy).build();

        visitor = new MySqlMigrationVisitor(diff, dialect);
    }

    @Nested @DisplayName("컬럼 추가/수정/이름변경")
    class ColumnOperations {

        @Test @DisplayName("visitAddedColumn → ADD 토큰 포함")
        void addsAddColumnContributor() {
            visitor.visitAddedColumn(ColumnModel.builder().columnName("email").build());

            String sql = visitor.getGeneratedSql();
            assertThat(sql).contains("ADD-email");
            then(dialect).should().getAddColumnSql(eq(TBL),
                    argThat(c -> "email".equals(c.getColumnName())));
        }

        @Test @DisplayName("PK 컬럼 수정 시 PK 재생성 DROP → MODIFY → ADD 순")
        void pkColumnModifyRecreatesPk() {
            ColumnModel oldPk = ColumnModel.builder()
                    .columnName("id").javaType("bigint")
                    .isPrimaryKey(true).build();
            ColumnModel newPk = ColumnModel.builder()
                    .columnName("id").javaType("int")  // 타입만 바뀜
                    .isPrimaryKey(true).build();

            visitor.visitModifiedColumn(newPk, oldPk);

            String sql = visitor.getGeneratedSql();
            assertThat(sql).containsSubsequence("DROP_PK", "MODIFY-id", "ADD_PK");
        }

        @Test @DisplayName("PK 컬럼 이름 변경 시 DROP_PK → RENAME → ADD_PK 순")
        void pkRenameGeneratesThreeSteps() {
            ColumnModel oldPk = ColumnModel.builder().columnName("id")
                    .isPrimaryKey(true).build();
            ColumnModel newPk = ColumnModel.builder().columnName("user_id")
                    .isPrimaryKey(true).build();

            visitor.visitRenamedColumn(newPk, oldPk);

            String sql = visitor.getGeneratedSql();
            assertThat(sql).containsSubsequence("DROP_PK",
                    "RENAME-id-TO-user_id",
                    "ADD_PK");
        }
    }

    @Nested @DisplayName("테이블 이름 변경")
    class TableRename {

        @Test @DisplayName("visitRenamedTable 이 RENAME‑TABLE 토큰을 만든다")
        void tableRename() {
            EntityModel oldE = EntityModel.builder().entityName("Old").tableName("old_tbl").build();
            EntityModel newE = EntityModel.builder().entityName("New").tableName("new_tbl").build();
            visitor.visitRenamedTable(DiffResult.RenamedTable.builder()
                    .oldEntity(oldE).newEntity(newE).build());

            String sql = visitor.getGeneratedSql();
            assertThat(sql).contains("RENAME‑TABLE‑old_tbl‑TO‑new_tbl");
            then(dialect).should().getRenameTableSql("old_tbl", "new_tbl");
        }
    }
}
