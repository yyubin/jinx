package org.jinx.migration.dialect.mysql;

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

class MySqlTableGeneratorVisitorTest {

    static class RecordingBuilder extends TableGeneratorBuilder {
        final List<Class<?>> added = new ArrayList<>();
        RecordingBuilder(TableGeneratorDialect dialect) { super(dialect); }

        @Override
        public TableGeneratorBuilder add(TableGeneratorContributor unit) {
            added.add(unit.getClass());
            // super.add(unit) 호출은 생략(실제 SQL 생성 불필요)
            return this;
        }

        @Override
        public String build() {
            // 단순히 클래스 simpleName을 줄바꿈으로 연결
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < added.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(added.get(i).getSimpleName());
            }
            return sb.toString();
        }
    }

    private static void injectBuilder(MySqlTableGeneratorVisitor v, TableGeneratorBuilder b) throws Exception {
        Field f = MySqlTableGeneratorVisitor.class.getDeclaredField("builder");
        f.setAccessible(true);
        f.set(v, b);
    }

    @Test
    @DisplayName("visitAdded/visitDropped/visitModified가 알맞은 Contributor를 추가한다")
    void addsExpectedContributors() throws Exception {
        // given
        TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
        MySqlTableGeneratorVisitor v = new MySqlTableGeneratorVisitor(tgDialect);

        RecordingBuilder rec = new RecordingBuilder(tgDialect);
        injectBuilder(v, rec);

        TableGeneratorModel tg = mock(TableGeneratorModel.class);
        TableGeneratorModel tgOld = mock(TableGeneratorModel.class);

        // when
        v.visitAddedTableGenerator(tg);
        v.visitDroppedTableGenerator(tg);
        v.visitModifiedTableGenerator(tg, tgOld);

        // then: 추가된 컨트리뷰터의 타입을 순서대로 검증
        assertEquals(3, rec.added.size());
        assertEquals(TableGeneratorAddContributor.class,   rec.added.get(0));
        assertEquals(TableGeneratorDropContributor.class,  rec.added.get(1));
        assertEquals(TableGeneratorModifyContributor.class,rec.added.get(2));

        // getGeneratedSql()은 RecordingBuilder.build() 결과를 그대로 반환
        String sql = v.getGeneratedSql();
        assertEquals(String.join("\n",
                "TableGeneratorAddContributor",
                "TableGeneratorDropContributor",
                "TableGeneratorModifyContributor"), sql);
    }

    @Test
    @DisplayName("visit 호출이 없다면 getGeneratedSql은 빈 문자열을 반환한다")
    void emptyBuildReturnsEmptyString() throws Exception {
        TableGeneratorDialect tgDialect = mock(TableGeneratorDialect.class);
        MySqlTableGeneratorVisitor v = new MySqlTableGeneratorVisitor(tgDialect);

        RecordingBuilder rec = new RecordingBuilder(tgDialect);
        injectBuilder(v, rec);

        assertEquals("", v.getGeneratedSql());
    }
}
