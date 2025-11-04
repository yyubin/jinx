package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.handler.relationship.*;
import org.jinx.model.*;
import org.jinx.util.AccessUtils;

import org.jinx.descriptor.AttributeDescriptor;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Handles the processing of all JPA relationship annotations (@ManyToOne, @OneToOne, @OneToMany, @ManyToMany).
 *
 * <p>This class orchestrates a set of specialized {@link RelationshipProcessor} instances
 * to handle different types of relationships (e.g., owning side, inverse side, join tables).
 * It also includes a deferred processing step for {@code @MapsId} attributes to ensure
 * that all primary key and foreign key information is available.
 *
 * <p>The handler respects the {@code @Access} type of the entity, scanning either fields or
 * getter methods to find relationship annotations.
 */
public class RelationshipHandler {
    private final ProcessingContext context;
    private final List<RelationshipProcessor> processors;
    
    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
        
        // Initialize support classes
        RelationshipSupport relationshipSupport = new RelationshipSupport(context);
        RelationshipJoinSupport joinTableSupport = new RelationshipJoinSupport(context, relationshipSupport);

        
        // Initialize processors in order of precedence
        this.processors = Arrays.asList(
            new InverseRelationshipProcessor(context, relationshipSupport),
            new ToOneRelationshipProcessor(context),
            new OneToManyOwningFkProcessor(context, relationshipSupport),
            new OneToManyOwningJoinTableProcessor(context, relationshipSupport, joinTableSupport),
            new ManyToManyOwningProcessor(context, relationshipSupport, joinTableSupport)
        );

        processors.sort(Comparator.comparing(p -> p.order()));
    }

    /**
     * Resolves all relationships for a given entity.
     * <p>
     * This method first attempts to use cached attribute descriptors. If the cache is not
     * available, it falls back to scanning the entity's fields or properties based on its
     * {@code @Access} type to avoid processing duplicate annotations on both fields and getters.
     *
     * @param ownerType The TypeElement of the owning entity.
     * @param ownerEntity The model of the owning entity.
     */
    public void resolveRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        // 1) First, try to use cached descriptors if available.
        boolean resolvedFromCache = false;
        try {
            java.util.List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
            if (descriptors != null && !descriptors.isEmpty()) {
                for (AttributeDescriptor d : descriptors) {
                    resolve(d, ownerEntity);
                }
                resolvedFromCache = true;
            }
        } catch (Exception ignore) {
            // Fallback to @Access-based scanning if cache is not implemented or an exception occurs.
        }

        // 2) If not resolved from cache, scan based on the @Access type.
        if (!resolvedFromCache) {
            AccessType accessType = AccessUtils.determineAccessType(ownerType);

            if (accessType == AccessType.FIELD) {
                // FIELD access: Scan only fields for relationship annotations.
                scanFieldsForRelationships(ownerType, ownerEntity);
            } else {
                // PROPERTY access: Scan only getter methods for relationship annotations.
                scanPropertiesForRelationships(ownerType, ownerEntity);
            }
        }
    }

    /**
     * Scans fields for relationship annotations.
     */
    private void scanFieldsForRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element e : ownerType.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e instanceof VariableElement ve) {
                if (hasRelationshipAnnotation(ve)) {
                    resolve(ve, ownerEntity);
                }
            }
        }
    }

    /**
     * Scans getter methods for relationship annotations.
     */
    private void scanPropertiesForRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element e : ownerType.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && e instanceof ExecutableElement ex) {
                if (AccessUtils.isGetterMethod(ex) && hasRelationshipAnnotation(ex)) {
                    Optional<AttributeDescriptor> pdOpt = context.getAttributeDescriptorFactory().createPropertyDescriptor(ex);
                    pdOpt.ifPresent(attributeDescriptor -> resolve(attributeDescriptor, ownerEntity));
                }
            }
        }
    }

    private boolean hasRelationshipAnnotation(VariableElement field) {
        return field.getAnnotation(ManyToOne.class) != null ||
               field.getAnnotation(OneToOne.class) != null ||
               field.getAnnotation(OneToMany.class) != null ||
               field.getAnnotation(ManyToMany.class) != null;
    }

    private boolean hasRelationshipAnnotation(ExecutableElement ex) {
        return ex.getAnnotation(ManyToOne.class) != null ||
                ex.getAnnotation(OneToOne.class)  != null ||
                ex.getAnnotation(OneToMany.class) != null ||
                ex.getAnnotation(ManyToMany.class)!= null;
    }

    private boolean hasRelationshipAnnotation(AttributeDescriptor descriptor) {
        return descriptor.getAnnotation(ManyToOne.class) != null ||
                descriptor.getAnnotation(OneToOne.class)  != null ||
                descriptor.getAnnotation(OneToMany.class) != null ||
                descriptor.getAnnotation(ManyToMany.class)!= null;
    }

    /**
     * Resolves a relationship for a given attribute descriptor by delegating to the first
     * supporting {@link RelationshipProcessor}.
     *
     * @param descriptor The attribute descriptor representing the relationship field/property.
     * @param entityModel The model of the owning entity.
     */
    public void resolve(AttributeDescriptor descriptor, EntityModel entityModel) {
        boolean handled = false;
        for (RelationshipProcessor p : processors) {
            if (p.supports(descriptor)) { p.process(descriptor, entityModel); handled = true; break; }
        }
        if (!handled && hasRelationshipAnnotation(descriptor)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "No registered processor can handle relation on "
                            + entityModel.getEntityName() + "." + descriptor.name(),
                    descriptor.elementForDiagnostics());
        }
    }

    /**
     * Overload for compatibility to handle {@link VariableElement}.
     * Wraps the VariableElement in an AttributeDescriptor for processing.
     *
     * @param field The relationship field element.
     * @param ownerEntity The model of the owning entity.
     */
    public void resolve(VariableElement field, EntityModel ownerEntity) {
        AttributeDescriptor fieldAttr = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
        resolve(fieldAttr, ownerEntity);
    }

    /**
     * A deferred processing pass for {@code @MapsId} attributes.
     * This runs after all relationships and columns have been created to validate the consistency
     * of the PK structure with the {@code @MapsId.value()} and create accurate FK-to-PK mappings.
     *
     * @param ownerType The TypeElement of the owning entity.
     * @param ownerEntity The model of the owning entity.
     */
    public void processMapsIdAttributes(TypeElement ownerType, EntityModel ownerEntity) {
        java.util.List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
        if (descriptors != null) {
            for (AttributeDescriptor d : descriptors) {
                if (d.hasAnnotation(MapsId.class)) {
                    processMapsIdAttribute(d, ownerEntity);
                }
            }
            return;
        }

        // Scan using AccessType only if cache is unavailable.
        AccessType accessType = AccessUtils.determineAccessType(ownerType);

        if (accessType == AccessType.FIELD) {
            // Process @MapsId on ToOne relationships defined on fields.
            processMapsIdFromFields(ownerType, ownerEntity);
        } else {
            // Process @MapsId on ToOne relationships defined on getters.
            processMapsIdFromProperties(ownerType, ownerEntity);
        }
    }

    private void processMapsIdFromFields(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD && element instanceof VariableElement field) {
                if (field.getAnnotation(MapsId.class) != null) {
                    AttributeDescriptor descriptor = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
                    processMapsIdAttribute(descriptor, ownerEntity);
                }
            }
        }
    }

    private void processMapsIdFromProperties(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.METHOD && element instanceof ExecutableElement method) {
                if (AccessUtils.isGetterMethod(method) && method.getAnnotation(MapsId.class) != null && hasRelationshipAnnotation(method)) {
                    Optional<AttributeDescriptor> descriptorOpt = context.getAttributeDescriptorFactory().createPropertyDescriptor(method);
                    descriptorOpt.ifPresent(descriptor -> processMapsIdAttribute(descriptor, ownerEntity));
                }
            }
        }
    }

    /**
     * Processes an individual {@code @MapsId} attribute.
     *
     * @param descriptor The attribute descriptor with the @MapsId annotation.
     * @param ownerEntity The model of the owning entity.
     */
    public void processMapsIdAttribute(AttributeDescriptor descriptor, EntityModel ownerEntity) {
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        String keyPath = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : "";

        // 1) Target only ToOne owning sides.
        ManyToOne m2o = descriptor.getAnnotation(ManyToOne.class);
        OneToOne o2o = descriptor.getAnnotation(OneToOne.class);
        if (m2o == null && (o2o == null || !o2o.mappedBy().isEmpty())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId can only be used on owning side ToOne relationships", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // 2) Collect the owning entity's primary key columns.
        List<ColumnModel> ownerPkCols = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkCols.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId requires a primary key on " + ownerEntity.getEntityName(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // 3) Find the RelationshipModel for the field.
        RelationshipModel relationship = findToOneRelationshipFor(descriptor, ownerEntity);
        if (relationship == null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Could not find relationship model for @MapsId field " + descriptor.name(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (!Objects.equals(relationship.getTableName(), ownerEntity.getTableName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                " @MapsId requires FK to be on owner's primary table. table=" + relationship.getTableName(),
                descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        List<String> fkColumns = relationship.getColumns();

        // 4) Process @MapsId.value().
        if (keyPath.isEmpty()) {
            // Share the entire primary key.
            processFullPrimaryKeyMapping(descriptor, ownerEntity, relationship, fkColumns, ownerPkCols, keyPath);
        } else {
            // Map to a specific primary key attribute.
            processPartialPrimaryKeyMapping(descriptor, ownerEntity, relationship, fkColumns, ownerPkCols, keyPath);
        }
    }

    private void processFullPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity, 
                                             RelationshipModel relationship, List<String> fkColumns, 
                                             List<ColumnModel> ownerPkCols, String keyPath) {
        if (fkColumns.size() != ownerPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId without value must map all PK columns. expected=" + ownerPkCols.size()
                    + ", found=" + fkColumns.size(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // Verify PK promotion and record mappings.
        ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
        
        List<String> ownerPkColumnNames = ownerPkCols.stream()
            .map(ColumnModel::getColumnName)
            .toList();
            
        recordMapsIdBindings(relationship, fkColumns, ownerPkColumnNames, keyPath);
    }

    private void processPartialPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity,
                                                RelationshipModel relationship, List<String> fkColumns,
                                                List<ColumnModel> ownerPkCols, String keyPath) {
        List<String> ownerPkAttrColumns = findPkColumnsForAttribute(ownerEntity, keyPath, descriptor);
        if (ownerPkAttrColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + keyPath + "\") could not find matching PK attribute on " + ownerEntity.getEntityName(),
                descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (fkColumns.size() != ownerPkAttrColumns.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + keyPath + "\") column count mismatch. expected=" + ownerPkAttrColumns.size()
                    + ", found=" + fkColumns.size(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
        recordMapsIdBindings(relationship, fkColumns, ownerPkAttrColumns, keyPath);
    }

    /**
     * Finds the RelationshipModel for a ToOne relationship.
     */
    private RelationshipModel findToOneRelationshipFor(AttributeDescriptor d, EntityModel owner) {
        for (var rel : owner.getRelationships().values()) {
            if ((rel.getType() == RelationshipType.MANY_TO_ONE || rel.getType() == RelationshipType.ONE_TO_ONE)
                && Objects.equals(rel.getSourceAttributeName(), d.name())) {
                return rel;
            }
        }
        return null;
    }

    /**
     * Ensures that the specified columns are primary keys, promotes them if necessary,
     * and removes any duplicate embedded PK columns.
     */
    private void ensureAllArePrimaryKeys(EntityModel ownerEntity, String tableName, List<String> columnNames, AttributeDescriptor descriptor) {
        for (String columnName : columnNames) {
            ColumnModel column = ownerEntity.findColumn(tableName, columnName);
            if (column == null) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not find column " + columnName + " for @MapsId PK promotion", descriptor.elementForDiagnostics());
                continue;
            }

            if (!column.isPrimaryKey()) {
                column.setPrimaryKey(true);
            }

            if (column.isNullable()) {
                column.setNullable(false);
            }

            // Remove duplicate embedded PK columns.
            removeDuplicateEmbeddedPkColumns(ownerEntity, columnName, descriptor);
        }
        refreshPrimaryKeyConstraint(ownerEntity, tableName);
    }

    /**
     * Removes embedded PK columns that are duplicates of a @MapsId foreign key column.
     * For example, if `customer_id` (FK->PK) exists, this removes `id_customerId` (embedded PK).
     */
    private void removeDuplicateEmbeddedPkColumns(EntityModel ownerEntity, String fkColumnName, AttributeDescriptor descriptor) {
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        if (mapsId == null) return;

        String keyPath = mapsId.value();
        if (keyPath.isEmpty()) return;

        // Generate possible names for the embedded PK column that might be a duplicate.
        List<String> possibleEmbeddedPkColumns = List.of(
            "id_" + keyPath,           // e.g., "id_customerId"
            "id." + keyPath,          // e.g., "id.customerId" (incorrect but worth checking)
            keyPath                   // e.g., "customerId" (direct)
        );

        for (String embeddedPkColumn : possibleEmbeddedPkColumns) {
            if (!embeddedPkColumn.equals(fkColumnName)) {
                // Find and remove the embedded PK column if it has a different name than the FK column.
                ColumnModel duplicateColumn = ownerEntity.getColumns().values().stream()
                    .filter(col -> embeddedPkColumn.equals(col.getColumnName()))
                    .filter(ColumnModel::isPrimaryKey)
                    .findFirst()
                    .orElse(null);

                if (duplicateColumn != null) {
                    // Remove the duplicate column.
                    ownerEntity.getColumns().entrySet()
                        .removeIf(entry -> entry.getValue() == duplicateColumn);

                    // Removed duplicate embedded PK column
                }
            }
        }
    }

    private void refreshPrimaryKeyConstraint(EntityModel entity, String tableName) {
        List<String> pkCols = entity.getColumns().values().stream()
            .filter(c -> tableName.equals(c.getTableName()) && c.isPrimaryKey())
            .map(ColumnModel::getColumnName)
            .sorted()
            .toList();

        if (pkCols.isEmpty()) return;

        // Find existing PK for the table
        ConstraintModel existing = entity.getConstraints().values().stream()
            .filter(c -> c.getType() == ConstraintType.PRIMARY_KEY && tableName.equals(c.getTableName()))
            .findFirst().orElse(null);

        String newPkName = context.getNaming().pkName(tableName, pkCols);

        // Update if existing PK is missing or columns differ
        if (existing == null || !new HashSet<>(existing.getColumns()).equals(new HashSet<>(pkCols))) {
            if (existing != null) {
                entity.getConstraints().remove(existing.getName());
            }
            ConstraintModel pk = ConstraintModel.builder()
                .name(newPkName).type(ConstraintType.PRIMARY_KEY)
                .tableName(tableName).columns(pkCols).build();
            entity.getConstraints().put(newPkName, pk);
        }
    }

    /**
     * Records the @MapsId bindings.
     */
    private void recordMapsIdBindings(RelationshipModel relationship, List<String> fkColumns, List<String> pkColumns, String keyPath) {
        relationship.setMapsIdKeyPath(keyPath);

        // Map FK columns to PK columns (1:1 mapping in order).
        Map<String, String> bindings = relationship.getMapsIdBindings();
        if (bindings == null) {
            bindings = new HashMap<>();
            relationship.setMapsIdBindings(bindings);
        }
        for (int i = 0; i < Math.min(fkColumns.size(), pkColumns.size()); i++) {
            bindings.put(fkColumns.get(i), pkColumns.get(i));
        }
    }

    /**
     * Finds the columns corresponding to a specific PK attribute, supporting @IdClass and @EmbeddedId.
     */
    private List<String> findPkColumnsForAttribute(EntityModel ownerEntity, String attributeName, AttributeDescriptor where) {
        // 1. If user specifies an attribute path, but the PK is a single ID, it's an error.
        List<ColumnModel> allPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (allPkColumns.size() == 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + attributeName + "\") cannot specify an attribute name for an entity with a single-column primary key. " +
                "This is only supported for @EmbeddedId composite keys.", where.elementForDiagnostics());
            return List.of();
        }

        // 2. For composite keys, look up the attribute path in the context map with flexible matching
        String fqcn = ownerEntity.getFqcn() != null ? ownerEntity.getFqcn() : ownerEntity.getEntityName();

        // Try various key formats.
        List<String> possibleKeys = List.of(
            attributeName,                    // "customerId"
            "id." + attributeName,           // "id.customerId"
            "id"                             // fallback to embedded id itself
        );

        List<String> cols = null;
        for (String key : possibleKeys) {
            cols = context.getPkColumnsForAttribute(fqcn, key);
            if (cols != null && !cols.isEmpty()) {
                break;
            }
        }

        // 3. Also try direct matching: check if a PK column exists with the same name as the FK column.
        if ((cols == null || cols.isEmpty()) && where != null) {
            JoinColumn joinColumn = where.getAnnotation(JoinColumn.class);
            if (joinColumn != null && !joinColumn.name().isEmpty()) {
                String fkColumnName = joinColumn.name();
                // Check if a PK column exists with the same name as the FK column.
                boolean hasPkWithSameName = allPkColumns.stream()
                    .anyMatch(pk -> fkColumnName.equals(pk.getColumnName()));
                if (hasPkWithSameName) {
                    cols = List.of(fkColumnName);
                }
            }
        }

        if (cols == null || cols.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + attributeName + "\") could not be resolved to PK columns. " +
                "Ensure the attribute path is correct and the target entity uses @EmbeddedId.",
                where.elementForDiagnostics());
            return List.of();
        }
        return cols;
    }

}
