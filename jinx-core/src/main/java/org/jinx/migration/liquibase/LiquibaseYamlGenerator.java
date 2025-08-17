package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.*;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

import java.util.ArrayList;
import java.util.List;

public class LiquibaseYamlGenerator {
    public DatabaseChangeLog generate(DiffResult diff, SchemaModel oldSchema, SchemaModel newSchema, Dialect dialect) {

        ChangeSetIdGenerator idGenerator = new ChangeSetIdGenerator();
        LiquibaseVisitor visitor = new LiquibaseVisitor(dialect, idGenerator);

        // 1. Visitor가 DiffResult를 방문하여 변경사항을 생성하도록 함
        diff.accept(visitor);

        // 2. Generator가 Visitor로부터 결과물을 받아 최종 순서를 조립
        List<ChangeSetWrapper> finalChangeSets = new ArrayList<>();
        finalChangeSets.addAll(visitor.getSequenceChanges()); // 시퀀스 먼저
        finalChangeSets.addAll(visitor.getTableChanges().stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof DropTableChange || change instanceof DropTableGeneratorChange))
                .toList()); // 테이블 삭제
        finalChangeSets.addAll(visitor.getDroppedFkChanges()); // 외래 키 삭제
        finalChangeSets.addAll(visitor.getConstraintChanges().stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof DropUniqueConstraintChange || change instanceof DropCheckConstraintChange))
                .toList()); // 제약조건 삭제 (Unique, Check)
        finalChangeSets.addAll(visitor.getDroppedIndexChanges()); // 인덱스 삭제
        finalChangeSets.addAll(visitor.getTableChanges().stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof RenameTableChange))
                .toList()); // 테이블 이름 변경
        finalChangeSets.addAll(visitor.getTableChanges().stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof CreateTableChange || change instanceof CreateTableGeneratorChange))
                .toList()); // 테이블 생성
        finalChangeSets.addAll(visitor.getColumnChanges()); // 컬럼 변경
        finalChangeSets.addAll(visitor.getPrimaryKeyChanges()); // 기본 키 변경
        finalChangeSets.addAll(visitor.getCreatedIndexChanges()); // 인덱스 생성
        finalChangeSets.addAll(visitor.getConstraintChanges().stream()
                .filter(cs -> cs.getChangeSet().getChanges().stream()
                        .anyMatch(change -> change instanceof AddUniqueConstraintChange || change instanceof AddCheckConstraintChange))
                .toList()); // 제약조건 생성 (Unique, Check)
        finalChangeSets.addAll(visitor.getCreatedFkChanges()); // 외래 키 생성

        return DatabaseChangeLog.builder().changeSets(finalChangeSets).build();
    }
}