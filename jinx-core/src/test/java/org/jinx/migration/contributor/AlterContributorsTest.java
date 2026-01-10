package org.jinx.migration.contributor;

import org.jinx.migration.contributor.alter.*;
import org.jinx.migration.contributor.create.PrimaryKeyAddContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SuppressWarnings("ConstantConditions")
class AlterContributorsTest {

    /* ===== helper builders ===== */

    private static ColumnModel col(String name) {
        return ColumnModel.builder().columnName(name).build();
    }

    private static ConstraintModel cons(String name) {
        return ConstraintModel.builder().name(name).build();
    }

    private static IndexModel idx(String name) {
        return IndexModel.builder().indexName(name).build();
    }

    private static RelationshipModel rel(String col) {
        return RelationshipModel.builder().columns(List.of(col)).build();
    }

    private static TableGeneratorModel tg(String table) {
        return TableGeneratorModel.builder().table(table).build();
    }


    @Nested
    @DisplayName("ColumnModifyContributor")
    class ColumnModifyContributorTest {

        @Test
        void priority_and_delegate() {
            ColumnModel n = col("age"); ColumnModel o = col("age");
            ColumnModifyContributor c = new ColumnModifyContributor("member", n, o);

            assertThat(c.priority()).isEqualTo(50);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getModifyColumnSql("member", n, o)).thenReturn("--MOD COL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getModifyColumnSql("member", n, o);
            assertThat(sb).hasToString("--MOD COL--");
        }
    }

    @Nested
    @DisplayName("ColumnRenameContributor")
    class ColumnRenameContributorTest {

        @Test
        void priority_and_delegate() {
            ColumnModel n = col("login"); ColumnModel o = col("username");
            ColumnRenameContributor c = new ColumnRenameContributor("member", n, o);

            assertThat(c.priority()).isEqualTo(50);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getRenameColumnSql("member", n, o)).thenReturn("--REN COL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getRenameColumnSql("member", n, o);
            assertThat(sb).hasToString("--REN COL--");
        }
    }

    @Nested
    @DisplayName("ConstraintModifyContributor")
    @Deprecated // No longer used in MySqlMigrationVisitor (replaced with Drop + Add pattern)
    class ConstraintModifyContributorTest {

        @Test
        void priority_and_delegate() {
            ConstraintModel n = cons("ck_age"); ConstraintModel o = cons("ck_age_old");
            ConstraintModifyContributor c = new ConstraintModifyContributor("member", n, o);

            assertThat(c.priority()).isEqualTo(30);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getModifyConstraintSql("member", n, o)).thenReturn("--MOD CONS--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getModifyConstraintSql("member", n, o);
            assertThat(sb).hasToString("--MOD CONS--");
        }
    }

    @Nested
    @DisplayName("IndexModifyContributor")
    @Deprecated // No longer used in MySqlMigrationVisitor (replaced with Drop + Add pattern)
    class IndexModifyContributorTest {

        @Test
        void priority_and_delegate() {
            IndexModel n = idx("idx_email_new"); IndexModel o = idx("idx_email");
            IndexModifyContributor c = new IndexModifyContributor("member", n, o);

            assertThat(c.priority()).isEqualTo(30);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getModifyIndexSql("member", n, o)).thenReturn("--MOD IDX--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getModifyIndexSql("member", n, o);
            assertThat(sb).hasToString("--MOD IDX--");
        }
    }

    @Nested
    @DisplayName("PrimaryKeyAddContributor")
    class PrimaryKeyAddContributorTest {

        @Test
        void priority_and_delegate() {
            List<String> pk = List.of("id", "tenant_id");
            PrimaryKeyAddContributor c = new PrimaryKeyAddContributor("member", pk);

            assertThat(c.priority()).isEqualTo(90);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getAddPrimaryKeySql("member", pk)).thenReturn("--ADD PK--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getAddPrimaryKeySql("member", pk);
            assertThat(sb).hasToString("--ADD PK--");
        }
    }

    @Nested
    @DisplayName("RelationshipModifyContributor")
    @Deprecated // No longer used in MySqlMigrationVisitor (replaced with Drop + Add pattern)
    class RelationshipModifyContributorTest {

        @Test
        void priority_and_delegate() {
            RelationshipModel n = rel("order_id"); RelationshipModel o = rel("order_id");
            RelationshipModifyContributor c = new RelationshipModifyContributor("order_item", n, o);

            assertThat(c.priority()).isEqualTo(30);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getModifyRelationshipSql("order_item", n, o)).thenReturn("--MOD REL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getModifyRelationshipSql("order_item", n, o);
            assertThat(sb).hasToString("--MOD REL--");
        }
    }

    @Nested
    @DisplayName("TableGeneratorModifyContributor")
    class TableGeneratorModifyContributorTest {

        @Test
        void priority_and_delegate() {
            TableGeneratorModel n = tg("seq_tbl"); TableGeneratorModel o = tg("seq_tbl");
            TableGeneratorModifyContributor c = new TableGeneratorModifyContributor(n, o);

            assertThat(c.priority()).isEqualTo(15);

            TableGeneratorDialect d = mock(TableGeneratorDialect.class);
            when(d.getAlterTableGeneratorSql(n, o)).thenReturn("--ALT TG--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getAlterTableGeneratorSql(n, o);
            assertThat(sb).hasToString("--ALT TG--");
        }
    }

    @Nested
    @DisplayName("TableRenameContributor")
    class TableRenameContributorTest {

        @Test
        void priority_and_delegate() {
            TableRenameContributor c = new TableRenameContributor("member", "app_member");

            assertThat(c.priority()).isEqualTo(10);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getRenameTableSql("member", "app_member")).thenReturn("--REN TBL--");

            StringBuilder sb = new StringBuilder();
            c.contribute(sb, d);

            verify(d).getRenameTableSql("member", "app_member");
            assertThat(sb).hasToString("--REN TBL--");
        }
    }
}
