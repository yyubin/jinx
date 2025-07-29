package org.jinx.migration.internal.create;

import org.jinx.migration.*;
import org.jinx.model.ConstraintModel;
import java.util.List;

public record ConstraintContributor(List<ConstraintModel> constraints) implements TableBodyContributor {
    @Override
    public int priority() {
        return 60; // Constraint 정의
    }

    @Override
    public void contribute(StringBuilder sb, Dialect dialect) {
        for (ConstraintModel cons : constraints) {
            if (cons.getName() == null || cons.getName().isEmpty()) {
                throw new IllegalStateException("Constraint name must not be null or empty for " + cons.getName());
            }
            // 제약조건 정의 SQL 생성을 Dialect에 위임합니다.
            sb.append("  ").append(dialect.getConstraintDefinitionSql(cons)).append(",\n");
        }
    }
}