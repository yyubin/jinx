package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.builtins.SecondaryTableAdapter;
import org.jinx.handler.builtins.TableAdapter;
import org.jinx.handler.relationship.RelationshipSupport;
import org.jinx.model.*;
import org.jinx.util.ConstraintKeys;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

public class EntityHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final ConstraintHandler constraintHandler;
    private final SequenceHandler sequenceHandler;
    private final ElementCollectionHandler elementCollectionHandler;
    private final TableGeneratorHandler tableGeneratorHandler;
    private final RelationshipHandler relationshipHandler;
    private final RelationshipSupport relationshipSupport;

    public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler,
                         ConstraintHandler constraintHandler, SequenceHandler sequenceHandler,
                         ElementCollectionHandler elementCollectionHandler, TableGeneratorHandler tableGeneratorHandler,
                         RelationshipHandler relationshipHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.constraintHandler = constraintHandler;
        this.sequenceHandler = sequenceHandler;
        this.elementCollectionHandler = elementCollectionHandler;
        this.tableGeneratorHandler = tableGeneratorHandler;
        this.relationshipHandler = relationshipHandler;
        this.relationshipSupport = new RelationshipSupport(context);
    }

    public void handle(TypeElement typeElement) {
        // Clear mappedBy visited set for each new entity to allow proper cycle detection
        context.clearMappedByVisited();

        // 1. Set up basic entity information
        EntityModel entity = createEntityModel(typeElement);
        if (entity == null) return;

        // Pre-register: enable mutual lookups between parent/child entities
        context.getSchemaModel().getEntities().putIfAbsent(entity.getEntityName(), entity);

        // 2. Process table metadata
        processTableMetadata(typeElement, entity);

        // 3. Process sequence/table generators
        processGenerators(typeElement);

        // 4. Process constraints
        processEntityConstraints(typeElement, entity);

        // 5. (REMOVED) Inheritance field injection - integrated in AttributeDescriptor-based processing

        // 6. Pre-register secondary table names (for validation)
        List<SecondaryTable> secondaryTableAnns = collectSecondaryTables(typeElement);
        registerSecondaryTableNames(secondaryTableAnns, entity);

        // 7. Process composite keys (@EmbeddedId)
        if (!processCompositeKeys(typeElement, entity)) return;

        // 8. Process fields (AttributeDescriptor-based) - includes MappedSuperclass attributes
        processFieldsWithAttributeDescriptor(typeElement, entity);

        // 10. Process secondary table joins and inheritance relationships (after PK is determined)
        processSecondaryTableJoins(secondaryTableAnns, typeElement, entity);
        processInheritanceJoin(typeElement, entity);

        // 11. Check for @MapsId attributes requiring deferred processing
        if (hasMapsIdAttributes(typeElement, entity)) {
            context.getDeferredEntities().offer(entity);
            context.getDeferredNames().add(entity.getEntityName());
        }
    }

    private void registerSecondaryTableNames(List<SecondaryTable> secondaryTablesAnns, EntityModel entity) {
        for (SecondaryTable stAnn : secondaryTablesAnns) {
            SecondaryTableModel stModel = SecondaryTableModel.builder()
                    .name(stAnn.name())
                    .build();
            if (entity.getSecondaryTables().stream().noneMatch(s -> s.getName().equals(stModel.getName()))) {
                entity.getSecondaryTables().add(stModel);
            }
        }
    }

    private void processSecondaryTableJoins(List<SecondaryTable> secondaryTables, TypeElement type, EntityModel entity) {
        // This method now runs after the PK has been determined.
        List<ColumnModel> primaryKeyColumns = context.findAllPrimaryKeyColumns(entity);
        if (!secondaryTables.isEmpty() && primaryKeyColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity with @SecondaryTable must have a primary key (@Id or @EmbeddedId).", type);
            entity.setValid(false);
            return;
        }

        for (SecondaryTable t : secondaryTables) {
            // Process constraints and indexes from the @SecondaryTable annotation
            processTableLike(new SecondaryTableAdapter(t, context), entity);
            // Process the join columns to create foreign key relationships
            processJoinTable(t.name(), Arrays.asList(t.pkJoinColumns()), type, entity, primaryKeyColumns, entity.getTableName(), RelationshipType.SECONDARY_TABLE);
        }
    }

    public void runDeferredPostProcessing() {
        int size = context.getDeferredEntities().size();
        for (int i = 0; i < size; i++) {
            EntityModel child = context.getDeferredEntities().poll();
            if (child == null) break;
            if (!child.isValid()) continue;

            String childName = child.getFqcn() != null ? child.getFqcn() : child.getEntityName();
            TypeElement te = context.getElementUtils().getTypeElement(childName);
            if (te == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Deferred processing: cannot resolve TypeElement for " + childName + " – re-queue");
                context.getDeferredEntities().offer(child);
                continue;
            }

            // Process JOINED inheritance
            processInheritanceJoin(te, child);

            // Re-process relationships for entities that were deferred due to missing referenced entities
            // This handles @ManyToOne/@OneToOne relationships where the target entity wasn't processed yet
            relationshipHandler.resolveRelationships(te, child);

            // Process @MapsId attributes if any
            if (hasMapsIdAttributes(te, child)) {
                boolean needsRetry = hasUnresolvedMapsIdDeps(te, child);

                if (!needsRetry) {
                    // 의존성이 모두 준비됨 → 실제 처리 진행
                    relationshipHandler.processMapsIdAttributes(te, child);
                }

                // 이번 라운드에서는 일단 제거 (성공/실패 관계없이)
                context.getDeferredNames().remove(childName);

                // 재시도가 필요하고 아직 유효한 엔티티라면 다시 큐에 추가
                if (needsRetry && child.isValid()) {
                    context.getDeferredEntities().offer(child);
                    context.getDeferredNames().add(childName);
                }
            } else {
                // If it was in the queue but not for @MapsId, it must be for another reason
                // (like JOINED or ToOne relationship) which should have been handled already. We can remove it.
                context.getDeferredNames().remove(childName);
            }

            // 부모가 여전히 없으면 processInheritanceJoin 내부에서 다시 enqueue
            // 하지만 여기서는 '이번 라운드' 스냅샷만 처리해서 무한루프 방지
        }
    }


    private EntityModel createEntityModel(TypeElement typeElement) {
        Optional<Table> tableOpt = Optional.ofNullable(typeElement.getAnnotation(Table.class));
        String tableName = tableOpt.map(Table::name).filter(n -> !n.isEmpty())
                .orElse(typeElement.getSimpleName().toString());
        String entityName = typeElement.getQualifiedName().toString();

        // Check for duplicates
        if (context.getSchemaModel().getEntities().containsKey(entityName)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate entity found: " + entityName, typeElement);
            // keep first
//            EntityModel invalidEntity = EntityModel.builder().entityName(entityName).isValid(false).build();
//            context.getSchemaModel().getEntities().putIfAbsent(entityName, invalidEntity);
            return null;
        }

        return EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .fqcn(typeElement.getQualifiedName().toString())
                .isValid(true)
                .build();
    }

    private void processTableMetadata(TypeElement typeElement, EntityModel entity) {
        Optional<Table> tableOpt = Optional.ofNullable(typeElement.getAnnotation(Table.class));
        tableOpt.ifPresent(table -> processTableLike(new TableAdapter(table, context), entity));
    }

    private void processGenerators(TypeElement typeElement) {
        sequenceHandler.processSequenceGenerators(typeElement);
        tableGeneratorHandler.processTableGenerators(typeElement);
    }

    private void processEntityConstraints(TypeElement typeElement, EntityModel entity) {
        processConstraints(typeElement, null,
                entity.getConstraints().values().stream().toList(),
                entity.getTableName());
    }



    private void processInheritanceJoin(TypeElement type, EntityModel childEntity) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) return;

        Optional<TypeElement> parentElementOptional = findNearestJoinedParentEntity(type);
        if (parentElementOptional.isEmpty()) {
            return;
        }
        TypeElement parentType = parentElementOptional.get();
        Inheritance parentInheritance = parentType.getAnnotation(Inheritance.class);
        if (parentInheritance == null || parentInheritance.strategy() != InheritanceType.JOINED) return;

        // Lookup parent entity model/PK columns
        EntityModel parentEntity = context.getSchemaModel().getEntities()
                .get(parentType.getQualifiedName().toString());
        String childName = childEntity.getEntityName();
        if (parentEntity == null) {
            if (context.getDeferredNames().contains(childName)) {
                return; // Already waiting for retry
            }
            context.getDeferredNames().add(childName);
            context.getDeferredEntities().add(childEntity); // Parent not yet processed -> retry later
            return;
        }

        if (!parentEntity.isValid()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JOINED parent entity is invalid: " + parentEntity.getEntityName(), type);
            childEntity.setValid(false);
            return;
        }

        List<ColumnModel> parentPkCols = context.findAllPrimaryKeyColumns(parentEntity);
        if (parentPkCols.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Parent entity '" + parentType.getQualifiedName() + "' must have a primary key for JOINED inheritance.",
                    type);
            childEntity.setValid(false);
            return;
        }

        PrimaryKeyJoinColumn[] pkjcs = type.getAnnotation(PrimaryKeyJoinColumns.class) != null
                ? type.getAnnotation(PrimaryKeyJoinColumns.class).value()
                : (type.getAnnotation(PrimaryKeyJoinColumn.class) != null
                ? new PrimaryKeyJoinColumn[]{type.getAnnotation(PrimaryKeyJoinColumn.class)}
                : new PrimaryKeyJoinColumn[]{});

        boolean ok = processJoinTable(
                childEntity.getTableName(),
                Arrays.asList(pkjcs),
                type, childEntity, parentPkCols,
                parentEntity.getTableName(),
                RelationshipType.JOINED_INHERITANCE
        );

        if (ok) {
            context.getDeferredNames().remove(childName);
        }
    }

    // Utility to protect against multi-level inheritance
    private Optional<TypeElement> findNearestJoinedParentEntity(TypeElement type) {
        Set<String> seen = new java.util.HashSet<>();
        TypeMirror sup = type.getSuperclass();

        while (sup.getKind() == TypeKind.DECLARED) {
            TypeElement p = (TypeElement) ((DeclaredType) sup).asElement();
            String qn = p.getQualifiedName().toString();

            if (!seen.add(qn)) {
                // Log warning and stop
                context.getMessager().printMessage(
                        javax.tools.Diagnostic.Kind.WARNING,
                        "Detected inheritance cycle near: " + qn + " while scanning for JOINED parent of " +
                                type.getQualifiedName()
                );
                break;
            }

            // Continue upward if not an entity
            if (p.getAnnotation(jakarta.persistence.Entity.class) == null) {
                sup = p.getSuperclass();
                continue;
            }

            // Check inheritance strategy if it's an entity
            jakarta.persistence.Inheritance inh = p.getAnnotation(jakarta.persistence.Inheritance.class);
            if (inh != null) {
                switch (inh.strategy()) {
                    case JOINED:
                        // Return nearest JOINED parent
                        return Optional.of(p);
                    case SINGLE_TABLE:
                    case TABLE_PER_CLASS:
                        // Conflict with JOINED search → error and terminate
                        context.getMessager().printMessage(
                                javax.tools.Diagnostic.Kind.ERROR,
                                "Found explicit inheritance strategy '" + inh.strategy() +
                                        "' at '" + qn + "' while searching for a JOINED parent of '" +
                                        type.getQualifiedName() + "'. Mixed strategies in the same hierarchy are not supported.",
                                p
                        );
                        return Optional.empty();
                    default:
                        // For future expansion: just continue
                        break;
                }
            }
            // Continue upward if no explicit @Inheritance
            sup = p.getSuperclass();
        }
        return Optional.empty();
    }

    private boolean processJoinTable(String tableName, List<PrimaryKeyJoinColumn> pkjcs,
                                  TypeElement type, EntityModel entity, List<ColumnModel> parentPkCols, String referencedTableName, RelationshipType relType) {
        List<String> childCols = new ArrayList<>();
        List<String> refCols = new ArrayList<>();

        if (pkjcs.isEmpty()) {
            // If no pkjcs, inherit parent table's PK as-is
            for (ColumnModel pkCol : parentPkCols) {
                childCols.add(pkCol.getColumnName());
                refCols.add(pkCol.getColumnName());
            }
        } else if (pkjcs.size() != parentPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "'" + tableName + "' pkJoinColumns size mismatch: expected " +
                            parentPkCols.size() + " but got " + pkjcs.size(), type);
            entity.setValid(false);
            return false;
        } else {
            for (int i = 0; i < pkjcs.size(); i++) {
                PrimaryKeyJoinColumn a = pkjcs.get(i);
                ColumnModel parentRef = resolveParentRef(parentPkCols, a, i, type);
                if (parentRef == null) {
                    context.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Invalid @PrimaryKeyJoinColumn at index " + i + " on " + type.getQualifiedName() + ".",
                            type);
                    entity.setValid(false);
                    return false;
                }
                String childCol = a.name().isEmpty() ? parentRef.getColumnName() : a.name();
                childCols.add(childCol);
                refCols.add(parentRef.getColumnName());
            }
        }

        String fkName = context.getNaming().fkName(
                tableName, childCols, referencedTableName, refCols);

        RelationshipModel rel = RelationshipModel.builder()
                .type(relType)
                .tableName(tableName) // FK가 걸리는 테이블 명시
                .columns(childCols)
                .referencedTable(referencedTableName)
                .referencedColumns(refCols)
                .constraintName(fkName)
                .build();
        entity.getRelationships().put(rel.getConstraintName(), rel);

        // For secondary tables, typically need to create columns on child (secondary) table side
        ensureChildPkColumnsExist(entity, tableName, childCols, parentPkCols);

        // Create FK index (only if not covered by PK/UNIQUE)
        relationshipSupport.addForeignKeyIndex(entity, childCols, tableName);

        return true;
    }

    // Existing private methods (unchanged)
    private void processTableLike(TableLike tableLike, EntityModel entity) {
        tableLike.getSchema().ifPresent(entity::setSchema);
        tableLike.getCatalog().ifPresent(entity::setCatalog);
        tableLike.getComment().ifPresent(entity::setComment);

        for (ConstraintModel c : tableLike.getConstraints()) {
            entity.getConstraints().put(c.getName(), c);
        }
        for (IndexModel i : tableLike.getIndexes()) {
            if (!entity.getIndexes().containsKey(i.getIndexName())) {
                entity.getIndexes().put(i.getIndexName(), i);
            }
        }
    }


    private void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints, String tableName) {
        constraintHandler.processConstraints(element, fieldName, constraints, tableName);
    }

    private List<SecondaryTable> collectSecondaryTables(TypeElement typeElement) {
        List<SecondaryTable> secondaryTableList = new ArrayList<>();
        SecondaryTable secondaryTable = typeElement.getAnnotation(SecondaryTable.class);
        SecondaryTables secondaryTables = typeElement.getAnnotation(SecondaryTables.class);
        if (secondaryTable != null) secondaryTableList.add(secondaryTable);
        if (secondaryTables != null) secondaryTableList.addAll(Arrays.asList(secondaryTables.value()));
        return secondaryTableList;
    }

    private boolean processCompositeKeys(TypeElement typeElement, EntityModel entity) {
        IdClass idClass = typeElement.getAnnotation(IdClass.class);
        if (idClass != null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "IdClass is not supported. Use @EmbeddedId instead for composite primary keys.",
                typeElement);
            entity.setValid(false);
            return false;
        }

        // Descriptor 기반으로 EmbeddedId 탐색
        AttributeDescriptor embeddedId = context.getCachedDescriptors(typeElement).stream()
            .filter(d -> d.hasAnnotation(EmbeddedId.class))
            .findFirst()
            .orElse(null);

        if (embeddedId != null) {
            embeddedHandler.processEmbeddedId(embeddedId, entity, new HashSet<>());
        }
        return true;
    }



    // NEW: AttributeDescriptor-based field processing
    public void processFieldsWithAttributeDescriptor(TypeElement typeElement, EntityModel entity) {
        // Get cached AttributeDescriptor list based on Access strategy
        List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);

        Map<String, SecondaryTable> tableMappings = buildTableMappings(entity, collectSecondaryTables(typeElement));

        for (AttributeDescriptor descriptor : descriptors) {
            processAttributeDescriptor(descriptor, entity, tableMappings);
        }
    }

    private void processAttributeDescriptor(AttributeDescriptor descriptor, EntityModel entity, Map<String, SecondaryTable> tableMappings) {
        if (descriptor.hasAnnotation(ElementCollection.class)) {
            // Use new AttributeDescriptor-based overload
            elementCollectionHandler.processElementCollection(descriptor, entity);
        } else if (descriptor.hasAnnotation(Embedded.class)) {
            // Use new AttributeDescriptor-based overload
            embeddedHandler.processEmbedded(descriptor, entity, new HashSet<>());
        } else if (isRelationshipAttribute(descriptor)) {
            processRelationshipAttribute(descriptor, entity);
        } else if (!descriptor.hasAnnotation(EmbeddedId.class)) {
            processRegularAttribute(descriptor, entity, tableMappings);
        }
    }

    private void processRegularAttribute(AttributeDescriptor descriptor,
                                         EntityModel entity,
                                         Map<String, SecondaryTable> tableMappings) {
        Column columnAnn = descriptor.getAnnotation(Column.class);
        String targetTable = determineTargetTableFromDescriptor(columnAnn, tableMappings, entity.getTableName());

        Map<String, String> overrides = Collections.singletonMap("tableName", targetTable);
        ColumnModel col = columnHandler.createFromAttribute(descriptor, entity, overrides);
        if (col == null) return;

        if (col.getTableName() == null || col.getTableName().isEmpty()) {
            col.setTableName(targetTable);
        }

        ColumnModel existing = entity.findColumn(targetTable, col.getColumnName());
        if (existing == null) {
            entity.putColumn(col);
        } else {
            if (existing.getJavaType() == null && col.getJavaType() != null) existing.setJavaType(col.getJavaType());
            if (existing.getLength() == 0 && col.getLength() > 0) existing.setLength(col.getLength());
            if (existing.getPrecision() == 0 && col.getPrecision() > 0) existing.setPrecision(col.getPrecision());
            if (existing.getScale() == 0 && col.getScale() > 0) existing.setScale(col.getScale());
            if (existing.getDefaultValue() == null && col.getDefaultValue() != null) existing.setDefaultValue(col.getDefaultValue());
            if (existing.getComment() == null && col.getComment() != null) existing.setComment(col.getComment());
        }

        // @Column(unique=true) → automatically add UNIQUE constraint
        if (columnAnn != null && columnAnn.unique()) {
            context.getConstraintManager().addUniqueIfAbsent(
                    entity,
                    targetTable,
                    List.of(col.getColumnName()),
                    Optional.empty() // Normalized to .orElse(null) inside ConstraintManager
            );
        }

        // Collect custom constraints
        List<ConstraintModel> collected = new ArrayList<>();
        processConstraints(descriptor.elementForDiagnostics(), col.getColumnName(), collected, targetTable);

        for (ConstraintModel c : collected) {
            // Normalize where / name to null-safe
            String where = normalizeBlankToNull(c.getWhere());
            String name  = normalizeBlankToNull(c.getName());

            switch (c.getType()) {
                case INDEX -> {
                    String ixName = (name == null)
                            ? context.getNaming().ixName(c.getTableName(), c.getColumns())
                            : name;

                    entity.getIndexes().putIfAbsent(ixName, IndexModel.builder()
                            .indexName(ixName)
                            .tableName(c.getTableName())
                            .columnNames(c.getColumns())
                            .build());
                }
                case UNIQUE -> {
                    // ConstraintManager accepts Optional and converts to null internally
                    context.getConstraintManager().addUniqueIfAbsent(
                            entity,
                            c.getTableName(),
                            c.getColumns(),
                            Optional.ofNullable(where)
                    );
                }
                case PRIMARY_KEY -> {
                    String pkName = (name == null)
                            ? context.getNaming().pkName(c.getTableName(), c.getColumns())
                            : name;

                    String key = ConstraintKeys.canonicalKey(
                            ConstraintType.PRIMARY_KEY.name(),
                            entity.getSchema(),
                            c.getTableName(),
                            c.getColumns(),
                            where // Already normalized to null
                    );

                    c.setName(pkName);
                    entity.getConstraints().putIfAbsent(key, c);
                }
                case NOT_NULL, CHECK, DEFAULT -> {
                    // Auto-generate name
                    if (name == null) {
                        name = switch (c.getType()) {
                            case CHECK    -> context.getNaming().ckName(c.getTableName(), c.getColumns());
                            case NOT_NULL -> context.getNaming().nnName(c.getTableName(), c.getColumns());
                            case DEFAULT  -> context.getNaming().dfName(c.getTableName(), c.getColumns());
                            default       -> context.getNaming().autoName(c.getTableName(), c.getColumns());
                        };
                        c.setName(name);
                    }

                    String key = ConstraintKeys.canonicalKey(
                            c.getType().name(),
                            entity.getSchema(),
                            c.getTableName(),
                            c.getColumns(),
                            where
                    );
                    c.setWhere(where); // Normalize empty string → null
                    entity.getConstraints().putIfAbsent(key, c);
                }
                default -> {
                    // Other types currently not processed
                }
            }
        }
    }

    private static String normalizeBlankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String determineTargetTableFromDescriptor(Column column, Map<String, SecondaryTable> tableMappings, String defaultTable) {
        if (column == null || column.table().isEmpty()) return defaultTable;

        String requestedTable = column.table();

        // Check if the requested table is the primary table
        if (defaultTable.equals(requestedTable)) {
            return defaultTable;
        }

        // Check if it's a known secondary table
        SecondaryTable st = tableMappings.get(requestedTable);
        if (st == null) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Table '" + requestedTable + "' specified in @Column.table is not defined as a secondary table. " +
                    "Falling back to primary table '" + defaultTable + "'");
            return defaultTable;
        }
        return st.name();
    }

    private boolean isRelationshipAttribute(AttributeDescriptor descriptor) {
        return descriptor.hasAnnotation(OneToOne.class) ||
               descriptor.hasAnnotation(OneToMany.class) ||
               descriptor.hasAnnotation(ManyToOne.class) ||
               descriptor.hasAnnotation(ManyToMany.class);
    }

    private void processRelationshipAttribute(AttributeDescriptor descriptor, EntityModel entity) {
        relationshipHandler.resolve(descriptor, entity);
    }


    /**
     * Check if the entity has any @MapsId attributes that require deferred processing
     */
    private boolean hasMapsIdAttributes(TypeElement typeElement, EntityModel entity) {
        List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);

        return descriptors.stream()
                .anyMatch(desc -> desc.hasAnnotation(MapsId.class) && isRelationshipAttribute(desc));
    }

    /**
     * Checks if dependencies are unresolved before processing @MapsId
     * Determines if retry is needed for temporary issues (checks only referenced entity readiness)
     */
    private boolean hasUnresolvedMapsIdDeps(TypeElement typeElement, EntityModel child) {
        // No point retrying already invalid entities
        if (!child.isValid()) {
            return false;
        }

        // Self PK is created/promoted in @MapsId promotion, exclude from retry condition
        // (Prevents deadlock: @MapsId-only PKs are promoted at this stage)

        // Check referenced entity dependencies for @MapsId relationship attributes
        List<AttributeDescriptor> mapsIdDescriptors = context.getCachedDescriptors(typeElement).stream()
                .filter(d -> d.hasAnnotation(MapsId.class) && isRelationshipAttribute(d))
                .toList();

        for (AttributeDescriptor descriptor : mapsIdDescriptors) {
            ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
            OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);

            // Resolve referenced entity type
            Optional<TypeElement> refElementOpt =
                    relationshipSupport.resolveTargetEntity(descriptor, manyToOne, oneToOne, null, null);

            if (refElementOpt.isEmpty()) {
                return true; // target TypeElement not yet available → retry needed
            }

            TypeElement refElement = refElementOpt.get();
            String refEntityName = refElement.getQualifiedName().toString();
            EntityModel refEntity = context.getSchemaModel().getEntities().get(refEntityName);

            // Referenced entity not yet registered
            if (refEntity == null) {
                return true; // target EntityModel not yet available → retry needed
            }

            // Referenced entity is in invalid state (permanent error)
            if (!refEntity.isValid()) {
                return false; // permanent failure → no retry
            }

            // Referenced entity's PK not yet determined
            if (context.findAllPrimaryKeyColumns(refEntity).isEmpty()) {
                return true; // target PK not determined → retry needed
            }
        }

        return false; // Ready → process promotion in this round
    }



    private ColumnModel resolveParentRef(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn a, int idx, TypeElement sourceElement) {
        if (!a.referencedColumnName().isEmpty()) {
            Optional<ColumnModel> found = parentPkCols.stream()
                    .filter(p -> p.getColumnName().equals(a.referencedColumnName()))
                    .findFirst();
            if (found.isEmpty()) {
                String availableColumns = parentPkCols.stream()
                        .map(ColumnModel::getColumnName)
                        .collect(java.util.stream.Collectors.joining(", "));
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "referencedColumnName '" + a.referencedColumnName() +
                        "' not found in parent primary key columns: [" + availableColumns + "]",
                        sourceElement);
                return null;
            }
            return found.get();
        }
        if (idx >= parentPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@PrimaryKeyJoinColumn index " + idx + " exceeds parent PK column count " + parentPkCols.size(),
                    sourceElement);
            return null;
        }
        return parentPkCols.get(idx);
    }

    private Map<String, SecondaryTable> buildTableMappings(EntityModel entity, List<SecondaryTable> secondaryTableList) {
        Map<String, SecondaryTable> tableMappings = new HashMap<>();
        tableMappings.put(entity.getTableName(), null); // Primary table
        for (SecondaryTable st : secondaryTableList) {
            tableMappings.put(st.name(), st);
        }
        return tableMappings;
    }

    private void ensureChildPkColumnsExist(
            EntityModel childEntity,
            String childTableName,
            List<String> childCols,              // Child-side column names (FK=PK)
            List<ColumnModel> parentPkCols) {    // Parent PK metadata in same order

        int n = childCols.size();
        for (int i = 0; i < n; i++) {
            String childColName = childCols.get(i);
            ColumnModel parentCol = parentPkCols.get(i);

            ColumnModel existing = childEntity.findColumn(childTableName, childColName);
            if (existing == null) {
                ColumnModel newCol = ColumnModel.builder()
                        .columnName(childColName)
                        .tableName(childTableName)
                        .isPrimaryKey(true)                 // or setPrimaryKey(true)
                        .isNullable(false)                  // PK is always NOT NULL
                        .javaType(parentCol.getJavaType())
                        .length(parentCol.getLength())
                        .precision(parentCol.getPrecision())
                        .scale(parentCol.getScale())
                        .defaultValue(parentCol.getDefaultValue())
                        .comment(parentCol.getComment())
                        .build();
                childEntity.putColumn(newCol);
            } else {
                existing.setPrimaryKey(true);
                existing.setNullable(false);              // Force PK to NOT NULL
                if (existing.getTableName() == null) {
                    existing.setTableName(childTableName);
                }
                // Supplement with parent values if type metadata is empty
                if (existing.getJavaType() == null) existing.setJavaType(parentCol.getJavaType());
                if (existing.getLength() == 0)   existing.setLength(parentCol.getLength());
                if (existing.getPrecision() == 0) existing.setPrecision(parentCol.getPrecision());
                if (existing.getScale() == 0)     existing.setScale(parentCol.getScale());
                if (existing.getDefaultValue() == null) existing.setDefaultValue(parentCol.getDefaultValue());
                if (existing.getComment() == null)      existing.setComment(parentCol.getComment());
            }
        }
    }

    private List<String> sorted(List<String> cols) {
        var c = new ArrayList<>(cols);
        Collections.sort(c, String.CASE_INSENSITIVE_ORDER);
        return c;
    }


}