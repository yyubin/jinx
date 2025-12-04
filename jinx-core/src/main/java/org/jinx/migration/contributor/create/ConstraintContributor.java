package org.jinx.migration.contributor.create;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.contributor.TableBodyContributor;
import org.jinx.migration.spi.dialect.DdlDialect;
import org.jinx.model.ConstraintModel;
import java.util.List;

public record ConstraintContributor(List<ConstraintModel> constraints) implements DdlContributor, TableBodyContributor {
    @Override
    public int priority() {
        return 60; // Constraint 정의
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        for (ConstraintModel cons : constraints) {
            if (cons.getName() == null || cons.getName().isEmpty()) {
                throw new IllegalStateException("Constraint name must not be null or empty for " + cons.getName());
            }
            // 제약조건 정의 SQL 생성을 Dialect에 위임합니다.
            sb.append("  ").append(dialect.getConstraintDefinitionSql(cons)).append(",\n");
        }
    }
}