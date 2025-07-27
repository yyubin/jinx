package org.jinx.migration;

import lombok.Builder;
import lombok.Data;
import org.jinx.model.*;
import java.util.*;

@Data
@Builder
public class DiffResult {
    @Builder.Default
    private List<EntityModel> addedTables = new ArrayList<>();
    @Builder.Default
    private List<EntityModel> droppedTables = new ArrayList<>();
    @Builder.Default
    private List<ModifiedEntity> modifiedTables = new ArrayList<>();

    @Data
    @Builder
    public static class ModifiedEntity {
        private EntityModel oldEntity;
        private EntityModel newEntity;
        @Builder.Default
        private List<ColumnDiff> columnDiffs = new ArrayList<>();
        @Builder.Default
        private List<IndexDiff> indexDiffs = new ArrayList<>();
        @Builder.Default
        private List<ConstraintDiff> constraintDiffs = new ArrayList<>();
        @Builder.Default
        private List<RelationshipDiff> relationshipDiffs = new ArrayList<>();
    }

    @Data
    @Builder
    public static class ColumnDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private ColumnModel column;
        private ColumnModel oldColumn;
        private String changeDetail;
    }

    @Data
    @Builder
    public static class IndexDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private IndexModel index;
        private String changeDetail;
    }

    @Data
    @Builder
    public static class ConstraintDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private ConstraintModel constraint;
        private String changeDetail;
    }

    @Data
    @Builder
    public static class RelationshipDiff {
        public enum Type { ADDED, DROPPED, MODIFIED }
        private Type type;
        private RelationshipModel relationship;
        private String changeDetail;
    }
}