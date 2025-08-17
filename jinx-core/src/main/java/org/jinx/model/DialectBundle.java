package org.jinx.model;

import org.jinx.migration.DatabaseType;
import org.jinx.migration.spi.dialect.*;

import java.util.Optional;
import java.util.function.Consumer;

public record DialectBundle(
        DatabaseType dialectName,
        BaseDialect base,
        DdlDialect ddl,
        Optional<IdentityDialect> identity,
        Optional<SequenceDialect> sequence,
        Optional<TableGeneratorDialect> tableGenerator,
        Optional<LiquibaseDialect> liquibase
) {

    public DatabaseType databaseType() { return dialectName; }

    public boolean supportsSequence() { return sequence.isPresent(); }
    public boolean supportsTableGenerator() { return tableGenerator.isPresent(); }
    public boolean supportsLiquibase() { return liquibase.isPresent(); }
    public boolean supportsIdentity() { return identity.isPresent(); }

    public SequenceDialect requireSequence() {
        return sequence.orElseThrow(() -> new IllegalStateException("Sequence dialect not available for this DB"));
    }
    public TableGeneratorDialect requireTableGenerator() {
        return tableGenerator.orElseThrow(() -> new IllegalStateException("TableGenerator dialect not available for this DB"));
    }
    public LiquibaseDialect requireLiquibase() {
        return liquibase.orElseThrow(() -> new IllegalStateException("Liquibase dialect not available for this DB"));
    }

    public void withSequence(Consumer<SequenceDialect> c) { sequence.ifPresent(c); }
    public void withTableGenerator(Consumer<TableGeneratorDialect> c) { tableGenerator.ifPresent(c); }
    public void withLiquibase(Consumer<LiquibaseDialect> c) { liquibase.ifPresent(c); }
    public void withIdentity(Consumer<IdentityDialect> c) { identity.ifPresent(c); }

    public static Builder builder(DdlDialect ddl, DatabaseType databaseType) { return new Builder(ddl, databaseType); }

    public static final class Builder {
        private final DatabaseType databaseType;
        private final BaseDialect base;
        private final DdlDialect ddl;
        private Optional<IdentityDialect> identity = Optional.empty();
        private Optional<SequenceDialect> sequence = Optional.empty();
        private Optional<TableGeneratorDialect> tableGenerator = Optional.empty();
        private Optional<LiquibaseDialect> liquibase = Optional.empty();

        public Builder(DdlDialect ddl, DatabaseType databaseType) {
            this.databaseType = databaseType;
            this.base = ddl;
            this.ddl = ddl;
        }

        public Builder identity(IdentityDialect id) { this.identity = Optional.ofNullable(id); return this; }
        public Builder sequence(SequenceDialect seq) { this.sequence = Optional.ofNullable(seq); return this; }
        public Builder tableGenerator(TableGeneratorDialect tg) { this.tableGenerator = Optional.ofNullable(tg); return this; }
        public Builder liquibase(LiquibaseDialect lb) { this.liquibase = Optional.ofNullable(lb); return this; }
        public DialectBundle build() { return new DialectBundle(databaseType, base, ddl, identity, sequence, tableGenerator, liquibase); }
    }
}
