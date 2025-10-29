package org.jinx.migration.contributor;

import org.jinx.migration.contributor.create.*;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("ConstantConditions")
class CreateContributorsTest {

    private static ColumnModel col(String name) {
        return ColumnModel.builder().columnName(name).javaType("String").build();
    }

    private static ConstraintModel cons(String name) {
        return ConstraintModel.builder().name(name).type(ConstraintType.CHECK).columns(List.of("age")).build();
    }

    private static IndexModel idx(String name) {
        return IndexModel.builder().indexName(name).columnNames(List.of("email")).build();
    }

    private static RelationshipModel rel(String column) {
        return RelationshipModel.builder().columns(List.of(column)).type(RelationshipType.MANY_TO_ONE).referencedTable("member").build();
    }

    private static TableGeneratorModel tg(String table) {
        return TableGeneratorModel.builder().table(table).name("seq_gen").build();
    }


    @Nested @DisplayName("ColumnAddContributor")
    class ColumnAddContributorTest {
        @Test void priority_and_delegate() {
            ColumnModel c = col("nickname");
            ColumnAddContributor contrib = new ColumnAddContributor("member", c);

            assertThat(contrib.priority()).isEqualTo(40);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getAddColumnSql("member", c)).thenReturn("--ADD COL--");

            StringBuilder sb = new StringBuilder();
            contrib.contribute(sb, d);

            verify(d).getAddColumnSql("member", c);
            assertThat(sb).hasToString("--ADD COL--");
        }
    }

    @Nested @DisplayName("ColumnContributor")
    class ColumnContributorTest {
        @Test void builds_column_and_pk_section() {
            ColumnModel id  = col("id");
            ColumnModel name = col("name");
            ColumnContributor contrib = new ColumnContributor(List.of("id"), List.of(id, name));

            DdlDialect d   = mock(DdlDialect.class);
            when(d.getColumnDefinitionSql(id)).thenReturn("id BIGINT");
            when(d.getColumnDefinitionSql(name)).thenReturn("name VARCHAR(255)");
            when(d.getPrimaryKeyDefinitionSql(List.of("id"))).thenReturn("PRIMARY KEY (id)");

            StringBuilder sb = new StringBuilder();
            contrib.contribute(sb, d);

            String expected = """
                    \
                      id BIGINT,
                      name VARCHAR(255),
                      PRIMARY KEY (id),
                    """;
            assertThat(sb.toString()).isEqualToNormalizingNewlines(expected);
        }
    }

    @Nested @DisplayName("ConstraintAddContributor")
    class ConstraintAddContributorTest {
        @Test void priority_and_delegate() {
            ConstraintModel ck = cons("ck_age");
            ConstraintAddContributor contrib = new ConstraintAddContributor("member", ck);

            assertThat(contrib.priority()).isEqualTo(60);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getAddConstraintSql("member", ck)).thenReturn("--ADD CONS--");

            StringBuilder sb = new StringBuilder();
            contrib.contribute(sb, d);

            verify(d).getAddConstraintSql("member", ck);
            assertThat(sb).hasToString("--ADD CONS--");
        }
    }

    @Nested @DisplayName("ConstraintContributor")
    class ConstraintContributorTest {
        @Test void builds_constraints_section() {
            ConstraintModel ck = cons("ck_positive");
            ConstraintContributor cc = new ConstraintContributor(List.of(ck));

            DdlDialect d = mock(DdlDialect.class);
            when(d.getConstraintDefinitionSql(ck)).thenReturn("CONSTRAINT ck_positive CHECK (age > 0)");

            StringBuilder sb = new StringBuilder();
            cc.contribute(sb, d);

            String expected = "  CONSTRAINT ck_positive CHECK (age > 0),\n";
            assertThat(sb).hasToString(expected);
        }

        @Test @DisplayName("name 이 비면 IllegalStateException")
        void empty_name_should_throw() {
            ConstraintModel invalid = cons("");
            ConstraintContributor cc = new ConstraintContributor(List.of(invalid));

            assertThatThrownBy(() -> cc.contribute(new StringBuilder(), mock(DdlDialect.class)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested @DisplayName("IndexContributor")
    class IndexContributorTest {
        @Test void priority_and_delegate_for_each_index() {
            IndexModel i1 = idx("idx_email");
            IndexContributor ic = new IndexContributor("member", List.of(i1));

            assertThat(ic.priority()).isEqualTo(60);

            DdlDialect d = mock(DdlDialect.class);
            when(d.indexStatement(i1, "member")).thenReturn("--CREATE IDX--");

            StringBuilder sb = new StringBuilder();
            ic.contribute(sb, d);

            verify(d).indexStatement(i1, "member");
            assertThat(sb).hasToString("--CREATE IDX--");
        }
    }

    @Nested @DisplayName("IndexAddContributor")
    class IndexAddContributorTest {
        @Test void priority_and_delegate() {
            IndexModel i1 = idx("idx_name");
            IndexAddContributor ic = new IndexAddContributor("member", i1);

            assertThat(ic.priority()).isEqualTo(60);

            DdlDialect d = mock(DdlDialect.class);
            when(d.indexStatement(i1, "member")).thenReturn("--CREATE IDX2--");

            StringBuilder sb = new StringBuilder();
            ic.contribute(sb, d);

            verify(d).indexStatement(i1, "member");
            assertThat(sb).hasToString("--CREATE IDX2--");
        }
    }

    @Nested @DisplayName("RelationshipAddContributor")
    class RelationshipAddContributorTest {
        @Test void priority_and_delegate() {
            RelationshipModel r = rel("member_id");
            RelationshipAddContributor rc = new RelationshipAddContributor("order", r);

            assertThat(rc.priority()).isEqualTo(60);

            DdlDialect d = mock(DdlDialect.class);
            when(d.getAddRelationshipSql("order", r)).thenReturn("--ADD REL--");

            StringBuilder sb = new StringBuilder();
            rc.contribute(sb, d);

            verify(d).getAddRelationshipSql("order", r);
            assertThat(sb).hasToString("--ADD REL--");
        }
    }

    @Nested @DisplayName("TableGeneratorAddContributor")
    class TableGeneratorAddContributorTest {
        @Test void priority_and_delegate() {
            TableGeneratorModel gen = tg("seq_tbl");
            TableGeneratorAddContributor tc = new TableGeneratorAddContributor(gen);

            assertThat(tc.priority()).isEqualTo(5);

            TableGeneratorDialect d = mock(TableGeneratorDialect.class);
            when(d.getCreateTableGeneratorSql(gen)).thenReturn("--CRT TG--");

            StringBuilder sb = new StringBuilder();
            tc.contribute(sb, d);

            verify(d).getCreateTableGeneratorSql(gen);
            assertThat(sb).hasToString("--CRT TG--");
        }
    }
}
