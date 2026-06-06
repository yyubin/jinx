package org.jinx.migration;

import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;

public class MigrationGenerator {
    private final DialectBundle dialects;
    private final SchemaModel newSchema;
    private final boolean reverseMode;

    public MigrationGenerator(DialectBundle dialects, SchemaModel newSchema, boolean reverseMode) {
        this.dialects = dialects;
        this.newSchema = newSchema;
        this.reverseMode = reverseMode;
    }

    public String generateSql(DiffResult diff) {
        var out = new StringBuilder();
        if (reverseMode) {
            out.append("-- WARNING: this is rollback SQL for a migration").append('\n');
        }
        for (String w : diff.getWarnings()) {
            out.append("-- WARNING: ").append(w).append('\n');
        }
        
        var providers = VisitorFactory.forBundle(dialects);

        // 0) Pre-Objects
        providers.sequenceVisitor().ifPresent(sup -> diff.sequenceAccept(sup.get(),
                DiffResult.SequenceDiff.Type.ADDED, DiffResult.SequenceDiff.Type.MODIFIED));
        providers.tableGeneratorVisitor().ifPresent(sup -> diff.tableGeneratorAccept(sup.get(),
                DiffResult.TableGeneratorDiff.Type.ADDED, DiffResult.TableGeneratorDiff.Type.MODIFIED));

        // 1) 파괴적 변경 (DROP/RENAME 등)
        // 1-1) ModifiedEntity: DROP 단계
        for (var m : diff.getModifiedTables()) {
            var v = providers.tableContentVisitor().apply(m);
            m.accept(v, DiffResult.TableContentPhase.DROP);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // 1-2) 테이블 드롭 (renamed old entity 포함 — rename은 DROP+CREATE로 처리)
        {
            var v = providers.tableVisitor().get();
            var allToDrop = new java.util.ArrayList<>(diff.getDroppedTables());
            diff.getRenamedTables().stream()
                    .map(DiffResult.RenamedTable::getOldEntity)
                    .forEach(allToDrop::add);
            // FK 참조 역방향: 자식 테이블(FK 보유)이 부모보다 먼저 DROP되어야 함
            DependencyResolver.sortByFkDependency(allToDrop)
                    .reversed()
                    .forEach(v::visitDroppedTable);
            out.append(((SqlGeneratingVisitor)v).getGeneratedSql()).append('\n');
        }

        // 2) 구성적 변경 (ADD/ALTER)
        // 2-1) 테이블 생성 (renamed new entity 포함 — rename은 DROP+CREATE로 처리)
        {
            var v = providers.tableVisitor().get();
            var allToAdd = new java.util.ArrayList<>(diff.getAddedTables());
            diff.getRenamedTables().stream()
                    .map(DiffResult.RenamedTable::getNewEntity)
                    .forEach(allToAdd::add);
            // FK 참조 정방향: 부모 테이블이 자식보다 먼저 CREATE되어야 함
            DependencyResolver.sortByFkDependency(allToAdd)
                    .forEach(v::visitAddedTable);
            out.append(((SqlGeneratingVisitor)v).getGeneratedSql()).append('\n');
        }

        // 2-2) ModifiedEntity: ALTER 단계
        for (var m : diff.getModifiedTables()) {
            var v = providers.tableContentVisitor().apply(m);
            m.accept(v, DiffResult.TableContentPhase.ALTER);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // 3) FK 추가 단계
        for (var m : diff.getModifiedTables()) {
            var v = providers.tableContentVisitor().apply(m);
            m.accept(v, DiffResult.TableContentPhase.FK_ADD);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // renamed new entity도 새로 생성된 테이블로 취급해 FK 추가
        java.util.stream.Stream.concat(
                diff.getAddedTables().stream(),
                diff.getRenamedTables().stream().map(DiffResult.RenamedTable::getNewEntity)
        ).forEach(a -> {
            var v = providers.entityTableContentVisitor().apply(a);
            a.getRelationships().values().forEach(v::visitAddedRelationship);
            out.append(v.getGeneratedSql()).append('\n');
        });

        // 4) Post-Objects: Sequence/TG 드롭
        providers.sequenceVisitor().ifPresent(sup -> diff.sequenceAccept(sup.get(),
                DiffResult.SequenceDiff.Type.DROPPED));
        providers.tableGeneratorVisitor().ifPresent(sup -> diff.tableGeneratorAccept(sup.get(),
                DiffResult.TableGeneratorDiff.Type.DROPPED));

        return out.toString().trim();
    }


}