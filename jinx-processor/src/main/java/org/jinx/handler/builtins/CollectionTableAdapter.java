package org.jinx.handler.builtins;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.TableLike;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.IndexModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * An adapter that wraps a {@link CollectionTable} annotation to expose its properties
 * through the {@link TableLike} interface.
 */
public class CollectionTableAdapter implements TableLike {
    private final CollectionTable collectionTable;
    private final ProcessingContext context;
    private final String effectiveTableName;

    public CollectionTableAdapter(CollectionTable collectionTable, ProcessingContext context) {
        this.collectionTable = collectionTable;
        this.context = context;
        this.effectiveTableName = null;
    }
    
    public CollectionTableAdapter(CollectionTable collectionTable, ProcessingContext context, String effectiveTableName) {
        this.collectionTable = collectionTable;
        this.context = context;
        this.effectiveTableName = effectiveTableName;
    }

    @Override
    public String getName() {
        String name = collectionTable.name();
        if (name.isEmpty()) {
            return (effectiveTableName != null && !effectiveTableName.isEmpty())
                   ? effectiveTableName
                   : "_anonymous_collection_"; // Safe fallback.
        }
        return name;
    }

    @Override
    public Optional<String> getSchema() {
        return Optional.ofNullable(collectionTable.schema()).filter(s -> !s.isEmpty());
    }

    @Override
    public Optional<String> getCatalog() {
        return Optional.ofNullable(collectionTable.catalog()).filter(c -> !c.isEmpty());
    }

    @Override
    public List<IndexModel> getIndexes() {
        List<IndexModel> indexes = new ArrayList<>();
        for (Index index : collectionTable.indexes()) {
            // First, trim column names and remove empty tokens.
            List<String> columnNames = Arrays.stream(index.columnList().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            
            // Skip if the column list is empty.
            if (columnNames.isEmpty()) {
                continue;
            }
            
            // Generate index name from the trimmed column list.
            String indexName = index.name().isEmpty()
                ? context.getNaming().ixName(getName(), columnNames)
                : index.name();
            
            IndexModel indexModel = IndexModel.builder()
                .indexName(indexName)
                .tableName(getName())
                .columnNames(columnNames)
                .build();
            indexes.add(indexModel);
        }
        return indexes;
    }

    @Override
    public List<ConstraintModel> getConstraints() {
        List<ConstraintModel> constraints = new ArrayList<>();
        
        // Process UniqueConstraints.
        for (UniqueConstraint uc : collectionTable.uniqueConstraints()) {
            List<String> cols = Arrays.stream(uc.columnNames())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            if (cols.isEmpty()) continue;

            String constraintName = uc.name().isEmpty()
                ? context.getNaming().uqName(getName(), cols)
                : uc.name();

            ConstraintModel constraint = ConstraintModel.builder()
                .name(constraintName)
                .type(ConstraintType.UNIQUE)
                .tableName(getName())
                .columns(cols)
                .build();
            constraints.add(constraint);
        }
        
        return constraints;
    }

    @Override
    public Optional<String> getComment() {
        return Optional.empty();
    }
}