package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.DiffResult.*;
import org.jinx.model.SchemaModel;

public class LiquibaseYamlGenerator {
    /**
     * Generates a Liquibase DatabaseChangeLog representing the schema differences.
     *
     * Processes differences in a deterministic order (sequences, table generators,
     * dropped objects, renames, additions, alterations, then foreign-key additions)
     * and collects resulting change sets into a DatabaseChangeLog.
     *
     * @param diff the DiffResult containing detected schema differences to convert into change sets
     * @param oldSchema unused; accepted for API compatibility but not referenced by this implementation
     * @param newSchema unused; accepted for API compatibility but not referenced by this implementation
     * @param dialectBundle supplies dialect-specific behavior used when generating change sets
     * @return a DatabaseChangeLog containing change sets produced from the provided diff
     */
    public DatabaseChangeLog generate(DiffResult diff, SchemaModel oldSchema, SchemaModel newSchema, DialectBundle dialectBundle) {
        ChangeSetIdGenerator idGenerator = new ChangeSetIdGenerator();
        LiquibaseVisitor visitor = new LiquibaseVisitor(dialectBundle, idGenerator);

        // 1. 시퀀스 변경 (ADDED, DROPPED, MODIFIED)
        diff.sequenceAccept(visitor, SequenceDiff.Type.values());

        // 2. 테이블 생성기 변경 (ADDED, DROPPED, MODIFIED)
        diff.tableGeneratorAccept(visitor, TableGeneratorDiff.Type.values());

        // 3. 테이블 삭제
        diff.tableAccept(visitor, TablePhase.DROPPED);

        // 4. 테이블 컨텐츠 변경 - DROP 단계 (FK, 인덱스, 제약, 컬럼 삭제)
        diff.tableContentAccept(visitor, TableContentPhase.DROP);

        // 5. 테이블 이름 변경
        diff.tableAccept(visitor, TablePhase.RENAMED);

        // 6. 테이블 생성
        diff.tableAccept(visitor, TablePhase.ADDED);

        // 7. 테이블 컨텐츠 변경 - ALTER 단계 (컬럼, 인덱스, 제약 추가/수정)
        diff.tableContentAccept(visitor, TableContentPhase.ALTER);

        // 8. 테이블 컨텐츠 변경 - FK 추가
        diff.tableContentAccept(visitor, TableContentPhase.FK_ADD);

        return DatabaseChangeLog.builder()
                .changeSets(visitor.getChangeSets())
                .build();
    }
}