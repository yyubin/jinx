package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.migration.spi.dialect.LiquibaseDialect;
import org.jinx.model.ColumnModel;
import org.jinx.model.DialectBundle;
import org.jinx.model.EntityModel;
import org.jinx.model.GenerationStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiquibaseVisitorTest {

    @Mock
    DialectBundle dialectBundle;
    @Mock
    LiquibaseDialect liquibaseDialect;
    @Mock
    ChangeSetIdGenerator idGen;

    @InjectMocks
    LiquibaseVisitor visitor;

    @Test
    void visitAddedTable_wrapsChangesUnderChangeSetWrapper() {
        // given
        DialectBundle dialect = mock(DialectBundle.class);
        // liquibase() 경로를 사용하지 않도록 fallback을 막기 위해 type override를 쓸 예정 -> Optional.empty()
        when(dialect.liquibase()).thenReturn(Optional.empty());

        ChangeSetIdGenerator ids = mock(ChangeSetIdGenerator.class);
        when(ids.nextId()).thenReturn("001", "002");

        LiquibaseVisitor v = new LiquibaseVisitor(dialect, ids);

        EntityModel table = EntityModel.builder()
                .entityName("users")
                .tableName("users")
                .isValid(true)
                .build();

        // PK/일반 컬럼 2개 추가
        table.putColumn(ColumnModel.builder()
                .columnName("id")
                .tableName("users")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .isNullable(false)
                .isUnique(false)
                .sqlTypeOverride("bigint")   // dialect 의존 제거
                .build());
        table.putColumn(ColumnModel.builder()
                .columnName("name")
                .tableName("users")
                .javaType("java.lang.String")
                .isNullable(false)
                .isUnique(false)
                .sqlTypeOverride("varchar(255)")
                .build());

        // when
        v.visitAddedTable(table);

        // then: 래퍼 → 체인지셋 → 체인지 확인
        List<ChangeSetWrapper> all = v.getChangeSets();
        assertThat(all.size()).isEqualTo(2);

        // 1) createTable
        ChangeSetWrapper cs1w = all.get(0);
        assertThat(cs1w).isNotNull();
        ChangeSet cs1 = cs1w.getChangeSet();
        assertThat(cs1.getId()).isEqualTo("001");
        assertThat(cs1.getChanges().size()).isEqualTo(1);
        assertThat(cs1.getChanges().get(0)).isInstanceOf(CreateTableChange.class);

        CreateTableChange ct = (CreateTableChange) cs1.getChanges().get(0);
        assertThat(ct.getConfig().getTableName()).isEqualTo("users");
        assertThat(ct.getConfig().getColumns().size()).isEqualTo(2);
        // PK는 createTable 단계 constraints에서 제외하고, 별도 AddPrimaryKey로 추가됨

        // 2) addPrimaryKey
        ChangeSetWrapper cs2w = all.get(1);
        ChangeSet cs2 = cs2w.getChangeSet();
        assertThat(cs2.getId()).isEqualTo("002");
        assertThat(cs2.getChanges().size()).isEqualTo(1);
        assertThat(cs2.getChanges().get(0)).isInstanceOf(AddPrimaryKeyConstraintChange.class);

        AddPrimaryKeyConstraintChange pk = (AddPrimaryKeyConstraintChange) cs2.getChanges().get(0);
        assertThat(pk.getConfig().getTableName()).isEqualTo("users");
        assertThat(pk.getConfig().getColumnNames()).isEqualTo("id");
    }

    @Test
    void visitAddedColumn_sets_defaultValueSequenceNext_whenSequenceStrategy() {
        // given
        DialectBundle dialect = mock(DialectBundle.class);
        when(dialect.liquibase()).thenReturn(Optional.empty());

        ChangeSetIdGenerator ids = mock(ChangeSetIdGenerator.class);
        when(ids.nextId()).thenReturn("100");

        LiquibaseVisitor v = new LiquibaseVisitor(dialect, ids);
        v.setCurrentTableName("orders");

        // SEQUENCE 전략 + defaultValue에 시퀀스명 저장 → defaultValueSequenceNext로 나가야 함
        ColumnModel seqCol = ColumnModel.builder()
                .columnName("id")
                .tableName("orders")
                .javaType("java.lang.Long")
                .generationStrategy(GenerationStrategy.SEQUENCE)
                .defaultValue("order_id_seq")           // 시퀀스명
                .sqlTypeOverride("bigint")
                .isUnique(false)
                .isNullable(false)
                .isPrimaryKey(true) // (참고) AddColumn에서 PK는 즉시 constraints로 갈 수 있으니 정책에 유의
                .build();

        // when
        v.visitAddedColumn(seqCol);

        // then
        List<ChangeSetWrapper> all = v.getChangeSets();
        assertThat(all.size()).isEqualTo(1);

        ChangeSet cs = all.get(0).getChangeSet();
        assertThat(cs.getId()).isEqualTo("100");
        assertThat(cs.getChanges().size()).isEqualTo(1);
        assertThat(cs.getChanges().get(0)).isInstanceOf(AddColumnChange.class);

        AddColumnChange add = (AddColumnChange) cs.getChanges().get(0);
        AddColumnConfig cfg = add.getConfig();

        assertThat(cfg.getTableName()).isEqualTo("orders");
        assertThat(cfg.getColumns().size()).isEqualTo(1);

        ColumnConfig colCfg = cfg.getColumns().get(0).getConfig();
        // 우선순위: computed > sequence > literal
        assertThat(colCfg.getDefaultValueComputed()).isNull();
        assertThat(colCfg.getDefaultValue()).isNull();
        assertThat(colCfg.getDefaultValueSequenceNext()).isEqualTo("order_id_seq");
        assertThat(colCfg.getType()).isEqualTo("bigint");
    }

    @Test
    void visitAddedColumn_sets_literalDefault_ifNoComputedOrSequence() {
        // given
        DialectBundle dialect = mock(DialectBundle.class);
        when(dialect.liquibase()).thenReturn(Optional.empty());

        ChangeSetIdGenerator ids = mock(ChangeSetIdGenerator.class);
        when(ids.nextId()).thenReturn("200");

        LiquibaseVisitor v = new LiquibaseVisitor(dialect, ids);
        v.setCurrentTableName("emails");

        ColumnModel col = ColumnModel.builder()
                .columnName("status")
                .tableName("emails")
                .javaType("java.lang.String")
                .generationStrategy(GenerationStrategy.NONE)
                .defaultValue("ACTIVE")                 // literal
                .isUnique(false)
                .isNullable(false)
                .sqlTypeOverride("varchar(50)")
                .build();

        // when
        v.visitAddedColumn(col);

        // then
        ChangeSet cs = v.getChangeSets().get(0).getChangeSet();
        AddColumnChange add = (AddColumnChange) cs.getChanges().get(0);
        ColumnConfig cfg = add.getConfig().getColumns().get(0).getConfig();

        assertThat(cfg.getDefaultValueComputed()).isNull();
        assertThat(cfg.getDefaultValueSequenceNext()).isNull();
        assertThat(cfg.getDefaultValue()).isEqualTo("ACTIVE");
        assertThat(cfg.getType()).isEqualTo("varchar(50)");
    }

}