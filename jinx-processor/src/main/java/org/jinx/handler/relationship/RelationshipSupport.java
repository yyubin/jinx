package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

public final class RelationshipSupport {
    
    private final ProcessingContext context;
    
    public RelationshipSupport(ProcessingContext context) {
        this.context = context;
    }
    
    /**
     * Resolves the table name for a JoinColumn, with fallback to primary table
     * @return the resolved table name
     */
    public String resolveJoinColumnTable(JoinColumn jc, EntityModel owner) {
        String primary = owner.getTableName();
        if (jc == null || jc.table().isEmpty()) return primary;
        String req = jc.table();
        boolean ok = primary.equals(req) || 
            owner.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(req));
        if (!ok) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
               "JoinColumn.table='" + req + "' is not a primary/secondary table of " + owner.getEntityName()
               + ". Falling back to primary '" + primary + "'.");
            return primary;
        }
        return req;
    }
    

    public Optional<TypeElement> resolveTargetEntity(AttributeDescriptor attr,
                                                      ManyToOne m2o, OneToOne o2o, OneToMany o2m, ManyToMany m2m) {
        // 1) Prefer explicit targetEntity (APT-safe)
        TypeElement explicit = null;
        if (m2o != null) explicit = classValToTypeElement(() -> m2o.targetEntity());
        else if (o2o != null) explicit = classValToTypeElement(() -> o2o.targetEntity());
        else if (o2m != null) explicit = classValToTypeElement(() -> o2m.targetEntity());
        else if (m2m != null) explicit = classValToTypeElement(() -> m2m.targetEntity());
        if (explicit != null) return Optional.of(explicit);

        // 2) Use generic argument if collection
        if ((o2m != null) || (m2m != null)) {
            return attr.genericArg(0).map(dt -> (TypeElement) dt.asElement());
        }
        // 3) Infer from field/property type
        return Optional.ofNullable(getReferencedTypeElement(attr.type()));
    }

    /**
     * Safely extracts TypeElement from class value (annotation)
     * Properly handles MirroredTypeException in APT environment
     */
    private TypeElement classValToTypeElement(java.util.function.Supplier<Class<?>> getter) {
        try {
            Class<?> clz = getter.get();
            if (clz == void.class) return null;
            return context.getElementUtils().getTypeElement(clz.getName());
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return getReferencedTypeElement(mte.getTypeMirror());
        } catch (Throwable t) {
            return null;
        }
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
            return (TypeElement) declaredType.asElement();
        }
        return null;
    }

    /**
     * Help method to find a column considering the table name
     * Avoid collisions when primary/secondary tables have the same column name
     */
    public ColumnModel findColumn(EntityModel entity, String tableName, String columnName) {
        return entity.findColumn(tableName, columnName);
    }

    /**
     * Helper method to add a column to the entity model
     * Prevents conflicts by using a key combining table and column name
     */
    public void putColumn(EntityModel entity, ColumnModel column) {
        entity.putColumn(column);
    }

    public boolean allSameConstraintMode(List<JoinColumn> jcs) {
        if (jcs.isEmpty()) return true;
        ConstraintMode first = jcs.get(0).foreignKey().value();
        for (JoinColumn jc : jcs) {
            if (jc.foreignKey().value() != first) return false;
        }
        return true;
    }

    public List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? Collections.emptyList() : Arrays.stream(arr).toList();
    }

    /**
     * Automatically create index on FK columns (for performance optimization)
     * Skip if already covered by PK or UNIQUE constraint
     */
    public void addForeignKeyIndex(EntityModel entity, List<String> fkColumns, String tableName) {
        if (fkColumns == null || fkColumns.isEmpty()) return;
        if (coveredByPkOrUnique(entity, tableName, fkColumns)) return;

        // Maintain index column order as "FK column creation order"
        List<String> colsInOrder = List.copyOf(fkColumns);
        if (hasSameIndex(entity, tableName, colsInOrder)) return;
        String indexName = context.getNaming().ixName(tableName, colsInOrder);
        if (entity.getIndexes().containsKey(indexName)) return;

        IndexModel ix = IndexModel.builder()
                .indexName(indexName).tableName(tableName)
                .columnNames(colsInOrder).build();
        entity.getIndexes().put(indexName, ix);
    }

    /**
     * Check if the FK column set is already covered by PK or UNIQUE constraint
     */
    public boolean coveredByPkOrUnique(EntityModel e, String table, List<String> cols) {
        Set<String> want = new HashSet<>(cols);
        for (ConstraintModel c : e.getConstraints().values()) {
            if (!Objects.equals(table, c.getTableName())) continue;
            if (c.getType() == ConstraintType.PRIMARY_KEY || c.getType() == ConstraintType.UNIQUE) {
                if (new HashSet<>(c.getColumns()).equals(want)) return true;
            }
        }
        return false;
    }

    public boolean hasSameIndex(EntityModel e, String table, List<String> cols) {
        for (IndexModel im : e.getIndexes().values()) {
            if (Objects.equals(table, im.getTableName()) && im.getColumnNames().equals(cols)) return true;
        }
        return false;
    }

    /**
     * Helper method for PK promotion used in @MapsId and similar cases
     * Promotes specified columns to PK and sets related constraints
     */
    public void promoteColumnsToPrimaryKey(EntityModel entity, String table, List<String> cols) {
        // 1) Verify column existence/type and enforce PK/NOT NULL
        for (String col : cols) {
            ColumnModel c = entity.findColumn(table, col);
            if (c == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Missing FK column '" + col + "' for @MapsId promotion on table '" + table + "'.");
                entity.setValid(false);
                return;
            }
            c.setPrimaryKey(true);
            c.setNullable(false);
            if (c.getTableName() == null || c.getTableName().isBlank()) {
                c.setTableName(table);
            }
        }

        // 2) Construct/merge PK constraint
        String pkName = context.getNaming().pkName(table, cols);
        entity.getConstraints().put(pkName, ConstraintModel.builder()
                .name(pkName)
                .type(ConstraintType.PRIMARY_KEY)
                .tableName(table)
                .columns(new ArrayList<>(cols))
                .build());

        // 3) PK naturally supersedes duplicate UNIQUE coverage (harmless if left as-is)
    }
}
