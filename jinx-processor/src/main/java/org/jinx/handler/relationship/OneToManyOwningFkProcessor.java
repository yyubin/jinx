package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Processor for @OneToMany relationships using Foreign Key strategy (owning side)
 */
public final class OneToManyOwningFkProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    
    public OneToManyOwningFkProcessor(ProcessingContext context, RelationshipSupport support) {
        this.context = context;
        this.support = support;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
        if (oneToMany == null || !oneToMany.mappedBy().isEmpty()) {
            return false; // Not owning side or not OneToMany
        }
        
        // OneToMany FK strategy: must have JoinColumn and no JoinTable
        // If this condition passes, j == null case will not occur in process()
        JoinTable jt = descriptor.getAnnotation(JoinTable.class);
        JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
        JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
        boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);
        
        return hasJoinColumn && jt == null;
    }
    
    @Override
    public void process(AttributeDescriptor attr, EntityModel ownerEntity) {
        OneToMany oneToMany = attr.getAnnotation(OneToMany.class);
        // Validate generic type
        if (!attr.isCollection()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany field must be a collection type. field=" + attr.name(), attr.elementForDiagnostics());
            return;
        }

        Optional<DeclaredType> typeArg = attr.genericArg(0);
        if (typeArg.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve generic type parameter for @OneToMany field. field=" + attr.name(), attr.elementForDiagnostics());
            return;
        }

        // Duplicate Collection check removed - already validated by attr.isCollection()

        Optional<TypeElement> targetEntityElementOpt = support.resolveTargetEntity(attr, null, null, oneToMany, null);
        if (targetEntityElementOpt.isEmpty()) return;
        TypeElement targetEntityElement = targetEntityElementOpt.get();
        EntityModel targetEntityModel = context.getSchemaModel().getEntities()
                .get(targetEntityElement.getQualifiedName().toString());
        if (targetEntityModel == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + ownerEntity.getEntityName() + " must have a primary key for @OneToMany relationship.", attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        JoinColumns jcs = attr.getAnnotation(JoinColumns.class);
        JoinColumn jc = attr.getAnnotation(JoinColumn.class);
        List<JoinColumn> jlist = jcs != null ? Arrays.asList(jcs.value()) :
                (jc != null ? List.of(jc) : Collections.emptyList());

        if (!jlist.isEmpty() && jlist.size() != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + attr.name()
                            + ". Expected " + ownerPks.size() + ", but got " + jlist.size() + ".", attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // Validate that all JoinColumns use the same table for composite keys
        if (jlist.size() > 1) {
            String firstTable = support.resolveJoinColumnTable(jlist.get(0), targetEntityModel);
            for (int i = 1; i < jlist.size(); i++) {
                String currentTable = support.resolveJoinColumnTable(jlist.get(i), targetEntityModel);
                if (!firstTable.equals(currentTable)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn annotations in composite key must target the same table. Found: '" + firstTable + "' and '" + currentTable + "'.", attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkNames = new ArrayList<>();
        List<String> refNames = new ArrayList<>();

        // Prepare: PK name → ColumnModel map
        Map<String, ColumnModel> pkByName = ownerPks.stream()
                .collect(java.util.stream.Collectors.toMap(ColumnModel::getColumnName, c -> c, (a, b) -> a, LinkedHashMap::new));

        Set<String> usedPkNames = new HashSet<>();

        // loop1: Pre-validation with safe referencedColumnName mapping
        for (int i = 0; i < ownerPks.size(); i++) {
            JoinColumn j = jlist.isEmpty() ? null : jlist.get(i);

            // 1) Determine referenced PK (name-based first, fallback to index-based)
            ColumnModel ownerPk;
            String refNameRaw = (j != null && j.referencedColumnName() != null) ? j.referencedColumnName().trim() : "";
            if (!refNameRaw.isEmpty()) {
                ownerPk = pkByName.get(refNameRaw);
                if (ownerPk == null) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "referencedColumnName '" + refNameRaw + "' is not a primary key column of "
                                    + ownerEntity.getTableName(), attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
                if (!usedPkNames.add(refNameRaw)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate referencedColumnName in @JoinColumns: '" + refNameRaw + "'.",
                            attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            } else {
                // Index-based fallback (size match already validated)
                ownerPk = ownerPks.get(i);
                if (!usedPkNames.add(ownerPk.getColumnName())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Ambiguous PK mapping at index " + i + " – primary key column '" +
                                    ownerPk.getColumnName() + "' already bound by another @JoinColumn.",
                            attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }

            String refName = ownerPk.getColumnName();

            // 2) Determine FK column name (explicit name has priority)
            String fkName = (j != null && !j.name().isEmpty())
                    ? j.name()
                    : context.getNaming().foreignKeyColumnName(attr.name(), refName);

            // Validate duplicate FK names
            if (fkNames.contains(fkName) || toAdd.containsKey(fkName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkName + "' in "
                                + targetEntityModel.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
                targetEntityModel.setValid(false);
                return;
            }

            String tableNameForFk = support.resolveJoinColumnTable(j, targetEntityModel);
            // Nullability rule: @OneToMany (FK strategy) follows JoinColumn.nullable() directly
            // Since OneToMany has no 'optional' attribute in JPA spec, FK nullability on the N-side is the actual constraint
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .tableName(tableNameForFk)
                    .javaType(ownerPk.getJavaType())
                    .isNullable(j.nullable())
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = support.findColumn(targetEntityModel, tableNameForFk, fkName);
            if (existing != null) {
                if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for implicit foreign key column '" + fkName + "' in table '" + tableNameForFk + "' on " +
                                    targetEntityModel.getEntityName() + ". Expected type " + fkColumn.getJavaType() +
                                    " but found existing column with type " + existing.getJavaType(), attr.elementForDiagnostics());
                    targetEntityModel.setValid(false);
                    return;
                }
            }

            // Defer new column addition (table-aware check)
            if (existing == null) {
                toAdd.put(fkName, fkColumn);
            }
            fkNames.add(fkName);
            refNames.add(refName);
        }

        // loop2: Apply (using helper method)
        for (ColumnModel col : toAdd.values()) {
            support.putColumn(targetEntityModel, col);
        }

        // Validate and determine FK constraint name
        String explicitFkName = null;
        for (JoinColumn jcn : jlist) {
            String n = jcn.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (explicitFkName == null) {
                    explicitFkName = n;
                } else if (!explicitFkName.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + n + "'.", attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        // FK constraint name generation based on the table where FK actually resides
        String fkBaseTable = jlist.isEmpty()
                ? targetEntityModel.getTableName()
                : support.resolveJoinColumnTable(jlist.get(0), targetEntityModel);

        String constraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkNames,
                        ownerEntity.getTableName(), refNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!support.allSameConstraintMode(jlist)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All @JoinColumn.foreignKey.value must be identical for composite FK on "
                            + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // Check for @ForeignKey(NO_CONSTRAINT)
        boolean noConstraint = !jlist.isEmpty() &&
                jlist.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        // Create relationship model
        // NOTE: Semantic difference between relationship type and actual FK direction
        // - Domain perspective: User(1) -> Orders(N) = ONE_TO_MANY (based on JPA annotation)
        // - Schema perspective: Orders table has user_id FK = MANY_TO_ONE (actual FK direction)
        // Here we preserve domain semantics by recording as ONE_TO_MANY (JPA standard compliance)
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.ONE_TO_MANY)
                .tableName(fkBaseTable) // Table where FK resides (many-side table)
                .columns(fkNames)       // FK columns (located in many-side table)
                .referencedTable(ownerEntity.getTableName())   // Referenced table (one-side)
                .referencedColumns(refNames)                   // Referenced columns (one-side PK)
                .noConstraint(noConstraint)
                .constraintName(constraintName)
                .cascadeTypes(support.toCascadeList(oneToMany.cascade()))
                .orphanRemoval(oneToMany.orphanRemoval())
                .fetchType(oneToMany.fetch())
                .build();

        targetEntityModel.getRelationships().put(rel.getConstraintName(), rel);

        // Handle @JoinColumn(unique=true) - unique FK constraint in OneToMany (effectively OneToOne)
        boolean anyUnique = jlist.stream().anyMatch(JoinColumn::unique);
        if (anyUnique) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "@OneToMany with @JoinColumn(unique=true) effectively becomes a one-to-one relationship. " +
                            "Consider using @OneToOne instead for clearer semantics. Field: " + attr.name(), attr.elementForDiagnostics());

            String uqName = context.getNaming().uqName(fkBaseTable, fkNames);
            if (!targetEntityModel.getConstraints().containsKey(uqName)) {
                targetEntityModel.getConstraints().put(uqName, ConstraintModel.builder()
                        .name(uqName).type(ConstraintType.UNIQUE)
                        .tableName(fkBaseTable).columns(new ArrayList<>(fkNames)).build());
            }
        }

        // Automatically create index on FK columns (for performance optimization)
        support.addForeignKeyIndex(targetEntityModel, fkNames, fkBaseTable);
    }
}