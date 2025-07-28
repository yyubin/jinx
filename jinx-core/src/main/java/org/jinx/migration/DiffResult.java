package org.jinx.migration;

import lombok.Builder;
import lombok.Getter;
import org.jinx.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Builder
@Getter
public class DiffResult {
    @Builder.Default private List<EntityModel> addedTables = new ArrayList<>();
    @Builder.Default private List<EntityModel> droppedTables = new ArrayList<>();
    @Builder.Default private List<ModifiedEntity> modifiedTables = new ArrayList<>();
    @Builder.Default private List<SequenceDiff> sequenceDiffs = new ArrayList<>();
    @Builder.Default private List<TableGeneratorDiff> tableGeneratorDiffs = new ArrayList<>();
    @Builder.Default private List<String> warnings = new ArrayList<>();

    public List<String> getAllWarnings() {
        List<String> all = new ArrayList<>(warnings);                            // 스키마 레벨
        modifiedTables.forEach(m -> all.addAll(m.getWarnings())); // 엔티티 레벨
        return all;
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

        public void accept(MigrationVisitor visitor) {
            for (ColumnDiff diff : columnDiffs) {
                switch (diff.getType()) {
                    case ADDED -> visitor.visitAddedColumn(diff.getColumn());
                    case DROPPED -> visitor.visitDroppedColumn(diff.getColumn());
                    case MODIFIED -> visitor.visitModifiedColumn(diff.getColumn(), diff.getOldColumn());
                    case RENAMED -> visitor.visitRenamedColumn(diff.getColumn(), diff.getOldColumn());
                }
            }
            for (IndexDiff diff : indexDiffs) {
                switch (diff.getType()) {
                    case ADDED -> visitor.visitAddedIndex(diff.getIndex());
                    case DROPPED -> visitor.visitDroppedIndex(diff.getIndex());
                    case MODIFIED -> visitor.visitModifiedIndex(diff.getIndex(), diff.getOldIndex());
                }
            }
            for (ConstraintDiff diff : constraintDiffs) {
                switch (diff.getType()) {
                    case ADDED -> visitor.visitAddedConstraint(diff.getConstraint());
                    case DROPPED -> visitor.visitDroppedConstraint(diff.getConstraint());
                    case MODIFIED -> visitor.visitModifiedConstraint(diff.getConstraint(), diff.getOldConstraint());
                }
            }
            for (RelationshipDiff diff : relationshipDiffs) {
                switch (diff.getType()) {
                    case ADDED -> visitor.visitAddedRelationship(diff.getRelationship());
                    case DROPPED -> visitor.visitDroppedRelationship(diff.getRelationship());
                    case MODIFIED -> visitor.visitModifiedRelationship(diff.getRelationship(), diff.getOldRelationship());
                }
            }
        }
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

        public static SequenceDiff modified(SequenceModel oldSequence, SequenceModel newSequence) {
            return SequenceDiff.builder()
                    .type(Type.MODIFIED)
                    .sequence(newSequence)
                    .oldSequence(oldSequence)
                    .changeDetail(getChangeDetail(oldSequence, newSequence))
                    .build();
        }

        private static String getChangeDetail(SequenceModel oldSeq, SequenceModel newSeq) {
            StringBuilder detail = new StringBuilder();
            if (oldSeq.getInitialValue() != newSeq.getInitialValue()) {
                detail.append("initialValue changed from ").append(oldSeq.getInitialValue()).append(" to ").append(newSeq.getInitialValue()).append("; ");
            }
            if (oldSeq.getAllocationSize() != newSeq.getAllocationSize()) {
                detail.append("allocationSize changed from ").append(oldSeq.getAllocationSize()).append(" to ").append(newSeq.getAllocationSize()).append("; ");
            }
            if (oldSeq.getCache() != newSeq.getCache()) {
                detail.append("cache changed from ").append(oldSeq.getCache()).append(" to ").append(newSeq.getCache()).append("; ");
            }
            if (oldSeq.getMinValue() != newSeq.getMinValue()) {
                detail.append("minValue changed from ").append(oldSeq.getMinValue()).append(" to ").append(newSeq.getMinValue()).append("; ");
            }
            if (oldSeq.getMaxValue() != newSeq.getMaxValue()) {
                detail.append("maxValue changed from ").append(oldSeq.getMaxValue()).append(" to ").append(newSeq.getMaxValue()).append("; ");
            }
            if (detail.length() > 2) {
                detail.setLength(detail.length() - 2);
            }
            return detail.toString();
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

        public static TableGeneratorDiff modified(TableGeneratorModel oldTableGenerator, TableGeneratorModel newTableGenerator) {
            return TableGeneratorDiff.builder()
                    .type(Type.MODIFIED)
                    .tableGenerator(newTableGenerator)
                    .oldTableGenerator(oldTableGenerator)
                    .changeDetail(getChangeDetail(oldTableGenerator, newTableGenerator))
                    .build();
        }

        private static String getChangeDetail(TableGeneratorModel oldTg, TableGeneratorModel newTg) {
            StringBuilder detail = new StringBuilder();
            if (!Objects.equals(oldTg.getTable(), newTg.getTable())) {
                detail.append("table changed from ").append(oldTg.getTable()).append(" to ").append(newTg.getTable()).append("; ");
            }
            if (!Objects.equals(oldTg.getSchema(), newTg.getSchema())) {
                detail.append("schema changed from ").append(oldTg.getSchema()).append(" to ").append(newTg.getSchema()).append("; ");
            }
            if (!Objects.equals(oldTg.getCatalog(), newTg.getCatalog())) {
                detail.append("catalog changed from ").append(oldTg.getCatalog()).append(" to ").append(newTg.getCatalog()).append("; ");
            }
            if (!Objects.equals(oldTg.getPkColumnName(), newTg.getPkColumnName())) {
                detail.append("pkColumnName changed from ").append(oldTg.getPkColumnName()).append(" to ").append(newTg.getPkColumnName()).append("; ");
            }
            if (!Objects.equals(oldTg.getValueColumnName(), newTg.getValueColumnName())) {
                detail.append("valueColumnName changed from ").append(oldTg.getValueColumnName()).append(" to ").append(newTg.getValueColumnName()).append("; ");
            }
            if (!Objects.equals(oldTg.getPkColumnValue(), newTg.getPkColumnValue())) {
                detail.append("pkColumnValue changed from ").append(oldTg.getPkColumnValue()).append(" to ").append(newTg.getPkColumnValue()).append("; ");
            }
            if (oldTg.getInitialValue() != newTg.getInitialValue()) {
                detail.append("initialValue changed from ").append(oldTg.getInitialValue()).append(" to ").append(newTg.getInitialValue()).append("; ");
            }
            if (oldTg.getAllocationSize() != newTg.getAllocationSize()) {
                detail.append("allocationSize changed from ").append(oldTg.getAllocationSize()).append(" to ").append(newTg.getAllocationSize()).append("; ");
            }
            if (detail.length() > 2) {
                detail.setLength(detail.length() - 2);
            }
            return detail.toString();
        }
    }
}