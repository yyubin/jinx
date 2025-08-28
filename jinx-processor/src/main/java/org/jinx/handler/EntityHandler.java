package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.builtins.SecondaryTableAdapter;
import org.jinx.handler.builtins.TableAdapter;
import org.jinx.model.*;

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

    /**
     * Creates an EntityHandler with the required processing context and collaborator handlers.
     *
     * <p>All dependencies are injected to enable entity metadata extraction and coordination of
     * specialized processing (columns, embeds, constraints, generators, element collections and
     * relationships).</p>
     */
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
    }

    /**
     * Orchestrates building an EntityModel from a TypeElement and registers it in the processing schema.
     *
     * Performs end-to-end processing of an entity declaration: clears per-entity mappedBy state, creates and
     * registers the EntityModel, applies table metadata, generators, and constraints, registers secondary table
     * names, processes composite keys and all attributes (via AttributeDescriptor-driven handlers), establishes
     * secondary-table and JOINED-inheritance joins once primary keys are known, and enqueues the entity for
     * deferred MapsId/join processing when necessary.
     *
     * The method mutates the global ProcessingContext and the schema model (it may add the entity and related
     * metadata, or place the entity on the deferred queue). It may return early (no entity created) if validation
     * fails during creation or composite-key processing.
     *
     * @param typeElement the TypeElement representing the entity class to process
     */
    public void handle(TypeElement typeElement) {
        // Clear mappedBy visited set for each new entity to allow proper cycle detection
        context.clearMappedByVisited();
        
        // 1. 엔티티 기본 정보 설정
        EntityModel entity = createEntityModel(typeElement);
        if (entity == null) return;

        // 가등록: 부모/자식 간 상호 조회 가능하게
        context.getSchemaModel().getEntities().putIfAbsent(entity.getEntityName(), entity);

        // 2. 테이블 메타데이터 처리
        processTableMetadata(typeElement, entity);

        // 3. 시퀀스/테이블 제너레이터 처리
        processGenerators(typeElement);

        // 4. 제약조건 처리
        processEntityConstraints(typeElement, entity);

        // 5. (REMOVED) 상속 필드 주입 - AttributeDescriptor 기반 처리에서 통합됨

        // 6. 보조 테이블 이름 사전 등록 (검증용)
        List<SecondaryTable> secondaryTableAnns = collectSecondaryTables(typeElement);
        registerSecondaryTableNames(secondaryTableAnns, entity);

        // 7. 복합키 처리 (@EmbeddedId)
        if (!processCompositeKeys(typeElement, entity)) return;

        // 8. 필드 처리 (AttributeDescriptor 기반)
        processFieldsWithAttributeDescriptor(typeElement, entity);

        // 9. 보조 테이블 조인 및 상속 관계 처리 (PK 확정 후)
        processSecondaryTableJoins(secondaryTableAnns, typeElement, entity);
        processInheritanceJoin(typeElement, entity);
        
        // 10. Check for @MapsId attributes requiring deferred processing
        if (hasMapsIdAttributes(typeElement, entity)) {
            context.getDeferredEntities().offer(entity);
            context.getDeferredNames().add(entity.getEntityName());
        }
    }

    /**
     * Registers secondary table names from the provided @SecondaryTable annotations on the given entity.
     *
     * For each annotation a SecondaryTableModel is created using only the annotation's `name` and added
     * to the entity's secondary table list if a table with the same name is not already present.
     *
     * @param secondaryTablesAnns list of @SecondaryTable annotations to register
     * @param entity the EntityModel to which secondary table entries will be added (modified in place)
     */

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

    /**
     * Processes @SecondaryTable annotations for an entity: applies table-level metadata (schema/catalog/comment/constraints/indexes)
     * and creates the foreign-key joins from each secondary table to the entity's primary table.
     *
     * <p>If any secondary tables are present but the entity has no primary key columns, this method emits a compilation
     * error and marks the entity invalid. For each secondary table it applies the annotation's constraints/indexes and
     * invokes join processing to create the corresponding relationship of type SECONDARY_TABLE.</p>
     *
     * @param secondaryTables list of @SecondaryTable annotations collected for the entity
     * @param type the element being processed (used for diagnostic reporting)
     * @param entity the EntityModel being populated; may be marked invalid on error and will receive relationships/columns
     */
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

    /**
     * Processes a bounded snapshot of entities deferred for post-processing of JOINED-inheritance foreign keys and MapsId mappings.
     *
     * <p>This method iterates over the current size of the deferred-entity queue and for each entity:
     * - polls the entity from the queue (stops early if the queue becomes empty),
     * - skips entities already marked invalid,
     * - attempts to resolve the corresponding TypeElement and, if unresolved, logs an error and re-queues the entity,
     * - invokes processing of JOINED inheritance joins, and
     * - invokes processing of any {@code @MapsId} relationship attributes for the entity.</p>
     *
     * <p>Behavioral notes:
     * - Only a snapshot number of entities (the queue size at method entry) are processed to avoid unbounded re-processing
     *   and potential infinite loops; entities re-queued here will be retried in a later round.
     * - The method emits diagnostics when a TypeElement cannot be resolved and may re-enqueue entities for later processing.
     * - No value is returned; processing and side effects occur on the shared ProcessingContext and EntityModel instances.</p>
     */
    public void runDeferredJoinedFks() {
        int size = context.getDeferredEntities().size();
        for (int i = 0; i < size; i++) {
            EntityModel child = context.getDeferredEntities().poll();
            if (child == null) break;
            if (!child.isValid()) continue;

            TypeElement te = context.getElementUtils().getTypeElement(child.getEntityName());
            if (te == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Deferred processing: cannot resolve TypeElement for " + child.getEntityName() + " – re-queue");
                context.getDeferredEntities().offer(child);
                continue;
            }
            
            // Process JOINED inheritance
            processInheritanceJoin(te, child);
            
            // Process @MapsId attributes if any
            if (hasMapsIdAttributes(te, child)) {
                processMapsIdAttributes(te, child);
            }
            
            // 부모가 여전히 없으면 processInheritanceJoin 내부에서 다시 enqueue
            // 하지만 여기서는 '이번 라운드' 스냅샷만 처리해서 무한루프 방지
        }
    }


    /**
     * Create an EntityModel for the given type element, using the @Table name when provided.
     *
     * The entity name is the type's fully-qualified name. If the type is annotated with
     * {@code @Table} and a non-empty name is provided, that name is used as the table name;
     * otherwise the type's simple name is used.
     *
     * If an entity with the same fully-qualified name already exists in the current schema,
     * a compilation error is reported and this method returns {@code null} to indicate
     * creation was aborted (the existing entity is preserved).
     *
     * @return a new, valid EntityModel populated with entity and table names, or {@code null}
     *         if a duplicate entity was found and creation was aborted
     */
    private EntityModel createEntityModel(TypeElement typeElement) {
        Optional<Table> tableOpt = Optional.ofNullable(typeElement.getAnnotation(Table.class));
        String tableName = tableOpt.map(Table::name).filter(n -> !n.isEmpty())
                .orElse(typeElement.getSimpleName().toString());
        String entityName = typeElement.getQualifiedName().toString();

        // 중복 체크
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

    /**
     * Processes all constraint annotations declared on the given entity and applies them to the
     * entity's primary table.
     *
     * Delegates to processConstraints using the element that declared the entity and the entity's
     * collected ConstraintModel instances scoped to the entity's table.
     */
    private void processEntityConstraints(TypeElement typeElement, EntityModel entity) {
        processConstraints(typeElement, null,
                entity.getConstraints().values().stream().toList(),
                entity.getTableName());
    }

    

    /**
     * Processes JOINED-inheritance primary-key join from the nearest joined parent entity to the given child entity.
     *
     * <p>If the nearest superclass annotated with {@code @Entity} uses {@code InheritanceType.JOINED}, this
     * method resolves the parent entity model and primary key columns and delegates creation of the
     * corresponding primary-key join relationship. If the parent entity model is not yet available the
     * child entity is queued for deferred processing. If validation fails (missing parent PKs or invalid
     * parent entity), the child entity is marked invalid and a compile-time error is emitted.</p>
     *
     * @param type the element representing the child entity type
     * @param childEntity the child entity model to augment with the inheritance join
     */
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

        // 부모 엔티티 모델/PK 컬럼 조회
        EntityModel parentEntity = context.getSchemaModel().getEntities()
                .get(parentType.getQualifiedName().toString());
        String childName = childEntity.getEntityName();
        if (parentEntity == null) {
            if (context.getDeferredNames().contains(childName)) {
                return; // 이미 재시도 대기 중
            }
            context.getDeferredNames().add(childName);
            context.getDeferredEntities().add(childEntity); // 부모가 아직 처리되지 않음 -> 나중에 재시도
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

    // 다층 상속 방어 유틸
    private Optional<TypeElement> findNearestJoinedParentEntity(TypeElement type) {
        TypeMirror sup = type.getSuperclass();
        while (sup.getKind() == TypeKind.DECLARED) {
            TypeElement p = (TypeElement) ((DeclaredType) sup).asElement();
            if (p.getAnnotation(Entity.class) != null) {
                Inheritance inh = p.getAnnotation(Inheritance.class);
                if (inh != null && inh.strategy() == InheritanceType.JOINED) return Optional.of(p);
                return Optional.empty(); // 엔티티인데 JOINED가 아니면 여기서 종료
            }
            sup = p.getSuperclass();
        }
        return Optional.empty();
    }

    /**
     * Processes a join from this entity to another table by building and registering a foreign-key
     * RelationshipModel and ensuring the corresponding child primary-key columns exist.
     *
     * If `pkjcs` is empty, the parent's PK column names are used for both child and referenced columns.
     * If `pkjcs` is provided, it must match the number of parent PK columns; each entry's
     * `name()` becomes the child column or, when empty, the corresponding parent PK column name is used.
     * On validation failure the entity is marked invalid and the method returns false.
     *
     * Side effects:
     * - Adds a RelationshipModel to `entity.getRelationships()`.
     * - May create or update child table columns on the entity via ensureChildPkColumnsExist.
     * - Requests creation of a foreign-key index via the RelationshipHandler.
     * - Emits error messages through the processing context and may set `entity` invalid.
     *
     * @param tableName the name of the child table that will hold the foreign key
     * @param pkjcs the list of @PrimaryKeyJoinColumn annotations (may be empty)
     * @param type the element being processed (used for diagnostics)
     * @param entity the EntityModel to update with the created relationship and any new columns
     * @param parentPkCols the ordered list of parent primary-key ColumnModel instances referenced by the join
     * @param referencedTableName the name of the referenced (parent) table
     * @param relType the relationship type to record on the RelationshipModel
     * @return true if the join was successfully processed and registered; false on validation errors
     */
    private boolean processJoinTable(String tableName, List<PrimaryKeyJoinColumn> pkjcs,
                                  TypeElement type, EntityModel entity, List<ColumnModel> parentPkCols, String referencedTableName, RelationshipType relType) {
        List<String> childCols = new ArrayList<>();
        List<String> refCols = new ArrayList<>();

        if (pkjcs.isEmpty()) {
            // pkjcs가 없으면 부모 테이블의 PK를 그대로 상속받음
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
            try {
                for (int i = 0; i < pkjcs.size(); i++) {
                    PrimaryKeyJoinColumn a = pkjcs.get(i);
                    ColumnModel parentRef = resolveParentRef(parentPkCols, a, i);
                    String childCol = a.name().isEmpty() ? parentRef.getColumnName() : a.name();
                    childCols.add(childCol);
                    refCols.add(parentRef.getColumnName());
                }
            } catch (IllegalStateException ex) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Invalid @PrimaryKeyJoinColumn on " + type.getQualifiedName() + ": " + ex.getMessage(), type);
                entity.setValid(false);
                return false;
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

        // 보조 테이블의 경우엔 일반적으로 자식(보조) 테이블 쪽 컬럼을 생성해야 함
        ensureChildPkColumnsExist(entity, tableName, childCols, parentPkCols);
        
        // FK 인덱스 생성 (PK/UNIQUE로 커버되지 않은 경우에만)
        relationshipHandler.addForeignKeyIndex(entity, childCols, tableName);
        
        return true;
    }

    /**
     * Applies metadata from a TableLike source onto the given EntityModel.
     *
     * Copies schema, catalog, and comment when present. Imports constraint definitions,
     * overwriting any entity constraint with the same name. Imports index definitions but
     * skips any index whose name already exists on the entity (preserving the existing index).
     *
     * @param tableLike source of table-like metadata (e.g., @Table or secondary-table adapter)
     * @param entity target entity model to receive the metadata
     */
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


    /**
     * Delegate to the ConstraintHandler to process and register constraints declared on an element or its field for a specific table.
     *
     * @param element the element that declares the constraints (type or field)
     * @param fieldName the name of the field on the element these constraints apply to, or null/empty for element-level constraints
     * @param constraints the constraint models to process
     * @param tableName the target table name the constraints apply to (may be null to indicate the primary table)
     */
    private void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints, String tableName) {
        constraintHandler.processConstraints(element, fieldName, constraints, tableName);
    }

    /**
     * Collects all @SecondaryTable annotations present on the given type.
     *
     * <p>Supports both a single {@code @SecondaryTable} and the container {@code @SecondaryTables};
     * returns an empty list if none are present.</p>
     *
     * @param typeElement the type element to inspect for {@code SecondaryTable} annotations
     * @return a list of {@code SecondaryTable} annotations found on the element (never {@code null})
     */
    private List<SecondaryTable> collectSecondaryTables(TypeElement typeElement) {
        List<SecondaryTable> secondaryTableList = new ArrayList<>();
        SecondaryTable secondaryTable = typeElement.getAnnotation(SecondaryTable.class);
        SecondaryTables secondaryTables = typeElement.getAnnotation(SecondaryTables.class);
        if (secondaryTable != null) secondaryTableList.add(secondaryTable);
        if (secondaryTables != null) secondaryTableList.addAll(Arrays.asList(secondaryTables.value()));
        return secondaryTableList;
    }

    


    /**
     * Validates and processes composite primary key definitions for the given entity.
     *
     * <p>If an {@code @IdClass} annotation is present this method emits a compilation
     * error, marks the entity invalid, and returns {@code false}. Otherwise it
     * looks for an {@code @EmbeddedId} attribute (via cached AttributeDescriptor
     * data) and delegates its processing to the embedded handler. Returns {@code true}
     * when processing completes (even if no {@code @EmbeddedId} is found).
     *
     * @param typeElement the source element being processed
     * @param entity the EntityModel to update (may be marked invalid or modified by embedded processing)
     * @return {@code true} when composite key processing succeeded or was not needed; {@code false} when an unsupported {@code @IdClass} was found
     */
    private boolean processCompositeKeys(TypeElement typeElement, EntityModel entity) {
        IdClass idClass = typeElement.getAnnotation(IdClass.class);
        if (idClass != null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "IdClass is not supported. Use @EmbeddedId instead for composite primary keys.",
                typeElement);
            entity.setValid(false);
            return false;
        }

        // ✅ Descriptor 기반으로 EmbeddedId 탐색
        AttributeDescriptor embeddedId = context.getCachedDescriptors(typeElement).stream()
            .filter(d -> d.hasAnnotation(EmbeddedId.class))
            .findFirst()
            .orElse(null);

        if (embeddedId != null) {
            embeddedHandler.processEmbeddedId(embeddedId, entity, new HashSet<>());
        }
        return true;
    }



    /**
     * Processes all mapped attributes of the given entity using cached AttributeDescriptor metadata.
     *
     * For the element's access strategy this method retrieves the cached descriptors, builds a map
     * of primary/secondary table names, and dispatches each descriptor to the appropriate handler
     * (column, embedded, element-collection, or relationship) via processAttributeDescriptor.
     *
     * The provided EntityModel is mutated: columns, relationships, embedded mappings, and related
     * metadata may be added or updated as a result of processing.
     *
     * @param typeElement the source type element whose attributes are being processed
     * @param entity the EntityModel to populate; modified in place
     */
    public void processFieldsWithAttributeDescriptor(TypeElement typeElement, EntityModel entity) {
        // Get cached AttributeDescriptor list based on Access strategy
        List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);
        
        Map<String, SecondaryTable> tableMappings = buildTableMappings(entity, collectSecondaryTables(typeElement));
        
        for (AttributeDescriptor descriptor : descriptors) {
            processAttributeDescriptor(descriptor, entity, tableMappings);
        }
    }
    
    /**
     * Dispatches processing for a single attribute based on its JPA annotations.
     *
     * <p>Routes to the appropriate handler:
     * - ElementCollection -> elementCollectionHandler.processElementCollection
     * - Embedded -> embeddedHandler.processEmbedded
     * - Relationship (OneToOne/OneToMany/ManyToOne/ManyToMany) -> processRelationshipAttribute
     * - EmbeddedId -> intentionally ignored here (handled elsewhere)
     * - otherwise -> processRegularAttribute
     *
     * @param descriptor a descriptor describing the attribute and its annotations
     * @param entity the entity model being populated
     * @param tableMappings map of table name to SecondaryTable annotation (primary table name maps to null); used when determining the target table for regular column mappings
     */
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
    
    /**
     * Process a non-relationship, non-embedded attribute: create a ColumnModel for the attribute
     * targeted at the appropriate table and add it to the entity if not already present.
     *
     * The target table is resolved from the attribute's @Column.table (falling back to the
     * entity's primary table and validating against known secondary tables). A ColumnModel is
     * built with an explicit table override to avoid validation conflicts, and stored in the
     * entity using table-aware column storage.
     *
     * @param descriptor   attribute metadata (annotations and element information) describing the field/column
     * @param entity       entity model to which the created ColumnModel will be added
     * @param tableMappings mapping of table names (primary and secondary) to their SecondaryTable annotations; used to validate/resolve the target table
     */
    private void processRegularAttribute(AttributeDescriptor descriptor, EntityModel entity, Map<String, SecondaryTable> tableMappings) {
        Column column = descriptor.getAnnotation(Column.class);
        String targetTable = determineTargetTableFromDescriptor(column, tableMappings, entity.getTableName());
        
        // Create column with pre-determined target table to avoid validation conflicts
        Map<String, String> overrides = Collections.singletonMap("tableName", targetTable);
        ColumnModel columnModel = columnHandler.createFromAttribute(descriptor, entity, overrides);
        if (columnModel != null) {
            // Use table-aware column storage to prevent primary/secondary table column conflicts
            if (!entity.hasColumn(targetTable, columnModel.getColumnName())) {
                entity.putColumn(columnModel);
            }
        }
    }
    
    /**
     * Determine which table name a column should target (primary or a named secondary table).
     *
     * If the Column annotation is null or its table name is empty, the primary table (defaultTable)
     * is returned. If the Column.table equals the primary table name, defaultTable is returned.
     * If Column.table names a known secondary table, that secondary table's declared name is returned.
     * If Column.table names an unknown table, a warning is emitted and the primary table is returned.
     *
     * @param column the @Column annotation instance for the attribute (may be null)
     * @param tableMappings map of declared table names to their SecondaryTable annotation (includes primary mapped to null)
     * @param defaultTable the primary table name to use as a fallback
     * @return the resolved table name (either defaultTable or the declared secondary table name)
     */
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
    
    /**
     * Returns true when the given attribute descriptor represents a JPA relationship
     * (OneToOne, OneToMany, ManyToOne, or ManyToMany).
     *
     * @param descriptor the attribute descriptor to inspect
     * @return true if the descriptor is annotated with a JPA relationship annotation, false otherwise
     */
    private boolean isRelationshipAttribute(AttributeDescriptor descriptor) {
        return descriptor.hasAnnotation(OneToOne.class) ||
               descriptor.hasAnnotation(OneToMany.class) ||
               descriptor.hasAnnotation(ManyToOne.class) ||
               descriptor.hasAnnotation(ManyToMany.class);
    }
    
    /**
     * Processes a relationship attribute descriptor by delegating resolution and registration to the RelationshipHandler.
     *
     * @param descriptor descriptor describing the attribute (relationship) to resolve
     * @param entity the entity model to which the resolved relationship should be applied
     */
    private void processRelationshipAttribute(AttributeDescriptor descriptor, EntityModel entity) {
        relationshipHandler.resolve(descriptor, entity);
    }


    /**
     * Returns true if the given entity contains any relationship attributes annotated with {@link MapsId}
     * that require deferred processing.
     *
     * Looks up cached attribute descriptors for the element and checks whether any descriptor both
     * has a {@code @MapsId} annotation and represents a relationship (OneToOne/OneToMany/ManyToOne/ManyToMany).
     *
     * @param typeElement the element representing the entity type
     * @param entity the EntityModel being processed
     * @return {@code true} if at least one relationship attribute is annotated with {@code @MapsId}; {@code false} otherwise
     */
    private boolean hasMapsIdAttributes(TypeElement typeElement, EntityModel entity) {
        List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);
        
        return descriptors.stream()
                .anyMatch(desc -> desc.hasAnnotation(MapsId.class) && isRelationshipAttribute(desc));
    }
    
    /**
     * Performs deferred processing of any relationship attributes annotated with {@link MapsId}.
     *
     * Scans the cached AttributeDescriptor list for the given type and invokes
     * {@link #processMapsIdAttribute(AttributeDescriptor, EntityModel)} for each
     * relationship attribute annotated with {@link MapsId}. This is intended to
     * be run after primary keys and join metadata have been established.
     *
     * @param typeElement the entity element whose attributes are being inspected
     * @param entity the EntityModel being updated as MapsId attributes are processed
     */
    public void processMapsIdAttributes(TypeElement typeElement, EntityModel entity) {
        List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);
        
        for (AttributeDescriptor descriptor : descriptors) {
            if (descriptor.hasAnnotation(MapsId.class) && isRelationshipAttribute(descriptor)) {
                processMapsIdAttribute(descriptor, entity);
            }
        }
    }
    
    /**
     * Processes a single relationship attribute annotated with @MapsId, resolving the
     * MapsId value and (eventually) promoting the relationship's foreign key columns to
     * primary-key status on the owning entity.
     *
     * <p>Current implementation only resolves the annotation value and emits a NOTE via
     * the processing messager. The actual PK-promotion logic is TODO and will mirror the
     * behavior used when establishing to-one relationships (promoting FK columns to PKs
     * and adjusting nullability/metadata accordingly).</p>
     *
     * @param descriptor the attribute descriptor for the relationship (should represent a relationship attribute that carries @MapsId)
     * @param entity the entity model that owns the attribute and whose schema may be modified when MapsId processing is implemented
     */
    private void processMapsIdAttribute(AttributeDescriptor descriptor, EntityModel entity) {
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        String mapsIdValue = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : null;
        
        // Find FK columns created by the relationship
        // Promote them to PK status based on @MapsId specification
        // This logic would be similar to what's in RelationshipHandler.processToOneRelationship
        
        // TODO: Implement the PK promotion logic
        // For now, just mark that this entity needs MapsId processing
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing @MapsId for attribute: " + descriptor.name() + " (value=" + mapsIdValue + ")",
                descriptor.elementForDiagnostics());
    }

    /**
     * Resolve a parent primary-key column referenced by a {@code @PrimaryKeyJoinColumn}.
     *
     * <p>If the {@code referencedColumnName} is present on the annotation, this method looks up
     * the matching column in {@code parentPkCols} by column name. Otherwise it uses {@code idx}
     * as a positional index into {@code parentPkCols}.</p>
     *
     * @param parentPkCols the list of parent primary-key columns to resolve from
     * @param a the {@code PrimaryKeyJoinColumn} annotation providing either a referenced column name
     * @param idx the fallback positional index to use when no referenced column name is specified
     * @return the matching parent {@code ColumnModel}
     * @throws IllegalStateException if a named referenced column is not found or if {@code idx}
     *         is out of range for {@code parentPkCols}
     */
    private ColumnModel resolveParentRef(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn a, int idx) {
        if (!a.referencedColumnName().isEmpty()) {
            return parentPkCols.stream()
                    .filter(p -> p.getColumnName().equals(a.referencedColumnName()))
                    .findFirst()
                    .orElseThrow(() -> {
                        String availableColumns = parentPkCols.stream()
                                .map(ColumnModel::getColumnName)
                                .collect(java.util.stream.Collectors.joining(", "));
                        return new IllegalStateException("referencedColumnName '" + a.referencedColumnName() + 
                                "' not found in parent primary key columns: [" + availableColumns + "]");
                    });
        }
        if (idx >= parentPkCols.size()) {
            throw new IllegalStateException("@PrimaryKeyJoinColumn index " + idx + " exceeds parent PK column count " + parentPkCols.size());
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

    /**
     * Ensures the given child table has primary-key columns corresponding to the provided child column names,
     * creating missing columns or updating existing ones to be primary, non-null, and to inherit type/size/metadata
     * from the corresponding parent primary-key columns.
     *
     * <p>The lists childCols and parentPkCols are positional: each entry in childCols corresponds to the parent
     * primary-key ColumnModel at the same index in parentPkCols. For each pair the method either:
     * - creates a new ColumnModel on the child entity with PK=true, NOT NULL, table set to childTableName, and
     *   copied type/length/precision/scale/default/comment from the parent column; or
     * - updates an existing column to set it as primary, non-null, assign the table name if missing, and fill any
     *   missing type/size/default/comment metadata from the parent column.</p>
     *
     * <p>Side effects: mutates the provided childEntity by adding or updating ColumnModel instances.</p>
     *
     * @param childEntity   the entity model to modify
     * @param childTableName the name of the table on the child side where the PK columns must exist
     * @param childCols     ordered list of child column names that should act as PKs (these are typically FK columns)
     * @param parentPkCols  ordered list of parent primary-key ColumnModel entries; must align positionally with childCols
     */
    private void ensureChildPkColumnsExist(
            EntityModel childEntity,
            String childTableName,
            List<String> childCols,              // 자식 측 컬럼명들 (FK=PK)
            List<ColumnModel> parentPkCols) {    // 동일 순서의 부모 PK 메타

        int n = childCols.size();
        for (int i = 0; i < n; i++) {
            String childColName = childCols.get(i);
            ColumnModel parentCol = parentPkCols.get(i);

            ColumnModel existing = childEntity.findColumn(childTableName, childColName);
            if (existing == null) {
                ColumnModel newCol = ColumnModel.builder()
                        .columnName(childColName)
                        .tableName(childTableName)
                        .isPrimaryKey(true)                 // 또는 setPrimaryKey(true)
                        .isNullable(false)                  // PK는 NOT NULL 고정
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
                existing.setNullable(false);              // PK는 NOT NULL로 강제
                if (existing.getTableName() == null) {
                    existing.setTableName(childTableName);
                }
                // 타입 메타가 비어있다면 부모 값 보강
                if (existing.getJavaType() == null) existing.setJavaType(parentCol.getJavaType());
                if (existing.getLength() == 0)   existing.setLength(parentCol.getLength());
                if (existing.getPrecision() == 0) existing.setPrecision(parentCol.getPrecision());
                if (existing.getScale() == 0)     existing.setScale(parentCol.getScale());
                if (existing.getDefaultValue() == null) existing.setDefaultValue(parentCol.getDefaultValue());
                if (existing.getComment() == null)      existing.setComment(parentCol.getComment());
            }
        }
    }


}