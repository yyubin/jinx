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
import java.util.stream.Collectors;

/**
 * Processor for @ManyToOne and @OneToOne relationships
 */
public final class ToOneRelationshipProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    
    public ToOneRelationshipProcessor(ProcessingContext context) {
        this.context = context;
        this.support = new RelationshipSupport(context);
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
        
        // ManyToOne is always on the owning side (no mapped By)
        if (manyToOne != null) {
            return true;
        }
        
        // OneToOne is only when there is no mapped By  (owning side)
        if (oneToOne != null && oneToOne.mappedBy().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public void process(AttributeDescriptor descriptor, EntityModel ownerEntity) {
        ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);

        // Inverse side check unnecessary - already filtered to owning side only in supports()

        Optional<TypeElement> referencedTypeElementOpt = support.resolveTargetEntity(descriptor, manyToOne, oneToOne, null, null);
        if (referencedTypeElementOpt.isEmpty()) return;
        TypeElement referencedTypeElement = referencedTypeElementOpt.get();

        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) {
            // Defer processing if referenced entity has not been processed yet
            // Handles cases where entities are processed regardless of dependency order
            context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Deferring FK generation for @" + (manyToOne != null ? "ManyToOne" : "OneToOne") +
                    " relationship to '" + referencedTypeElement.getQualifiedName() +
                    "' (referenced entity not yet processed). Will retry in deferred pass.",
                    descriptor.elementForDiagnostics());

            String ownerEntityName = ownerEntity.getEntityName();
            if (!context.getDeferredNames().contains(ownerEntityName)) {
                context.getDeferredEntities().offer(ownerEntity);
                context.getDeferredNames().add(ownerEntityName);
            }
            return;
        }

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key to be referenced.", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));

        JoinColumns joinColumnsAnno = descriptor.getAnnotation(JoinColumns.class);
        JoinColumn joinColumnAnno = descriptor.getAnnotation(JoinColumn.class);

        List<JoinColumn> joinColumns = joinColumnsAnno != null ? Arrays.asList(joinColumnsAnno.value()) :
                (joinColumnAnno != null ? List.of(joinColumnAnno) : Collections.emptyList());

        // Validate composite key
        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on " + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (!joinColumns.isEmpty() && joinColumns.size() != refPkList.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + descriptor.name() +
                            ". Expected " + refPkList.size() + " (from referenced PK), but got " + joinColumns.size() + ".", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // Check if this relationship has already been processed (deferred retry scenario)
        RelationshipModel existingRelationship = ownerEntity.getRelationships().values().stream()
                .filter(rel -> descriptor.name().equals(rel.getSourceAttributeName()))
                .findFirst()
                .orElse(null);

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNames = new ArrayList<>();
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        String mapsIdAttr = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : null;

        // If relationship already exists, skip FK creation but still check for UNIQUE constraint (OneToOne)
        if (existingRelationship != null) {
            // Extract FK column names from existing relationship for UNIQUE constraint check
            fkColumnNames = new ArrayList<>(existingRelationship.getColumns());
            String fkBaseTable = existingRelationship.getTableName();

            // Jump to UNIQUE constraint check for OneToOne
            // OneToOne relationship must logically always be UNIQUE, so add regardless of @JoinColumn.unique value
            if (oneToOne != null && mapsId == null && fkColumnNames.size() == 1) {
                if (!support.coveredByPkOrUnique(ownerEntity, fkBaseTable, fkColumnNames)) {
                    String uqName = context.getNaming().uqName(fkBaseTable, fkColumnNames);
                    if (!ownerEntity.getConstraints().containsKey(uqName)) {
                        ownerEntity.getConstraints().put(uqName, ConstraintModel.builder()
                            .name(uqName).type(ConstraintType.UNIQUE)
                            .tableName(fkBaseTable).columns(new ArrayList<>(fkColumnNames)).build());
                    }
                }
            }

            // FK index check (may have been skipped in previous pass)
            support.addForeignKeyIndex(ownerEntity, fkColumnNames, fkBaseTable);
            return; // Already processed, skip FK and relationship creation
        }

        // Validate that all JoinColumns use the same table for composite keys
        if (joinColumns.size() > 1) {
            String firstTable = support.resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            for (int i = 1; i < joinColumns.size(); i++) {
                String currentTable = support.resolveJoinColumnTable(joinColumns.get(i), ownerEntity);
                if (!firstTable.equals(currentTable)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn annotations in composite key must target the same table. Found: '" + firstTable + "' and '" + currentTable + "'.", descriptor.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        // loop1: Pre-validation
        for (int i = 0; i < refPkList.size(); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            String referencedPkName = (jc != null && !jc.referencedColumnName().isEmpty())
                    ? jc.referencedColumnName() : refPkList.get(i).getColumnName();

            ColumnModel referencedPkColumn = refPkMap.get(referencedPkName);
            if (referencedPkColumn == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Referenced column name '" + referencedPkName + "' not found in primary keys of "
                                + referencedEntity.getEntityName(), descriptor.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            String fieldName = descriptor.name();
            // FK naming for regular entities: based on attribute name (fieldName + referencedPK)
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                    ? jc.name()
                    : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

            // Validate duplicate FK names
            if (fkColumnNames.contains(fkColumnName) || toAdd.containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkColumnName + "' in "
                                + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            // PK promotion is performed in post-processing (@MapsId) phase. Only determine nullability here.
            // Nullability rule: For ToOne relationships, consider both optional and JoinColumn.nullable()
            // - If optional=false, always NOT NULL (ignores JoinColumn.nullable=true)
            // - If optional=true, follows JoinColumn.nullable() value (default: true)
            boolean associationOptional =
                    (manyToOne != null) ? manyToOne.optional() : oneToOne.optional();
            boolean columnNullableFromAnno =
                    (jc != null) ? jc.nullable() : associationOptional;
            boolean isNullable = associationOptional && columnNullableFromAnno;

            if (!associationOptional && jc != null && jc.nullable()) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@JoinColumn(nullable=true) conflicts with optional=false; treating as NOT NULL.", descriptor.elementForDiagnostics());
            }

            // Even columns that will become PK via @MapsId may initially be created with isNullable=true here.
            // Final nullability is enforced to isNullable=false when promoted to PK in processMapsIdAttributes.
            if (!associationOptional) {
                isNullable = false;
            }

            String tableNameForFk = support.resolveJoinColumnTable(jc, ownerEntity);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .tableName(tableNameForFk)
                    .javaType(referencedPkColumn.getJavaType())
                    .isPrimaryKey(false) // PK promotion happens in MapsId post-processing phase
                    .isNullable(isNullable)
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = ownerEntity.findColumn(tableNameForFk, fkColumnName);
            if (existing != null) {
                if (existing.getJavaType() == null) {
                    existing.setJavaType(fkColumn.getJavaType());
                }
                if (!Objects.equals(existing.getJavaType(), fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for column '" + fkColumnName + "' in table '" + tableNameForFk + "' on " + ownerEntity.getEntityName() +
                                    ". Foreign key requires type " + fkColumn.getJavaType() +
                                    " but existing column has type " + existing.getJavaType(), descriptor.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
                // If types match, apply relationship constraints (nullable) to existing column
                // PK promotion is deferred
                if (existing.isNullable() != isNullable) {
                    existing.setNullable(isNullable);
                }
            }

            // Defer new column addition (table-aware check)
            if (existing == null) {
                toAdd.put(fkColumnName, fkColumn);
            }
            fkColumnNames.add(fkColumnName);
            referencedPkNames.add(referencedPkName);
        }

        // loop2: Apply (using helper method)
        for (ColumnModel col : toAdd.values()) {
            ownerEntity.putColumn(col);
        }

        // Validate and determine FK constraint name
        String explicitFkName = null;
        for (JoinColumn jc : joinColumns) {
            String n = jc.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (explicitFkName == null) {
                    explicitFkName = n;
                } else if (!explicitFkName.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + n + "'.", descriptor.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        // FK constraint name generation based on the table where FK actually resides
        String fkBaseTable = joinColumns.isEmpty()
            ? ownerEntity.getTableName()
            : support.resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            
        String relationConstraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!support.allSameConstraintMode(joinColumns)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All @JoinColumn.foreignKey.value must be identical for composite FK on " 
                    + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }
        
        // Check for @ForeignKey(NO_CONSTRAINT) 
        boolean noConstraint = !joinColumns.isEmpty() &&
                joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        // Create relationship model
        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .tableName(fkBaseTable) // Table where FK resides (including secondary tables)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNames)
                .mapsId(mapsId != null)
                .mapsIdKeyPath(mapsIdAttr) // Store @MapsId.value()
                .noConstraint(noConstraint)
                .constraintName(relationConstraintName)
                .sourceAttributeName(descriptor.name())
                .cascadeTypes(manyToOne != null ? support.toCascadeList(manyToOne.cascade()) : support.toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();

        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);

        // Add UNIQUE constraint for 1:1 (single FK) without @MapsId
        // OneToOne relationship must logically always be UNIQUE, so add regardless of @JoinColumn.unique value
        boolean isSingleFk = fkColumnNames.size() == 1;
        boolean shouldAddUnique = (oneToOne != null) && (mapsId == null) && isSingleFk;
        if (shouldAddUnique && !support.coveredByPkOrUnique(ownerEntity, fkBaseTable, fkColumnNames)) {
            String uqName = context.getNaming().uqName(fkBaseTable, fkColumnNames);
            if (!ownerEntity.getConstraints().containsKey(uqName)) {
                ownerEntity.getConstraints().put(uqName, ConstraintModel.builder()
                    .name(uqName).type(ConstraintType.UNIQUE)
                    .tableName(fkBaseTable).columns(new ArrayList<>(fkColumnNames)).build());
            }
        }

        // Automatically create index on FK columns (for performance optimization)
        support.addForeignKeyIndex(ownerEntity, fkColumnNames, fkBaseTable);
    }

}