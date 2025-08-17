package org.jinx.model;

import lombok.Builder;
import lombok.Getter;
import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;

import java.util.ArrayList;
import java.util.List;

@Builder
@Getter
public class DiffResult {
    @Builder.Default private List<EntityModel> addedTables = new ArrayList<>();
    @Builder.Default private List<EntityModel> droppedTables = new ArrayList<>();
    @Builder.Default private List<ModifiedEntity> modifiedTables = new ArrayList<>();
    @Builder.Default private List<RenamedTable> renamedTables = new ArrayList<>();
    @Builder.Default private List<SequenceDiff> sequenceDiffs = new ArrayList<>();
    @Builder.Default private List<TableGeneratorDiff> tableGeneratorDiffs = new ArrayList<>();
    @Builder.Default private List<String> warnings = new ArrayList<>();

    public List<String> getAllWarnings() {
        return warnings;
    }

    public enum TablePhase { DROPPED, RENAMED, ADDED }
    public enum TableContentPhase { DROP, ALTER, FK_ADD }

    public void sequenceAccept(SequenceVisitor visitor, SequenceDiff.Type... types) {
        var allow = java.util.EnumSet.noneOf(SequenceDiff.Type.class);
        java.util.Collections.addAll(allow, types);
        sequenceDiffs.stream()
                .filter(d -> allow.contains(d.getType()))
                .forEach(diff -> {
                    switch (diff.getType()) {
                        case ADDED -> visitor.visitAddedSequence(diff.getSequence());
                        case DROPPED -> visitor.visitDroppedSequence(diff.getSequence());
                        case MODIFIED -> visitor.visitModifiedSequence(diff.getSequence(), diff.getOldSequence());
                    }
                });
    }

    public void tableGeneratorAccept(TableGeneratorVisitor visitor, TableGeneratorDiff.Type... types) {
        var allow = java.util.EnumSet.noneOf(TableGeneratorDiff.Type.class);
        java.util.Collections.addAll(allow, types);
        tableGeneratorDiffs.stream()
                .filter(d -> allow.contains(d.getType()))
                .forEach(diff -> {
                    switch (diff.getType()) {
                        case ADDED -> visitor.visitAddedTableGenerator(diff.getTableGenerator());
                        case DROPPED -> visitor.visitDroppedTableGenerator(diff.getTableGenerator());
                        case MODIFIED -> visitor.visitModifiedTableGenerator(diff.getTableGenerator(), diff.getOldTableGenerator());
                    }
                });
    }

    public void tableContentAccept(TableContentVisitor visitor, TableContentPhase phase) {
        modifiedTables.forEach(m -> m.accept(visitor, phase));
    }

    public void tableAccept(TableVisitor visitor, TablePhase phase) {
        switch (phase) {
            case DROPPED -> droppedTables.forEach(visitor::visitDroppedTable);
            case RENAMED -> renamedTables.forEach(visitor::visitRenamedTable);
            case ADDED   -> addedTables.forEach(visitor::visitAddedTable);
        }
    }

    @Builder
    @Getter
    public static class ModifiedEntity {
        private EntityModel oldEntity;
        private EntityModel newEntity;
        @Builder.Default private List<ColumnDiff> columnDiffs = new ArrayList<>();
        @Builder.Default private List<IndexDiff> indexDiffs = new ArrayList<>();
        @Builder.Default private List<ConstraintDiff> constraintDiffs = new ArrayList<>();
        @Builder.Default private List<RelationshipDiff> relationshipDiffs = new ArrayList<>();
        @Builder.Default private List<String> warnings = new ArrayList<>();

        public void accept(TableContentVisitor v, TableContentPhase phase) {
            switch (phase) {
                case DROP -> {
                    // 1) FK drop / modify(drop-part)
                    for (RelationshipDiff d : relationshipDiffs) {
                        if (d.getType() == RelationshipDiff.Type.DROPPED) v.visitDroppedRelationship(d.getRelationship());
                        else if (d.getType() == RelationshipDiff.Type.MODIFIED) v.visitModifiedRelationship(d.getRelationship(), d.getOldRelationship());
                    }
                    // 2) 보조 인덱스/제약 드롭
                    for (IndexDiff d : indexDiffs) if (d.getType() == IndexDiff.Type.DROPPED) v.visitDroppedIndex(d.getIndex());
                    for (ConstraintDiff d : constraintDiffs) if (d.getType() == ConstraintDiff.Type.DROPPED) v.visitDroppedConstraint(d.getConstraint());
                    // 3) 컬럼 드롭
                    for (ColumnDiff d : columnDiffs) if (d.getType() == ColumnDiff.Type.DROPPED) v.visitDroppedColumn(d.getColumn());
                    // 4) 컬럼 리네임은 DROP 직후 처리 (FK 제거된 상태에서 안전)
                    for (ColumnDiff d : columnDiffs) if (d.getType() == ColumnDiff.Type.RENAMED) v.visitRenamedColumn(d.getColumn(), d.getOldColumn());
                }
                case ALTER -> {
                    // 컬럼 추가/수정, 인덱스/제약 추가 및 수정 (FK 제외)
                    for (ColumnDiff d : columnDiffs) {
                        if (d.getType() == ColumnDiff.Type.ADDED) v.visitAddedColumn(d.getColumn());
                        else if (d.getType() == ColumnDiff.Type.MODIFIED) v.visitModifiedColumn(d.getColumn(), d.getOldColumn());
                    }
                    for (IndexDiff d : indexDiffs) {
                        if (d.getType() == IndexDiff.Type.ADDED) v.visitAddedIndex(d.getIndex());
                        else if (d.getType() == IndexDiff.Type.MODIFIED) v.visitModifiedIndex(d.getIndex(), d.getOldIndex());
                    }
                    for (ConstraintDiff d : constraintDiffs) {
                        if (d.getType() == ConstraintDiff.Type.ADDED) v.visitAddedConstraint(d.getConstraint());
                        else if (d.getType() == ConstraintDiff.Type.MODIFIED) v.visitModifiedConstraint(d.getConstraint(), d.getOldConstraint());
                    }
                }
                case FK_ADD -> {
                    // FK 추가 / modify(add-part)
                    for (RelationshipDiff d : relationshipDiffs) {
                        if (d.getType() == RelationshipDiff.Type.ADDED) v.visitAddedRelationship(d.getRelationship());
                        else if (d.getType() == RelationshipDiff.Type.MODIFIED) v.visitModifiedRelationship(d.getRelationship(), d.getOldRelationship());
                    }
                }
            }
        }
    }

    @Builder
    @Getter
    public static class RenamedTable {
        private EntityModel oldEntity;
        private EntityModel newEntity;
        private String changeDetail;
    }

    @Builder
    @Getter
    public static class ColumnDiff {
        public enum Type { ADDED, DROPPED, MODIFIED, RENAMED }
        private Type type;
        private ColumnModel column;
        private ColumnModel oldColumn;
        private String changeDetail;
    }

    @Builder
    @Getter
    public static class IndexDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private IndexModel index;
        private IndexModel oldIndex;
        private String changeDetail;
    }

    @Builder
    @Getter
    public static class ConstraintDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private ConstraintModel constraint;
        private ConstraintModel oldConstraint;
        private String changeDetail;
    }

    @Builder
    @Getter
    public static class RelationshipDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private RelationshipModel relationship;
        private RelationshipModel oldRelationship;
        private String changeDetail;
    }

    @Builder
    @Getter
    public static class SequenceDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private SequenceModel sequence;
        private SequenceModel oldSequence;
        private String changeDetail;

        public static SequenceDiff added(SequenceModel sequence) {
            return SequenceDiff.builder().type(Type.ADDED).sequence(sequence).build();
        }

        public static SequenceDiff dropped(SequenceModel sequence) {
            return SequenceDiff.builder().type(Type.DROPPED).sequence(sequence).build();
        }
    }

    @Builder
    @Getter
    public static class TableGeneratorDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private TableGeneratorModel tableGenerator;
        private TableGeneratorModel oldTableGenerator;
        private String changeDetail;

        public static TableGeneratorDiff added(TableGeneratorModel tableGenerator) {
            return TableGeneratorDiff.builder().type(Type.ADDED).tableGenerator(tableGenerator).build();
        }

        public static TableGeneratorDiff dropped(TableGeneratorModel tableGenerator) {
            return TableGeneratorDiff.builder().type(Type.DROPPED).tableGenerator(tableGenerator).build();
        }

    }
}