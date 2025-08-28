package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.AttributeDescriptorFactory;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class EmbeddedHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final RelationshipHandler relationshipHandler;
    private final AttributeDescriptorFactory descriptorFactory;
    private final Types typeUtils;
    private final Elements elementUtils;

    /**
     * Creates a new EmbeddedHandler and wires required collaborators.
     *
     * Initializes the processing context and handler collaborators, obtains type and
     * element utilities from the context, and constructs the AttributeDescriptorFactory
     * used to produce AttributeDescriptor instances for embeddable/embedded processing.
     */
    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler, RelationshipHandler relationshipHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.relationshipHandler = relationshipHandler;
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
        this.descriptorFactory = new AttributeDescriptorFactory(typeUtils, elementUtils, context);
    }

    /**
     * Process an embedded field on an entity.
     *
     * Converts the given field (an embedded or embeddable attribute) into columns and/or
     * relationship entries on the provided owner entity. This overload wraps the
     * VariableElement into a FieldAttributeDescriptor and delegates to the descriptor-based
     * processing routine.
     *
     * @param field the field element representing the embedded attribute
     * @param ownerEntity the target EntityModel to which columns and relationships will be added
     * @param processedTypes a set of already-processed embeddable type names used to avoid recursive re-processing
     */
    public void processEmbedded(VariableElement field, EntityModel ownerEntity, Set<String> processedTypes) {
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processEmbedded(fieldDescriptor, ownerEntity, processedTypes);
    }

    /**
     * Processes an embedded attribute (non-Id) by applying any attribute/association/table overrides
     * and materializing its columns and relationships onto the given owner entity model.
     *
     * This performs descriptor-driven traversal of the embeddable type, handling nested embeddables,
     * relationship mappings, and column mappings. It delegates to internal processing with an empty
     * prefix and marks the embedding context as not part of a primary key.
     *
     * @param attribute the descriptor for the embedded attribute to process
     * @param ownerEntity the target EntityModel to receive columns and relationships produced from the embeddable
     * @param processedTypes a set of type names used to track and avoid recursive re-processing of embeddable types
     */
    public void processEmbedded(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, false, "", nameOverrides, tableOverrides, assocOverrides);
    }

    /**
     * Process an @EmbeddedId field, adding its constituent columns and relationships to the owner entity.
     *
     * This converts the provided field into a descriptor and delegates to the descriptor-based
     * processing path that handles embeddable types, applies overrides, and marks resulting
     * columns as primary key components on the owner entity. Recursive processing of embeddable
     * types is guarded using the supplied processedTypes set.
     *
     * @param field the VariableElement representing the embedded id field
     * @param ownerEntity the entity model to receive columns and relationships derived from the embeddable
     * @param processedTypes a set of type names already processed to prevent infinite recursion
     */
    public void processEmbeddedId(VariableElement field, EntityModel ownerEntity, Set<String> processedTypes) {
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processEmbeddedId(fieldDescriptor, ownerEntity, processedTypes);
    }

    /**
     * Processes an embeddable attribute used as an entity's embedded id (primary key).
     *
     * Builds name/table/association override maps for the given attribute and delegates
     * to the internal embedding routine with primary-key context.
     *
     * @param attribute descriptor for the embedded-id attribute
     * @param ownerEntity the entity model receiving columns/relationships
     * @param processedTypes a set of already-processed embeddable type names to avoid recursion
     */
    public void processEmbeddedId(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, true, "", nameOverrides, tableOverrides, assocOverrides);
    }

    /**
     * Recursively processes the fields of an embeddable type and adds corresponding columns
     * and relationships to the given collection-owner entity model.
     *
     * This walks the attributes of the provided embeddable type (including nested embeddables,
     * associations, and basic attributes), applies attribute/table/association overrides, and
     * creates columns or relationships on ownerCollectionTable. Column names are prefixed
     * with the provided prefix and the collection field name when applicable. The method
     * prevents infinite recursion by tracking processed type names in processedTypes.
     *
     * @param embeddableType the embeddable type whose attributes will be processed
     * @param ownerCollectionTable the entity model that receives the generated columns and relationships
     * @param processedTypes a modifiable set of qualified type names used to avoid re-processing recursive types
     * @param prefix an optional name prefix applied to generated column names (may be null)
     * @param collectionField if non-null, the collection field descriptor whose name and overrides are applied when generating column names and resolving overrides
     */
    public void processEmbeddableFields(TypeElement embeddableType, EntityModel ownerCollectionTable, Set<String> processedTypes, String prefix, VariableElement collectionField) {
        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        AttributeDescriptor collectionDescriptor = (collectionField != null) ? new FieldAttributeDescriptor(collectionField, typeUtils, elementUtils) : null;

        String effectivePrefix = (prefix != null ? prefix : "")
                + (collectionDescriptor != null ? collectionDescriptor.name() + "_" : "");

        Map<String, String> attrNameOverrides = new HashMap<>();
        Map<String, String> attrTableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = (collectionDescriptor != null)
                ? extractOverrides(collectionDescriptor, attrNameOverrides, attrTableOverrides)
                : Collections.emptyMap();

        List<AttributeDescriptor> embeddedDescriptors = descriptorFactory.createDescriptors(embeddableType);

        for (AttributeDescriptor embeddedDescriptor : embeddedDescriptors) {
            if (embeddedDescriptor.hasAnnotation(Transient.class)) continue;

            String attributeName = embeddedDescriptor.name();

            if (embeddedDescriptor.hasAnnotation(Embedded.class)) {
                String childPrefix = attributeName + ".";
                Map<String, String> childNameOverrides = filterAndRemapOverrides(attrNameOverrides, childPrefix);
                Map<String, String> childTableOverrides = filterAndRemapOverrides(attrTableOverrides, childPrefix);
                Map<String, List<JoinColumn>> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, childPrefix);

                processEmbeddedInternal(embeddedDescriptor, ownerCollectionTable, processedTypes, false, effectivePrefix, childNameOverrides, childTableOverrides, childAssocOverrides);
            } else if (embeddedDescriptor.hasAnnotation(ManyToOne.class) || embeddedDescriptor.hasAnnotation(OneToOne.class)) {
                processEmbeddedRelationship(embeddedDescriptor, ownerCollectionTable, assocOverrides, effectivePrefix);
            } else {
                ColumnModel column = columnHandler.createFromAttribute(embeddedDescriptor, ownerCollectionTable, attrNameOverrides);
                if (column != null) {
                    String attrName = embeddedDescriptor.name();

                    String overrideName = attrNameOverrides.get(attrName);
                    boolean hasOverrideName = (overrideName != null && !overrideName.isEmpty());
                    Column leafColAnn = embeddedDescriptor.getAnnotation(Column.class);
                    boolean hasExplicitLeafName = (leafColAnn != null && !leafColAnn.name().isEmpty());

                    String targetTable = attrTableOverrides.get(attrName);
                    if (targetTable != null && !targetTable.isEmpty()) {
                        boolean isValidTable = ownerCollectionTable.getTableName().equals(targetTable) ||
                                ownerCollectionTable.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(targetTable));
                        if (isValidTable) {
                            column.setTableName(targetTable);
                        } else {
                            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                                    "AttributeOverride.table '" + targetTable + "' is not a primary/secondary table of " +
                                            ownerCollectionTable.getEntityName() + ". Falling back to " + column.getTableName(),
                                    collectionDescriptor != null ? collectionDescriptor.elementForDiagnostics() : embeddableType);
                        }
                    }

                    if (!(hasOverrideName || hasExplicitLeafName)) {
                        column.setColumnName(effectivePrefix + column.getColumnName());
                    }

                    if (!ownerCollectionTable.hasColumn(column.getTableName(), column.getColumnName())) {
                        ownerCollectionTable.putColumn(column);
                    }
                }
            }
        }
        processedTypes.remove(typeName);
    }

    /**
     * Recursively processes an embeddable attribute, adding its columns and relationships to the owner entity.
     *
     * <p>Traverses the fields described by the embeddable type referenced by {@code attribute}. Handles nested
     * embedded types, association mappings (ManyToOne/OneToOne) and simple attributes. Applies attribute and table
     * overrides, prefixes generated column names with the parent prefix and the attribute name, and marks columns
     * as primary-key / non-null when {@code isPrimaryKey} is true. Prevents infinite recursion using {@code processedTypes}.</p>
     *
     * @param attribute         descriptor of the embedded attribute to process
     * @param ownerEntity       entity model receiving generated columns and relationships
     * @param processedTypes    set of fully-qualified embeddable type names already visited to avoid recursion
     * @param isPrimaryKey      true when processing an embedded id (columns created must be PK and non-null)
     * @param parentPrefix      prefix to apply to generated column names (typically accumulated from ancestor attributes)
     * @param nameOverrides     map of attribute-path -> column-name overrides to apply for attributes within this embeddable
     * @param tableOverrides    map of attribute-path -> table-name overrides to apply for attributes within this embeddable
     * @param assocOverrides    map of attribute-path -> list of JoinColumn used to override association join columns
     */
    private void processEmbeddedInternal(
            AttributeDescriptor attribute,
            EntityModel ownerEntity,
            Set<String> processedTypes,
            boolean isPrimaryKey,
            String parentPrefix,
            Map<String, String> nameOverrides,
            Map<String, String> tableOverrides,
            Map<String, List<JoinColumn>> assocOverrides) {

        TypeMirror typeMirror = attribute.type();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        String prefix = parentPrefix + attribute.name() + "_";

        List<AttributeDescriptor> embeddedDescriptors = descriptorFactory.createDescriptors(embeddableType);

        for (AttributeDescriptor embeddedDescriptor : embeddedDescriptors) {
            if (embeddedDescriptor.hasAnnotation(Transient.class)) continue;

            String attrName = embeddedDescriptor.name();

            if (embeddedDescriptor.hasAnnotation(Embedded.class)) {
                String childPrefix = attrName + ".";
                Map<String, String> childNameOverrides = filterAndRemapOverrides(nameOverrides, childPrefix);
                Map<String, String> childTableOverrides = filterAndRemapOverrides(tableOverrides, childPrefix);
                Map<String, List<JoinColumn>> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, childPrefix);

                processEmbeddedInternal(embeddedDescriptor, ownerEntity, processedTypes, isPrimaryKey, prefix, childNameOverrides, childTableOverrides, childAssocOverrides);
            } else if (embeddedDescriptor.hasAnnotation(ManyToOne.class) || embeddedDescriptor.hasAnnotation(OneToOne.class)) {
                processEmbeddedRelationship(embeddedDescriptor, ownerEntity, assocOverrides, prefix);
            } else {
                ColumnModel column = columnHandler.createFromAttribute(embeddedDescriptor, ownerEntity, nameOverrides);
                if (column != null) {
                    String overrideName = nameOverrides.get(attrName);
                    boolean hasOverrideName = (overrideName != null && !overrideName.isEmpty());
                    Column leafColAnn = embeddedDescriptor.getAnnotation(Column.class);
                    boolean hasExplicitLeafName = (leafColAnn != null && !leafColAnn.name().isEmpty());

                    String targetTable = tableOverrides.get(attrName);
                    if (targetTable != null && !targetTable.isEmpty()) {
                        boolean isValidTable = ownerEntity.getTableName().equals(targetTable) ||
                                ownerEntity.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(targetTable));
                        if (isValidTable) {
                            column.setTableName(targetTable);
                        } else {
                            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                                    "AttributeOverride.table '" + targetTable + "' is not a primary/secondary table of " +
                                            ownerEntity.getEntityName() + ". Falling back to " + column.getTableName(),
                                    attribute.elementForDiagnostics());
                        }
                    }

                    if (!(hasOverrideName || hasExplicitLeafName)) {
                        column.setColumnName(prefix + column.getColumnName());
                    }

                    if (isPrimaryKey) {
                        column.setPrimaryKey(true);
                        column.setNullable(false);
                    }

                    if (!ownerEntity.hasColumn(column.getTableName(), column.getColumnName())) {
                        ownerEntity.putColumn(column);
                    }
                }
            }
        }

        processedTypes.remove(typeName);
    }

    /**
     * Processes an embedded relationship attribute (ManyToOne or OneToOne) on an owning entity.
     *
     * <p>Resolves join columns from explicit association overrides or the attribute's
     * {@code @JoinColumns}/{@code @JoinColumn} annotations, validates them against the
     * referenced entity's primary key(s), and creates or reuses foreign-key columns on
     * the owning entity. Builds and registers a corresponding RelationshipModel (including
     * FK constraint name and no-constraint flag) and, for 1:1 single-column FKs, a UNIQUE
     * constraint if applicable. Emits diagnostic errors/warnings through the processing
     * context and aborts processing for this relationship on fatal configuration issues
     * (e.g., mismatched composite key sizes, inconsistent FK tables, type/nullability mismatches).
     *
     * @param attribute the embedded attribute descriptor representing the relationship
     * @param ownerEntity the entity model that will receive FK columns, constraints, and the relationship
     * @param associationOverrides map of attribute name -> list of {@code JoinColumn} overrides to consult before annotations
     * @param prefix a column-name prefix to apply when generating default FK column names
     */
    private void processEmbeddedRelationship(AttributeDescriptor attribute, EntityModel ownerEntity,
                                             Map<String, List<JoinColumn>> associationOverrides, String prefix) {
        ManyToOne manyToOne = attribute.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = attribute.getAnnotation(OneToOne.class);

        List<JoinColumn> joinColumns = associationOverrides.get(attribute.name());
        if (joinColumns == null) {
            JoinColumns joinColumnsAnno = attribute.getAnnotation(JoinColumns.class);
            if (joinColumnsAnno != null) joinColumns = Arrays.asList(joinColumnsAnno.value());
            else {
                JoinColumn joinColumnAnno = attribute.getAnnotation(JoinColumn.class);
                joinColumns = (joinColumnAnno != null) ? List.of(joinColumnAnno) : Collections.emptyList();
            }
        }

        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) attribute.type()).asElement();
        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) return;

        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on embedded relationship " +
                            ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics());
            return;
        }

        if (!joinColumns.isEmpty() && refPkList.size() != joinColumns.size()) {
            context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch: expected " + refPkList.size() + " but got " + joinColumns.size()
                            + " on " + ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics()
            );
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c, (a, b) -> a, HashMap::new));

        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNamesInOrder = new ArrayList<>();
        String fkTableName = null;

        MapsId mapsId = attribute.getAnnotation(MapsId.class);
        String mapsIdAttr = mapsId != null && !mapsId.value().isEmpty() ? mapsId.value() : null;

        String explicitFkName = null;
        if (!joinColumns.isEmpty()) {
            for (JoinColumn jc : joinColumns) {
                String fkName = jc.foreignKey().name();
                if (fkName != null && !fkName.isEmpty()) {
                    if (explicitFkName == null) {
                        explicitFkName = fkName;
                    } else if (!explicitFkName.equals(fkName)) {
                        context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + fkName + "' on embedded relationship " + attribute.name(),
                                attribute.elementForDiagnostics());
                        return;
                    }
                }
            }

            if (!relationshipHandler.allSameConstraintMode(joinColumns)) {
                context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "All @JoinColumn.foreignKey.value must be identical for composite FK on embedded relationship " + attribute.name(),
                        attribute.elementForDiagnostics());
                return;
            }
        }

        boolean noConstraint = !joinColumns.isEmpty() &&
                joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        for (int i = 0; i < (joinColumns.isEmpty() ? refPkList.size() : joinColumns.size()); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            ColumnModel pkCol;
            if (jc != null && !jc.referencedColumnName().isEmpty()) {
                pkCol = refPkMap.get(jc.referencedColumnName());
            } else {
                pkCol = refPkList.get(i);
            }
            if (pkCol == null) {
                context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "referencedColumnName not found among parent PKs on " + attribute.name(),
                        attribute.elementForDiagnostics());
                return;
            }

            String fkName = (jc != null && !jc.name().isEmpty())
                    ? jc.name() // Use explicit name as-is
                    : prefix + attribute.name() + "_" + pkCol.getColumnName(); // Apply prefix only to default-generated name

            boolean colNullable = jc != null ? jc.nullable()
                    : (manyToOne != null ? manyToOne.optional() : oneToOne.optional());

            boolean colUnique = (oneToOne != null && mapsId == null && (joinColumns.isEmpty() ? refPkList.size() == 1 : joinColumns.size() == 1))
                    && (jc != null ? jc.unique() : true);

            boolean makePk = false;
            if (mapsId != null) {
                if (mapsIdAttr == null) makePk = true;
                else {
                    makePk = pkCol.getColumnName().equalsIgnoreCase(mapsIdAttr) || pkCol.getColumnName().endsWith("_" + mapsIdAttr);
                }
            }

            String fkTable = resolveJoinColumnTable(jc, ownerEntity);
            if (fkTableName == null) {
                fkTableName = fkTable;
            } else if (!fkTableName.equals(fkTable)) {
                context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "All @JoinColumn annotations in composite key must target the same table for embedded relationship " + attribute.name() + ". Found: '" + fkTableName + "' and '" + fkTable + "'.",
                        attribute.elementForDiagnostics());
                return;
            }

            ColumnModel existing = ownerEntity.findColumn(fkTable, fkName);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .tableName(fkTable)
                    .javaType(pkCol.getJavaType())
                    .isPrimaryKey(makePk)
                    .isNullable(!makePk && colNullable)
                    .isUnique(colUnique)
                    .generationStrategy(GenerationStrategy.NONE)
                    .build();

            if (existing == null) {
                ownerEntity.putColumn(fkColumn);
            } else {
                if (!Objects.equals(existing.getJavaType(), pkCol.getJavaType())
                        || (existing.isPrimaryKey() != fkColumn.isPrimaryKey())
                        || (existing.isNullable() != fkColumn.isNullable())) {
                    context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "FK column mismatch on " + ownerEntity.getEntityName() + "." + fkName,
                            attribute.elementForDiagnostics());
                    return;
                }
            }

            fkColumnNames.add(fkName);
            referencedPkNamesInOrder.add(pkCol.getColumnName());
        }

        final String fkTableBase = (fkTableName != null) ? fkTableName : ownerEntity.getTableName();

        // For 1:1 relationships on a single FK, explicitly create a UNIQUE constraint model
        // This ensures physical constraint creation regardless of the DDL generator's strategy.
        if (oneToOne != null && mapsId == null && fkColumnNames.size() == 1) {
            boolean isJoinColumnUnique = joinColumns.isEmpty() || joinColumns.get(0).unique();
            if (isJoinColumnUnique) {
                String uqName = context.getNaming().uqName(fkTableBase, fkColumnNames);
                ownerEntity.getConstraints().putIfAbsent(uqName, ConstraintModel.builder()
                        .name(uqName)
                        .type(ConstraintType.UNIQUE)
                        .tableName(fkTableBase)
                        .columns(new ArrayList<>(fkColumnNames))
                        .build());
            }
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .tableName(fkTableBase)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNamesInOrder)
                .mapsId(mapsId != null)
                .noConstraint(noConstraint)
                .constraintName(explicitFkName != null ? explicitFkName
                        : context.getNaming().fkName(
                        fkTableBase, fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNamesInOrder))
                .cascadeTypes(manyToOne != null ? toCascadeList(manyToOne.cascade()) : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();
        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);

        if (!noConstraint) {
            relationshipHandler.addForeignKeyIndex(ownerEntity, fkColumnNames, fkTableBase);
        }
    }

    /**
     * Resolve the actual table name to use for a join column.
     *
     * Returns the join column's explicit table name if it is non-empty and is either
     * the owner's primary table or one of its secondary tables. If the join column
     * is null or its table name is empty, returns the owner's primary table.
     * If the explicit table is not a primary/secondary table of the owner, logs a
     * warning and returns the owner's primary table.
     *
     * @param jc    the JoinColumn annotation (may be null)
     * @param owner the owning entity model whose primary/secondary tables are validated
     * @return the resolved table name to use for the join column
     */
    private String resolveJoinColumnTable(JoinColumn jc, EntityModel owner) {
        String primary = owner.getTableName();
        if (jc == null || jc.table().isEmpty()) return primary;
        String req = jc.table();
        boolean ok = primary.equals(req) ||
                owner.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(req));
        if (!ok) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "JoinColumn.table='" + req + "' is not a primary/secondary table of " +
                            owner.getEntityName() + ". Falling back to '" + primary + "'.");
            return primary;
        }
        return req;
    }

    /**
     * Extracts attribute and association override information from the given attribute descriptor.
     *
     * Populates the supplied maps with column name and table overrides found on
     * `@AttributeOverrides` / `@AttributeOverride` annotations (keyed by the overridden
     * attribute name). Empty override values are ignored. Also collects association
     * overrides from `@AssociationOverrides` / `@AssociationOverride` and returns them
     * as a map from association name to the list of `JoinColumn`s.
     *
     * @param attribute the attribute descriptor to inspect for override annotations
     * @param nameOverrides map to populate with attribute-to-column-name overrides
     * @param tableOverrides map to populate with attribute-to-table-name overrides
     * @return a map of association override entries (association name -> list of JoinColumn)
     */
    private Map<String, List<JoinColumn>> extractOverrides(AttributeDescriptor attribute, Map<String, String> nameOverrides, Map<String, String> tableOverrides) {
        AttributeOverrides aos = attribute.getAnnotation(AttributeOverrides.class);
        if (aos != null) {
            for (AttributeOverride ao : aos.value()) {
                if (!ao.column().name().isEmpty()) nameOverrides.put(ao.name(), ao.column().name());
                if (!ao.column().table().isEmpty()) tableOverrides.put(ao.name(), ao.column().table());
            }
        }
        AttributeOverride aoSingle = attribute.getAnnotation(AttributeOverride.class);
        if (aoSingle != null) {
            if (!aoSingle.column().name().isEmpty()) nameOverrides.put(aoSingle.name(), aoSingle.column().name());
            if (!aoSingle.column().table().isEmpty()) tableOverrides.put(aoSingle.name(), aoSingle.column().table());
        }

        Map<String, List<JoinColumn>> assocOverrides = new HashMap<>();
        AssociationOverrides as = attribute.getAnnotation(AssociationOverrides.class);
        if (as != null) {
            for (AssociationOverride a : as.value()) {
                assocOverrides.put(a.name(), Arrays.asList(a.joinColumns()));
            }
        }
        AssociationOverride aSingle = attribute.getAnnotation(AssociationOverride.class);
        if (aSingle != null) {
            assocOverrides.put(aSingle.name(), Arrays.asList(aSingle.joinColumns()));
        }
        return assocOverrides;
    }

    /**
     * Returns a new map containing entries from {@code parentOverrides} whose keys start with {@code prefix},
     * with the returned keys having the {@code prefix} removed.
     *
     * @param parentOverrides map of override keys to values
     * @param prefix string prefix to filter keys by and strip from returned keys
     * @return a new map with filtered and remapped keys; values are unchanged
     */
    private Map<String, String> filterAndRemapOverrides(Map<String, String> parentOverrides, String prefix) {
        return parentOverrides.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    /**
     * Returns a new map containing only entries from parentOverrides whose keys start with the given prefix,
     * with the prefix removed from each key.
     *
     * @param parentOverrides map of association override names to lists of JoinColumn instances
     * @param prefix the key prefix to filter by and remove from retained keys
     * @return a new map with filtered entries and keys remapped by stripping the prefix
     */
    private Map<String, List<JoinColumn>> filterAndRemapAssocOverrides(Map<String, List<JoinColumn>> parentOverrides, String prefix) {
        return parentOverrides.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    /**
     * Convert a Jakarta Persistence CascadeType array to an immutable List.
     *
     * Returns an empty list when the input is null.
     *
     * @param arr the jakarta.persistence.CascadeType array, may be null
     * @return an immutable List of CascadeType corresponding to the array contents, or an empty list if {@code arr} is null
     */
    private static List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }
}
