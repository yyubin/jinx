package org.jinx.testing.visitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.IndexModel;
import org.jinx.model.RelationshipModel;
import org.jinx.testing.visitor.RecordingTableContentVisitor.Event;

public class RecordingEntityTableContentVisitor implements TableContentVisitor, SqlGeneratingVisitor {
    public static class Event {
        public final String type;
        public final String detail;
        public Event(String type, String detail) { this.type = type; this.detail = detail; }
        @Override public String toString() { return type + "(" + detail + ")"; }
    }

    private final StringBuilder sql = new StringBuilder();
    private final List<RecordingTableContentVisitor.Event> events = new ArrayList<>();
    private final Function<String, String> q;

    public RecordingEntityTableContentVisitor() { this(s -> "\"" + s + "\""); }
    public RecordingEntityTableContentVisitor(Function<String, String> quote) {
        this.q = quote != null ? quote : (s -> s);
    }

    private void line(String s) {
        if (sql.length() > 0 && sql.charAt(sql.length() - 1) != '\n') sql.append('\n');
        sql.append(s);
    }

    @Override public String getGeneratedSql() { return sql.toString().trim(); }

    // Column
    @Override public void visitAddedColumn(ColumnModel column) {
        String name = column != null ? column.getColumnName() : "<null>";
        line("ALTER TABLE ADD COLUMN " + q.apply(name));
        events.add(new RecordingTableContentVisitor.Event("ADDED_COLUMN", name));
    }

    @Override public void visitDroppedColumn(ColumnModel column) {
        String name = column != null ? column.getColumnName() : "<null>";
        line("ALTER TABLE DROP COLUMN " + q.apply(name));
        events.add(new RecordingTableContentVisitor.Event("DROPPED_COLUMN", name));
    }

    @Override public void visitModifiedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        String n = newColumn != null ? newColumn.getColumnName() : "<null>";
        String o = oldColumn != null ? oldColumn.getColumnName() : "<null>";
        line("ALTER TABLE MODIFY COLUMN " + q.apply(n) + " /* from " + q.apply(o) + " */");
        events.add(new RecordingTableContentVisitor.Event("MODIFIED_COLUMN", o + "->" + n));
    }

    @Override public void visitRenamedColumn(ColumnModel newColumn, ColumnModel oldColumn) {
        String n = newColumn != null ? newColumn.getColumnName() : "<null>";
        String o = oldColumn != null ? oldColumn.getColumnName() : "<null>";
        line("ALTER TABLE RENAME COLUMN " + q.apply(o) + " TO " + q.apply(n));
        events.add(new RecordingTableContentVisitor.Event("RENAMED_COLUMN", o + "->" + n));
    }

    // Primary Key
    @Override public void visitAddedPrimaryKey(List<String> pkColumns) {
        String cols = String.join(", ", pkColumns.stream().map(q).toList());
        line("ALTER TABLE ADD PRIMARY KEY (" + cols + ")");
        events.add(new RecordingTableContentVisitor.Event("ADDED_PK", cols));
    }

    @Override public void visitDroppedPrimaryKey() {
        line("ALTER TABLE DROP PRIMARY KEY");
        events.add(new RecordingTableContentVisitor.Event("DROPPED_PK", ""));
    }

    @Override public void visitModifiedPrimaryKey(List<String> newPkColumns, List<String> oldPkColumns) {
        String n = String.join(", ", newPkColumns.stream().map(q).toList());
        String o = String.join(", ", oldPkColumns.stream().map(q).toList());
        line("/* MODIFY PK " + o + " -> " + n + " */");
        events.add(new RecordingTableContentVisitor.Event("MODIFIED_PK", o + "->" + n));
    }

    // Index
    @Override public void visitAddedIndex(IndexModel index) {
        String idx = index != null ? index.getIndexName() : "<null>";
        line("CREATE INDEX " + q.apply(idx));
        events.add(new RecordingTableContentVisitor.Event("ADDED_INDEX", idx));
    }

    @Override public void visitDroppedIndex(IndexModel index) {
        String idx = index != null ? index.getIndexName() : "<null>";
        line("DROP INDEX " + q.apply(idx));
        events.add(new RecordingTableContentVisitor.Event("DROPPED_INDEX", idx));
    }

    @Override public void visitModifiedIndex(IndexModel newIndex, IndexModel oldIndex) {
        String n = newIndex != null ? newIndex.getIndexName() : "<null>";
        String o = oldIndex != null ? oldIndex.getIndexName() : "<null>";
        line("/* MODIFY INDEX " + q.apply(o) + " -> " + q.apply(n) + " */");
        events.add(new RecordingTableContentVisitor.Event("MODIFIED_INDEX", o + "->" + n));
    }

    // Constraint
    @Override public void visitAddedConstraint(ConstraintModel constraint) {
        String name = constraint != null ? constraint.getName() : "<null>";
        line("ALTER TABLE ADD CONSTRAINT " + q.apply(name));
        events.add(new RecordingTableContentVisitor.Event("ADDED_CONSTRAINT", name));
    }

    @Override public void visitDroppedConstraint(ConstraintModel constraint) {
        String name = constraint != null ? constraint.getName() : "<null>";
        line("ALTER TABLE DROP CONSTRAINT " + q.apply(name));
        events.add(new RecordingTableContentVisitor.Event("DROPPED_CONSTRAINT", name));
    }

    @Override public void visitModifiedConstraint(ConstraintModel newConstraint, ConstraintModel oldConstraint) {
        String n = newConstraint != null ? newConstraint.getName() : "<null>";
        String o = oldConstraint != null ? oldConstraint.getName() : "<null>";
        line("/* MODIFY CONSTRAINT " + q.apply(o) + " -> " + q.apply(n) + " */");
        events.add(new RecordingTableContentVisitor.Event("MODIFIED_CONSTRAINT", o + "->" + n));
    }

    // Relationship
    @Override public void visitAddedRelationship(RelationshipModel relationship) {
        String name = relationship != null ? relationship.getConstraintName() : "<null>";
        line("/* ADD RELATIONSHIP " + q.apply(name) + " */");
        events.add(new RecordingTableContentVisitor.Event("ADDED_REL", name));
    }

    @Override public void visitDroppedRelationship(RelationshipModel relationship) {
        String name = relationship != null ? relationship.getConstraintName() : "<null>";
        line("/* DROP RELATIONSHIP " + q.apply(name) + " */");
        events.add(new RecordingTableContentVisitor.Event("DROPPED_REL", name));
    }

    @Override public void visitModifiedRelationship(RelationshipModel newRelationship, RelationshipModel oldRelationship) {
        String n = newRelationship != null ? newRelationship.getConstraintName() : "<null>";
        String o = oldRelationship != null ? oldRelationship.getConstraintName() : "<null>";
        line("/* MODIFY RELATIONSHIP " + q.apply(o) + " -> " + q.apply(n) + " */");
        events.add(new RecordingTableContentVisitor.Event("MODIFIED_REL", o + "->" + n));
    }

    public List<RecordingTableContentVisitor.Event> getEvents() { return Collections.unmodifiableList(events); }
    public void reset() { sql.setLength(0); events.clear(); }
}
