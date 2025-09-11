package org.jinx.testing.visitor;

import org.jinx.migration.spi.visitor.TableVisitor;
import org.jinx.migration.spi.visitor.SqlGeneratingVisitor;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class RecordingTableVisitor implements TableVisitor, SqlGeneratingVisitor {

    public static class Event {
        public final String type;
        public final String detail;
        public Event(String type, String detail) { this.type = type; this.detail = detail; }
        @Override public String toString() { return type + "(" + detail + ")"; }
    }

    private final StringBuilder sql = new StringBuilder();
    private final List<Event> events = new ArrayList<>();
    private final Function<String, String> q; // identifier quote helper

    public RecordingTableVisitor() {
        this(s -> "\"" + s + "\"");
    }

    public RecordingTableVisitor(Function<String, String> quote) {
        this.q = quote != null ? quote : (s -> s);
    }

    @Override
    public void visitAddedTable(EntityModel table) {
        String name = table != null ? table.getTableName() : "<null>";
        line("CREATE TABLE " + q.apply(name) + " ()");
        events.add(new Event("ADDED_TABLE", name));
    }

    @Override
    public void visitDroppedTable(EntityModel table) {
        String name = table != null ? table.getTableName() : "<null>";
        line("DROP TABLE " + q.apply(name));
        events.add(new Event("DROPPED_TABLE", name));
    }

    @Override
    public void visitRenamedTable(DiffResult.RenamedTable renamed) {
        String from = renamed != null ? renamed.getOldEntity().getTableName() : "<null>";
        String to   = renamed != null ? renamed.getNewEntity().getTableName() : "<null>";
        line("RENAME TABLE " + q.apply(from) + " TO " + q.apply(to));
        events.add(new Event("RENAMED_TABLE", from + "->" + to));
    }

    private void line(String s) {
        if (sql.length() > 0 && sql.charAt(sql.length() - 1) != '\n') sql.append('\n');
        sql.append(s);
    }

    @Override
    public String getGeneratedSql() {
        return sql.toString().trim();
    }

    public List<Event> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void reset() {
        sql.setLength(0);
        events.clear();
    }
}
