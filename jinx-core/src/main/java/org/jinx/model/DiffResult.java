package org.jinx.model;

import lombok.Builder;
import lombok.Getter;
import org.jinx.migration.MigrationVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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