package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.TableGeneratorBuilder;
import org.jinx.migration.contributor.TableGeneratorContributor;
import org.jinx.migration.contributor.alter.TableGeneratorModifyContributor;
import org.jinx.migration.contributor.create.TableGeneratorAddContributor;
import org.jinx.migration.contributor.drop.TableGeneratorDropContributor;
import org.jinx.migration.spi.dialect.TableGeneratorDialect;
import org.jinx.model.TableGeneratorModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSqlTableGeneratorVisitorTest {

    static class RecordingBuilder extends TableGeneratorBuilder {
        final List<Class<?>> added = new ArrayList<>();

        RecordingBuilder(TableGeneratorDialect dialect) { super(dialect); }

        @Override
        public TableGeneratorBuilder add(TableGeneratorContributor unit) {
            added.add(unit.getClass());
            return this;
        }

        @Override
        public String build() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < added.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(added.get(i).getSimpleName());
            }
            return sb.toString();
        }
    }

    private static void injectBuilder(PostgreSqlTableGeneratorVisitor v, TableGeneratorBuilder b) throws Exception {
        Field f = PostgreSqlTableGeneratorVisitor.class.getDeclaredField("builder");
        f.setAccessible(true);
        f.set(v, b);
    }

    @Test @DisplayName("visitAdded/Dropped/Modified → 올바른 Contributor 순서 추가")
    void addsExpectedContributors() throws Exception {
        TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
        PostgreSqlTableGeneratorVisitor v = new PostgreSqlTableGeneratorVisitor(tgDialect);

        RecordingBuilder rec = new RecordingBuilder(tgDialect);
        injectBuilder(v, rec);

        TableGeneratorModel tg = mock(TableGeneratorModel.class);
        TableGeneratorModel tgOld = mock(TableGeneratorModel.class);

        v.visitAddedTableGenerator(tg);
        v.visitDroppedTableGenerator(tg);
        v.visitModifiedTableGenerator(tg, tgOld);

        assertEquals(3, rec.added.size());
        assertEquals(TableGeneratorAddContributor.class,    rec.added.get(0));
        assertEquals(TableGeneratorDropContributor.class,   rec.added.get(1));
        assertEquals(TableGeneratorModifyContributor.class, rec.added.get(2));

        assertEquals(String.join("\n",
                "TableGeneratorAddContributor",
                "TableGeneratorDropContributor",
                "TableGeneratorModifyContributor"),
                v.getGeneratedSql());
    }

    @Test @DisplayName("visit 없으면 getGeneratedSql은 빈 문자열")
    void noVisit_emptyResult() throws Exception {
        TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
        PostgreSqlTableGeneratorVisitor v = new PostgreSqlTableGeneratorVisitor(tgDialect);
        RecordingBuilder rec = new RecordingBuilder(tgDialect);
        injectBuilder(v, rec);

        assertEquals("", v.getGeneratedSql());
    }
}
