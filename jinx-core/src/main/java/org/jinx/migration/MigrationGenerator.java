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
            diff.tableContentAccept(v, DiffResult.TableContentPhase.DROP);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // 1-2) 테이블 드롭/리네임
        {
            var v = providers.tableVisitor().get();
            diff.tableAccept(v, DiffResult.TablePhase.DROPPED);
            diff.tableAccept(v, DiffResult.TablePhase.RENAMED);
            out.append(((SqlGeneratingVisitor)v).getGeneratedSql()).append('\n');
        }

        // 2) 구성적 변경 (ADD/ALTER)
        // 2-1) 테이블 생성
        {
            var v = providers.tableVisitor().get();
            diff.tableAccept(v, DiffResult.TablePhase.ADDED);
            out.append(((SqlGeneratingVisitor)v).getGeneratedSql()).append('\n');
        }

        // 2-2) ModifiedEntity: ALTER 단계
        for (var m : diff.getModifiedTables()) {
            var v = providers.tableContentVisitor().apply(m);
            diff.tableContentAccept(v, DiffResult.TableContentPhase.ALTER);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // 3) FK 추가 단계
        for (var m : diff.getModifiedTables()) {
            var v = providers.tableContentVisitor().apply(m);
            diff.tableContentAccept(v, DiffResult.TableContentPhase.FK_ADD);
            out.append(v.getGeneratedSql()).append('\n');
        }

        // 4) Post-Objects: Sequence/TG 드롭
        providers.sequenceVisitor().ifPresent(sup -> diff.sequenceAccept(sup.get(),
                DiffResult.SequenceDiff.Type.DROPPED));
        providers.tableGeneratorVisitor().ifPresent(sup -> diff.tableGeneratorAccept(sup.get(),
                DiffResult.TableGeneratorDiff.Type.DROPPED));

        return out.toString().trim();
    }


}