package org.jinx.migration.dialect.postgresql;

import org.jinx.migration.contributor.DdlContributor;
import org.jinx.migration.spi.dialect.DdlDialect;

/**
 * PostgreSQL 전용 PK DROP contributor.
 * <p>
 * MySQL과 달리 PostgreSQL은 PK를 이름 있는 제약(CONSTRAINT)으로 관리하므로
 * {@code DROP CONSTRAINT <name>} 구문이 필요하다.
 * 제약 이름은 엔티티 메타데이터에서 추출하며, 찾을 수 없을 때만
 * PostgreSQL 기본 명명 규칙({@code {table}_pkey})으로 fallback한다.
 */
public record PostgreSqlPrimaryKeyDropContributor(String table, String pkConstraintName)
        implements DdlContributor {

    @Override
    public int priority() {
        return 10; // PrimaryKeyDropContributor와 동일
    }

    @Override
    public void contribute(StringBuilder sb, DdlDialect dialect) {
        sb.append("ALTER TABLE ")
          .append(dialect.quoteIdentifier(table))
          .append(" DROP CONSTRAINT ")
          .append(dialect.quoteIdentifier(pkConstraintName))
          .append(";\n");
    }
}
