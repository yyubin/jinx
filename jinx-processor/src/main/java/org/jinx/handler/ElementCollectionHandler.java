package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.util.ColumnBuilderFactory;
import org.jinx.util.ColumnUtils;
import org.jinx.util.ConstraintShapes;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Handles the processing of {@code @ElementCollection} annotations.
 *
 * <p>This handler is responsible for creating a separate collection table for a persistent
 * collection of basic types or embeddable classes. It follows a two-phase
 * validate-then-commit pattern to ensure that the collection table and its components
 * (foreign keys, primary keys, value columns, map key columns, order columns) are only
 * added to the schema model if all configurations are valid.
 */
public class ElementCollectionHandler {

    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final Types typeUtils;
    private final Elements elementUtils;

    /**
     * Inner class to hold validation results for the two-phase validate-then-commit pattern.
     */
    private static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<ColumnModel> pendingColumns = new ArrayList<>();
        private final List<RelationshipModel> pendingRelationships = new ArrayList<>();
        private final List<IndexModel> pendingIndexes = new ArrayList<>();
        private final List<ConstraintModel> pendingConstraints = new ArrayList<>();
        private EntityModel collectionEntity;
        private boolean committed = false; // Flag to prevent re-entry

        public void addError(String error) {
            errors.add(error);
        }

        public void addColumn(ColumnModel column) {
            pendingColumns.add(column);
        }

        public void addRelationship(RelationshipModel relationship) {
            pendingRelationships.add(relationship);
        }
        
        public void addIndex(IndexModel index) {
            pendingIndexes.add(index);
        }
        
        public void addConstraint(ConstraintModel constraint) {
            pendingConstraints.add(constraint);
        }

        public void setCollectionEntity(EntityModel entity) {
            this.collectionEntity = entity;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean isCommitted() {
            return committed;
        }

        /**
         * Validates for duplicate column names, checking for conflicts between FK, Map Key, Value, and Order columns,
         * as well as with existing columns.
         */
        private void validateColumnNameDuplicates() {
            Set<String> columnNames = new HashSet<>();
            
            // Also includes conflicts with columns already present in the collectionEntity (e.g., from Embeddable paths).
            if (collectionEntity != null && collectionEntity.getColumns() != null) {
                for (ColumnModel existing : collectionEntity.getColumns().values()) {
                    if (existing.getColumnName() != null) {
                        String normalizedName = existing.getColumnName().trim().toLowerCase(java.util.Locale.ROOT);
                        columnNames.add(normalizedName);
                    }
                }
            }
            
            // Validate pending columns.
            for (ColumnModel column : pendingColumns) {
                String columnName = column.getColumnName();
                if (columnName == null || columnName.isBlank()) {
                    addError("Column name cannot be null/blank in collection table: " + 
                            (collectionEntity != null ? collectionEntity.getTableName() : "unknown"));
                    continue;
                }
                
                String normalizedName = columnName.trim().toLowerCase(java.util.Locale.ROOT);
                if (!columnNames.add(normalizedName)) {
                    addError("Duplicate column name in collection table: " + columnName);
                }
            }
        }

        /**
         * Validates for duplicate constraint names, checking for conflicts with relationship (FK) constraints
         * and existing constraints.
         */
        private void validateConstraintNameDuplicates() {
            Set<String> constraintNames = new HashSet<>();
            
            // Also includes conflicts with relationships already present in the collectionEntity (e.g., from Embeddable or other paths).
            if (collectionEntity != null && collectionEntity.getRelationships() != null) {
                for (RelationshipModel existing : collectionEntity.getRelationships().values()) {
                    if (existing.getConstraintName() != null && !existing.getConstraintName().trim().isEmpty()) {
                        String normalizedName = existing.getConstraintName().trim().toLowerCase(java.util.Locale.ROOT);
                        constraintNames.add(normalizedName);
                    }
                }
            }
            
            // Validate pending relationships.
            for (RelationshipModel relationship : pendingRelationships) {
                String constraintName = relationship.getConstraintName();
                if (constraintName == null || constraintName.trim().isEmpty()) {
                    addError("Constraint name cannot be null or empty for relationship: " + relationship.getTableName());
                    continue;
                }
                
                // Case-insensitive duplicate check (databases are often case-insensitive).
                String normalizedName = constraintName.trim().toLowerCase(java.util.Locale.ROOT);
                if (!constraintNames.add(normalizedName)) {
                    addError("Duplicate constraint name in collection table: " + constraintName);
                }
            }
        }

        /**
         * Performs final duplication validation (called at the end of the validation phase).
         */
        public void performFinalValidation() {
            validateColumnNameDuplicates();
            validateConstraintNameDuplicates();
            validateIndexAndConstraintDuplicates();
            validateIndexAndConstraintColumnsExist();
        }

        /**
         * Validates for duplicate indexes/constraints, checking for both name and semantic duplication.
         */
        private void validateIndexAndConstraintDuplicates() {
            // Name duplication check (case-insensitive).
            Set<String> names = new HashSet<>();
            
            // Collect existing index/constraint names.
            if (collectionEntity != null) {
                if (collectionEntity.getIndexes() != null) {
                    for (IndexModel existing : collectionEntity.getIndexes().values()) {
                        if (existing.getIndexName() != null) {
                            names.add(existing.getIndexName().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
                if (collectionEntity.getConstraints() != null) {
                    for (ConstraintModel existing : collectionEntity.getConstraints().values()) {
                        if (existing.getName() != null) {
                            names.add(existing.getName().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            
            // Validate pending index name duplicates.
            for (IndexModel ix : pendingIndexes) {
                String k = ix.getIndexName().toLowerCase(java.util.Locale.ROOT);
                if (!names.add(k)) {
                    addError("Duplicate index name on collection table: " + ix.getIndexName());
                }
            }
            
            // Validate pending constraint name duplicates.
            for (ConstraintModel c : pendingConstraints) {
                String k = c.getName().toLowerCase(java.util.Locale.ROOT);
                if (!names.add(k)) {
                    addError("Duplicate constraint name on collection table: " + c.getName());
                }
            }
            
            // Semantic duplication check (same table + column set + type).
            Set<String> shapes = new HashSet<>();

            if (collectionEntity != null && collectionEntity.getConstraints() != null) {
                for (ConstraintModel existing : collectionEntity.getConstraints().values()) {
                    shapes.add(ConstraintShapes.shapeKey(existing));
                }
            }
            for (ConstraintModel c : pendingConstraints) {
                if (!shapes.add(ConstraintShapes.shapeKey(c))) {
                    addError("Duplicate constraint definition: " + c.getName());
                }
            }
            if (collectionEntity != null && collectionEntity.getIndexes() != null) {
                for (IndexModel existing : collectionEntity.getIndexes().values()) {
                    shapes.add(ConstraintShapes.shapeKey(existing));
                }
            }
            for (IndexModel ix : pendingIndexes) {
                if (!shapes.add(ConstraintShapes.shapeKey(ix))) {
                    addError("Duplicate index definition: " + ix.getIndexName());
                }
            }
        }

        /**
         * Validates that columns referenced by indexes/constraints exist.
         */
        private void validateIndexAndConstraintColumnsExist() {
            Set<String> cols = new HashSet<>();
            
            // Collect pending columns.
            for (ColumnModel cm : pendingColumns) {
                if (cm.getColumnName() != null) {
                    cols.add(cm.getColumnName().toLowerCase(java.util.Locale.ROOT));
                }
            }
            
            // Also include existing columns (e.g., pre-applied from embeddables).
            if (collectionEntity != null && collectionEntity.getColumns() != null) {
                for (ColumnModel cm : collectionEntity.getColumns().values()) {
                    if (cm.getColumnName() != null) {
                        cols.add(cm.getColumnName().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            
            // Validate columns referenced by indexes.
            for (IndexModel ix : pendingIndexes) {
                for (String colName : ix.getColumnNames()) {
                    if (!cols.contains(colName.toLowerCase(java.util.Locale.ROOT))) {
                        addError("Index '" + ix.getIndexName() + "' refers to unknown column: " + colName);
                    }
                }
            }
            
            // Validate columns referenced by constraints.
            for (ConstraintModel c : pendingConstraints) {
                for (String colName : c.getColumns()) {
                    if (!cols.contains(colName.toLowerCase(java.util.Locale.ROOT))) {
                        addError("Constraint '" + c.getName() + "' refers to unknown column: " + colName);
                    }
                }
            }
        }

        /**
         * Pure commit - applies changes to the model without exceptions (prevents re-entry).
         */
        public void commitToModel() {
            // Re-entry prevention: ignore if already committed.
            if (committed) {
                return;
            }
            
            // No exceptions, as this is only called in a validated state.
            // Commit in the order: columns -> indexes -> constraints -> relationships.
            
            // Add all columns in bulk.
            for (ColumnModel column : pendingColumns) {
                collectionEntity.putColumn(column);
            }
            
            // Add all indexes in bulk.
            for (IndexModel ix : pendingIndexes) {
                collectionEntity.getIndexes().put(ix.getIndexName(), ix);
            }
            
            // Add all constraints in bulk.
            for (ConstraintModel c : pendingConstraints) {
                String key = ConstraintShapes.shapeKey(c);
                collectionEntity.getConstraints().put(key, c);
            }

            // Add all relationships in bulk.
            for (RelationshipModel relationship : pendingRelationships) {
                collectionEntity.getRelationships().put(relationship.getConstraintName(), relationship);
            }
            
            // Set the commit completion flag.
            committed = true;
        }
    }

    /**
     * A synthetic AttributeDescriptor implementation based on a TypeMirror.
     * Used to pass @Embeddable value types to the EmbeddedHandler.
     */
    private static class SyntheticTypeAttributeDescriptor implements AttributeDescriptor {
        private final String name;
        private final TypeMirror type;
        private final Element elementForDiagnostics;

        SyntheticTypeAttributeDescriptor(String name, TypeMirror type, Element elementForDiagnostics) {
            this.name = name;
            this.type = type;
            this.elementForDiagnostics = elementForDiagnostics;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TypeMirror type() {
            return type;
        }

        @Override
        public boolean isCollection() {
            return false;
        }

        @Override
        public java.util.Optional<DeclaredType> genericArg(int idx) {
            return java.util.Optional.empty();
        }

        @Override
        public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationClass) {
            return null;
        }

        @Override
        public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> annotationClass) {
            return false;
        }

        @Override
        public Element elementForDiagnostics() {
            return elementForDiagnostics;
        }

        @Override
        public AccessKind accessKind() {
            return AccessKind.FIELD;
        }
    }

    public ElementCollectionHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
    }

    public void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        // Delegate to AttributeDescriptor-based method
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processElementCollection(fieldDescriptor, ownerEntity);
    }

    /**
     * Main entry point for processing an {@code @ElementCollection} attribute.
     *
     * @param attribute The descriptor for the attribute annotated with @ElementCollection.
     * @param ownerEntity The model of the entity that owns the collection.
     */
    public void processElementCollection(AttributeDescriptor attribute, EntityModel ownerEntity) {
        // ========== Phase 1: Validation (in-memory creation only) ==========
        ValidationResult validation = validateElementCollection(attribute, ownerEntity);

        // ========== Phase 2: Commit (only if there are no errors) ==========
        if (validation.hasErrors()) {
            // Print all validation errors as APT messages.
            for (String error : validation.getErrors()) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR, error, attribute.elementForDiagnostics());
            }
            return; // Full rollback without partial creation.
        }

        // Validation successful: commit to the model in bulk (no exceptions, pure commit).
        validation.commitToModel();
        
        // Register the completed collection table model with the schema.
        context.getSchemaModel().getEntities().putIfAbsent(
            validation.collectionEntity.getEntityName(), 
            validation.collectionEntity
        );
    }

    /**
     * Phase 1: Validation - Creates and validates all collection components in memory only.
     */
    private ValidationResult validateElementCollection(AttributeDescriptor attribute, EntityModel ownerEntity) {
        ValidationResult result = new ValidationResult();

        // 0. Basic null/blank validation to prevent NPEs.
        if (ownerEntity.getTableName() == null || ownerEntity.getTableName().isBlank()) {
            result.addError("Owner entity has no tableName; cannot derive collection table name for @ElementCollection on " + attribute.name());
            return result;
        }
        if (attribute.name() == null || attribute.name().isBlank()) {
            result.addError("Attribute name is blank for @ElementCollection");
            return result;
        }

        // 1. Determine the collection table name and create a new EntityModel.
        String defaultTableName = ownerEntity.getTableName() + "_" + attribute.name();

        CollectionTable collectionTable = attribute.getAnnotation(CollectionTable.class);
        String tableName = (collectionTable != null && !collectionTable.name().isEmpty())
                ? collectionTable.name()
                : defaultTableName;

        EntityModel collectionEntity = EntityModel.builder()
                .entityName(tableName) // Use table name as a unique identifier
                .tableName(tableName)
                .tableType(EntityModel.TableType.COLLECTION_TABLE)
                .build();
        result.setCollectionEntity(collectionEntity);

        // 1-1. Apply indexes/uniqueConstraints from @CollectionTable.
        if (collectionTable != null) {
            var adapter = new org.jinx.handler.builtins.CollectionTableAdapter(collectionTable, context, tableName);
            
            for (var ix : adapter.getIndexes()) {
                result.addIndex(ix);
            }
            for (var c : adapter.getConstraints()) {
                result.addConstraint(c);
            }
        }

        // 2. Analyze the collection's generic type (Map vs. Collection) - validation before FK creation.
        TypeMirror attributeType = attribute.type();
        if (!(attributeType instanceof DeclaredType declaredType)) {
            result.addError("Cannot determine collection type for @ElementCollection");
            return result;
        }

        boolean isMap = context.isSubtype(declaredType, "java.util.Map");
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        TypeMirror keyType = null;
        TypeMirror valueType = null;

        if (isMap && typeArguments.size() == 2) {
            keyType = typeArguments.get(0);
            valueType = typeArguments.get(1);
        } else if (!isMap && typeArguments.size() == 1) {
            valueType = typeArguments.get(0);
        } else {
            result.addError("Cannot determine collection element type for @ElementCollection");
            return result;
        }

        // 3. Collect the owner entity's PK information.
        List<ColumnModel> ownerPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkColumns.isEmpty()) {
            result.addError("Owner entity " + ownerEntity.getEntityName() + " must have a primary key for @ElementCollection");
            return result;
        }

        // 4. Configure joinColumns from @CollectionTable or create FK columns with default values.
        JoinColumn[] joinColumns = (collectionTable != null) ? collectionTable.joinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPkColumns.size()) {
            result.addError("@CollectionTable joinColumns size mismatch: expected " + ownerPkColumns.size() 
                + " but got " + joinColumns.length + " on " + ownerEntity.getEntityName() + "." + attribute.name());
            return result;
        }

        // Create FK columns (prioritizing referencedColumnName mapping) - in-memory only.
        java.util.List<String> fkColumnNames = new java.util.ArrayList<>();
        java.util.Map<String, ColumnModel> ownerPkByName = new java.util.HashMap<>();
        for (ColumnModel pk : ownerPkColumns) ownerPkByName.put(pk.getColumnName(), pk);
        for (int i = 0; i < ownerPkColumns.size(); i++) {
            JoinColumn jc = (joinColumns.length > 0) ? joinColumns[i] : null;
            ColumnModel ownerPkCol;
            if (jc != null && !jc.referencedColumnName().isEmpty()) {
                ownerPkCol = ownerPkByName.get(jc.referencedColumnName());
                if (ownerPkCol == null) {
                    result.addError("referencedColumnName not found in owner PKs: " + jc.referencedColumnName());
                    return result;
                }
            } else {
                ownerPkCol = ownerPkColumns.get(i);
            }
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                ? jc.name()
                : context.getNaming().foreignKeyColumnName(ownerEntity.getTableName(), ownerPkCol.getColumnName());
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .tableName(tableName)
                    .javaType(ownerPkCol.getJavaType())
                    .isPrimaryKey(true) // In a collection table, the FK is part of the PK
                    .isNullable(false)
                    .build();
            result.addColumn(fkColumn); // Add to validation results instead of calling putColumn immediately
            fkColumnNames.add(fkColumnName);
        }

        // 5. Process Map Key columns - in-memory only.
        if (isMap) {
            // The key type can be overridden by @MapKeyClass or @MapKey.
            TypeMirror actualKeyType = resolveMapKeyType(attribute, keyType);
            
            if (actualKeyType != null) {
                // Check if the key type is an entity.
                Element keyElement = context.getTypeUtils().asElement(actualKeyType);
                boolean isEntityKey = keyElement != null && keyElement.getAnnotation(Entity.class) != null;
                
                if (isEntityKey) {
                    // Process entity keys with @MapKeyJoinColumn(s).
                    processMapKeyJoinColumns(attribute, actualKeyType, tableName, result);
                } else {
                    // Process basic type keys (@MapKeyColumn).
                    MapKeyColumn mapKeyColumn = attribute.getAnnotation(MapKeyColumn.class);
                    String mapKeyColumnName = (mapKeyColumn != null && !mapKeyColumn.name().isEmpty())
                            ? mapKeyColumn.name()
                            : attribute.name() + "_KEY";

                    ColumnModel keyColumn = ColumnBuilderFactory.fromType(actualKeyType, mapKeyColumnName, tableName);
                    if (keyColumn != null) {
                        keyColumn.setPrimaryKey(true);
                        keyColumn.setMapKey(true);
                        keyColumn.setNullable(false);

                        // Apply @MapKeyColumn metadata (PK rules take precedence).
                        if (mapKeyColumn != null) {
                            keyColumn.setNullable(false); // PK is always NOT NULL (ignore nullable request).
                            if (mapKeyColumn.length() != 255) { // Only if it's not the default value.
                                keyColumn.setLength(mapKeyColumn.length());
                            }
                            if (mapKeyColumn.precision() != 0) {
                                keyColumn.setPrecision(mapKeyColumn.precision());
                            }
                            if (mapKeyColumn.scale() != 0) {
                                keyColumn.setScale(mapKeyColumn.scale());
                            }
                            if (!mapKeyColumn.columnDefinition().isEmpty()) {
                                keyColumn.setSqlTypeOverride(mapKeyColumn.columnDefinition());
                            }
                        }

                        // Process special Map Key annotations.
                        processMapKeyAnnotations(attribute, keyColumn, actualKeyType);

                        result.addColumn(keyColumn); // Add to validation results instead of calling putColumn immediately
                    }
                }
            }
        }

        // 6. Process Element (Value) columns - in-memory only.
        boolean isList = context.isSubtype(declaredType, "java.util.List");
        
        Element valueElement = context.getTypeUtils().asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // If the value is an Embeddable type - currently added directly to the temporary EntityModel.
            // TODO: After changing EmbeddedHandler to a two-phase pattern, validation results need to be integrated.
            AttributeDescriptor valueAttribute = new SyntheticTypeAttributeDescriptor(
                attribute.name() + "_value",   // Appropriate name for diagnostics/logging.
                valueType,                     // The actual value type, such as DeclaredType.
                attribute.elementForDiagnostics()
            );
            embeddedHandler.processEmbedded(valueAttribute, collectionEntity, new HashSet<>());
            // TODO: PK promotion for Embeddable fields needs to be handled (for Sets).
        } else {
            // If the value is a basic type.
            Column columnAnnotation = attribute.getAnnotation(Column.class);
            String elementColumnName = (columnAnnotation != null && !columnAnnotation.name().isEmpty())
                    ? columnAnnotation.name()
                    : attribute.name();

            ColumnModel elementColumn = ColumnBuilderFactory.fromType(valueType, elementColumnName, tableName);
            if (elementColumn != null) {
                boolean isSetPk = !isList && !isMap; // The value in a Set/Collection is part of the PK.
                elementColumn.setPrimaryKey(isSetPk);
                if (isSetPk) elementColumn.setNullable(false);
                result.addColumn(elementColumn); // Add to validation results instead of calling putColumn immediately
            }
        }

        // 7. Process @OrderColumn (for Lists) - in-memory only.
        if (isList) {
            OrderColumn orderColumn = attribute.getAnnotation(OrderColumn.class);
            if (orderColumn != null) {
                String orderColumnName = orderColumn.name().isEmpty() 
                    ? attribute.name() + "_ORDER" 
                    : orderColumn.name();
                ColumnModel orderCol = ColumnModel.builder()
                        .columnName(orderColumnName)
                        .tableName(tableName)
                        .javaType("java.lang.Integer")
                        .isPrimaryKey(true)            // (owner PK + order) = PK
                        .isNullable(false)             // PK cannot be NULL.
                        .build();
                result.addColumn(orderCol); // Add to validation results instead of calling putColumn immediately
            }
        }

        // 8. Create foreign key relationship model - in-memory only.
        List<String> ownerPkNames = ownerPkColumns.stream().map(ColumnModel::getColumnName).toList();

        RelationshipModel fkRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)
                .tableName(tableName)
                .columns(fkColumnNames)
                .referencedTable(ownerEntity.getTableName())
                .referencedColumns(ownerPkNames)
                .constraintName(context.getNaming().fkName(tableName, fkColumnNames, ownerEntity.getTableName(), ownerPkNames))
                .build();
        result.addRelationship(fkRelationship);

        // 9. Final duplication validation (including pre-applied columns/relationships from embeddables).
        result.performFinalValidation();
        
        return result;
    }

    /**
     * Processes @MapKeyJoinColumn(s) when the map key is an entity.
     */
    private void processMapKeyJoinColumns(AttributeDescriptor attribute, TypeMirror keyType, 
                                        String tableName, ValidationResult result) {
        // Look up the key entity's PK columns.
        Element keyElement = context.getTypeUtils().asElement(keyType);
        if (keyElement == null || !(keyElement instanceof TypeElement keyTypeElement)) {
            result.addError("Cannot resolve key entity type for @MapKeyJoinColumn");
            return;
        }

        // Find the key entity's EntityModel in the schema and collect its PK columns (using FQCN).
        String keyFqcn = keyTypeElement.getQualifiedName().toString();
        EntityModel keyEntity = context.getSchemaModel().getEntities().get(keyFqcn);
        if (keyEntity == null) {
            result.addError("Key entity " + keyFqcn + " not found in schema for @MapKeyJoinColumn");
            return;
        }

        List<ColumnModel> keyPkColumns = context.findAllPrimaryKeyColumns(keyEntity);
        if (keyPkColumns.isEmpty()) {
            result.addError("Key entity " + keyTypeElement.getSimpleName() + " has no primary key for @MapKeyJoinColumn");
            return;
        }

        // Process @MapKeyJoinColumn(s) annotations.
        MapKeyJoinColumn[] mapKeyJoinColumns = getMapKeyJoinColumns(attribute);
        
        if (mapKeyJoinColumns.length > 0 && mapKeyJoinColumns.length != keyPkColumns.size()) {
            result.addError("@MapKeyJoinColumn size mismatch: expected " + keyPkColumns.size() 
                + " but got " + mapKeyJoinColumns.length + " for key entity " + keyTypeElement.getSimpleName());
            return;
        }

        // Create key FK columns (collecting names at the same time).
        Map<String, ColumnModel> keyPkByName = new HashMap<>();
        for (ColumnModel pk : keyPkColumns) {
            keyPkByName.put(pk.getColumnName(), pk);
        }

        List<String> keyFkColumnNames = new ArrayList<>();
        Set<String> usedReferencedColumns = new HashSet<>();
        for (int i = 0; i < keyPkColumns.size(); i++) {
            MapKeyJoinColumn mkjc = (mapKeyJoinColumns.length > 0) ? mapKeyJoinColumns[i] : null;
            ColumnModel keyPkCol;
            
            if (mkjc != null && !mkjc.referencedColumnName().isEmpty()) {
                keyPkCol = keyPkByName.get(mkjc.referencedColumnName());
                if (keyPkCol == null) {
                    result.addError("MapKeyJoinColumn.referencedColumnName not found in key entity PKs: " + mkjc.referencedColumnName());
                    return;
                }
                // Detect duplicate referencedColumnName.
                if (!usedReferencedColumns.add(keyPkCol.getColumnName())) {
                    result.addError("Duplicate MapKeyJoinColumn.referencedColumnName: " + keyPkCol.getColumnName());
                    return;
                }
            } else {
                keyPkCol = keyPkColumns.get(i);
                // Prevent duplicates in default mapping as well.
                if (!usedReferencedColumns.add(keyPkCol.getColumnName())) {
                    result.addError("Duplicate key PK column reference: " + keyPkCol.getColumnName());
                    return;
                }
            }

            String keyFkColumnName = (mkjc != null && !mkjc.name().isEmpty())
                ? mkjc.name()
                : context.getNaming().foreignKeyColumnName(attribute.name() + "_KEY", keyPkCol.getColumnName());

            ColumnModel keyFkColumn = ColumnModel.builder()
                    .columnName(keyFkColumnName)
                    .tableName(tableName)
                    .javaType(keyPkCol.getJavaType())
                    .isPrimaryKey(true)  // The Map key FK is part of the collection table's PK
                    .isNullable(false)
                    .isMapKey(true)
                    .build();
            
            result.addColumn(keyFkColumn);
            keyFkColumnNames.add(keyFkColumnName);  // Immediately add the created name to the list.
        }

        List<String> keyPkColumnNames = keyPkColumns.stream().map(ColumnModel::getColumnName).toList();
        RelationshipModel keyRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)  // The Map key FK is also part of the ElementCollection.
                .tableName(tableName)
                .columns(keyFkColumnNames)
                .referencedTable(keyEntity.getTableName())
                .referencedColumns(keyPkColumnNames)
                .constraintName(context.getNaming().fkName(tableName, keyFkColumnNames, 
                    keyEntity.getTableName(), keyPkColumnNames))
                .build();
        
        result.addRelationship(keyRelationship);
    }

    /**
     * Determines the actual Map key type, considering @MapKeyClass and @MapKey annotations.
     */
    private TypeMirror resolveMapKeyType(AttributeDescriptor attribute, TypeMirror defaultKeyType) {
        // 1) @MapKeyClass has higher priority.
        MapKeyClass mapKeyClass = attribute.getAnnotation(MapKeyClass.class);
        if (mapKeyClass != null) {
            TypeElement keyClassElement = classValToTypeElement(() -> mapKeyClass.value());
            if (keyClassElement != null) {
                return keyClassElement.asType();
            }
        }
        
        // 2) @MapKey requires special handling (specifies the key field name).
        MapKey mapKey = attribute.getAnnotation(MapKey.class);
        if (mapKey != null && !mapKey.name().isEmpty()) {
            // @MapKey(name="fieldName") uses a specific field of the value entity as the key.
            // Only type information is needed here, so return defaultKeyType.
            // The actual column name is handled in processMapKeyAnnotations.
            return defaultKeyType;
        }
        
        // 3) Use the default value (generic type argument).
        return defaultKeyType;
    }
    
    /**
     * Processes Map Key related annotations to configure the ColumnModel.
     */
    private void processMapKeyAnnotations(AttributeDescriptor attribute, ColumnModel keyColumn, TypeMirror keyType) {
        // Process @MapKey - specifies the key field name.
        MapKey mapKey = attribute.getAnnotation(MapKey.class);
        if (mapKey != null && !mapKey.name().isEmpty()) {
            // For @MapKey(name="fieldName"), reset the column name to the specified field name.
            keyColumn.setColumnName(mapKey.name());
        }
        
        // Process @MapKeyEnumerated.
        MapKeyEnumerated mapKeyEnumerated = attribute.getAnnotation(MapKeyEnumerated.class);
        if (mapKeyEnumerated != null) {
            keyColumn.setEnumStringMapping(mapKeyEnumerated.value() == EnumType.STRING);
            if (keyColumn.isEnumStringMapping()) {
                keyColumn.setEnumValues(ColumnUtils.getEnumConstants(keyType));
            }
        }

        // Process @MapKeyTemporal.
        MapKeyTemporal mapKeyTemporal = attribute.getAnnotation(MapKeyTemporal.class);
        if (mapKeyTemporal != null) {
            keyColumn.setTemporalType(mapKeyTemporal.value());
        }
    }

    /**
     * Extracts an array from @MapKeyJoinColumn or @MapKeyJoinColumns annotations.
     */
    private MapKeyJoinColumn[] getMapKeyJoinColumns(AttributeDescriptor attribute) {
        MapKeyJoinColumns mapKeyJoinColumns = attribute.getAnnotation(MapKeyJoinColumns.class);
        if (mapKeyJoinColumns != null) {
            return mapKeyJoinColumns.value();
        }
        
        MapKeyJoinColumn mapKeyJoinColumn = attribute.getAnnotation(MapKeyJoinColumn.class);
        if (mapKeyJoinColumn != null) {
            return new MapKeyJoinColumn[]{mapKeyJoinColumn};
        }
        
        return new MapKeyJoinColumn[0];
    }
    
    /**
     * Safely extracts a TypeElement from a class value in an annotation.
     * Properly handles MirroredTypeException in an APT environment.
     */
    private TypeElement classValToTypeElement(java.util.function.Supplier<Class<?>> getter) {
        try {
            Class<?> clz = getter.get();
            if (clz == void.class) return null;
            return context.getElementUtils().getTypeElement(clz.getName());
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            TypeMirror typeMirror = mte.getTypeMirror();
            if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
                return (TypeElement) declaredType.asElement();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

}
