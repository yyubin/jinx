package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.builtins.SecondaryTableAdapter;
import org.jinx.handler.builtins.TableAdapter;
import org.jinx.handler.relationship.RelationshipSupport;
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
                // (like JOINED) which should have been handled already. We can remove it.
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
        Set<String> seen = new java.util.HashSet<>();
        TypeMirror sup = type.getSuperclass();

        while (sup.getKind() == TypeKind.DECLARED) {
            TypeElement p = (TypeElement) ((DeclaredType) sup).asElement();
            String qn = p.getQualifiedName().toString();

            if (!seen.add(qn)) {
                // 선택: warning으로 남기고 중단
                context.getMessager().printMessage(
                        javax.tools.Diagnostic.Kind.WARNING,
                        "Detected inheritance cycle near: " + qn + " while scanning for JOINED parent of " +
                                type.getQualifiedName()
                );
                break;
            }

            // 엔티티가 아니면 계속 상위로
            if (p.getAnnotation(jakarta.persistence.Entity.class) == null) {
                sup = p.getSuperclass();
                continue;
            }

            // 엔티티면 상속 전략 확인
            jakarta.persistence.Inheritance inh = p.getAnnotation(jakarta.persistence.Inheritance.class);
            if (inh != null) {
                switch (inh.strategy()) {
                    case JOINED:
                        // 가장 가까운 JOINED 부모 반환
                        return Optional.of(p);
                    case SINGLE_TABLE:
                    case TABLE_PER_CLASS:
                        // JOINED 탐색과 충돌 → 에러 및 종료
                        context.getMessager().printMessage(
                                javax.tools.Diagnostic.Kind.ERROR,
                                "Found explicit inheritance strategy '" + inh.strategy() +
                                        "' at '" + qn + "' while searching for a JOINED parent of '" +
                                        type.getQualifiedName() + "'. Mixed strategies in the same hierarchy are not supported.",
                                p
                        );
                        return Optional.empty();
                    default:
                        // 미래 확장 대비: 그냥 계속
                        break;
                }
            }
            // 명시적 @Inheritance 없으면 계속 상위로
            sup = p.getSuperclass();
        }
        return Optional.empty();
    }

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
        relationshipSupport.addForeignKeyIndex(entity, childCols, tableName);

        return true;
    }

    // 기존 private 메서드들 (변경 없음)
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

        // 1) 컬럼 생성 (테이블 미리 주입)
        Map<String, String> overrides = Collections.singletonMap("tableName", targetTable);
        ColumnModel col = columnHandler.createFromAttribute(descriptor, entity, overrides);
        if (col == null) return;

        if (col.getTableName() == null || col.getTableName().isEmpty()) {
            col.setTableName(targetTable);
        }

        // 2) 테이블 인식 컬럼 반영 (중복 시 메타 보강)
        ColumnModel existing = entity.findColumn(targetTable, col.getColumnName());
        if (existing == null) {
            entity.putColumn(col);
        } else {
            // 필요한 경우만 최소 보강(옵션)
            if (existing.getJavaType() == null && col.getJavaType() != null) existing.setJavaType(col.getJavaType());
            if (existing.getLength() == 0 && col.getLength() > 0) existing.setLength(col.getLength());
            if (existing.getPrecision() == 0 && col.getPrecision() > 0) existing.setPrecision(col.getPrecision());
            if (existing.getScale() == 0 && col.getScale() > 0) existing.setScale(col.getScale());
            if (existing.getDefaultValue() == null && col.getDefaultValue() != null) existing.setDefaultValue(col.getDefaultValue());
            if (existing.getComment() == null && col.getComment() != null) existing.setComment(col.getComment());
        }

        // 3) 속성 단 제약 수집
        List<ConstraintModel> out = new ArrayList<>();
        // descriptor의 소스 위치로 진단 매핑
        processConstraints(descriptor.elementForDiagnostics(), col.getColumnName(), out, targetTable);

        // 4) @Column(unique=true) → UNIQUE 제약 (PK/기존 UNIQUE로 이미 커버되면 생략)
        if (columnAnn != null && columnAnn.unique()) {
            List<String> cols = List.of(col.getColumnName());
            boolean covered = entity.getConstraints().values().stream().anyMatch(c ->
                    targetTable.equals(c.getTableName()) &&
                            (c.getType() == ConstraintType.PRIMARY_KEY || c.getType() == ConstraintType.UNIQUE) &&
                            c.getColumns().equals(cols)
            );
            if (!covered) {
                String uqName = context.getNaming().uqName(targetTable, cols);
                out.add(ConstraintModel.builder()
                        .name(uqName)
                        .type(ConstraintType.UNIQUE)
                        .tableName(targetTable)
                        .columns(cols)
                        .build());
            }
        }

        // 5) 제약 병합
        // 5-1) out 내부 중복(테이블/타입/컬럼 동일) 제거
        Map<String, ConstraintModel> dedup = new LinkedHashMap<>();
        for (ConstraintModel c : out) {
            String key = c.getType() + "|" + c.getTableName() + "|" + String.join(",", c.getColumns());
            dedup.putIfAbsent(key, c);
        }
        for (ConstraintModel c : dedup.values()) {
            // INDEX는 IndexModel로
            if (c.getType() == ConstraintType.INDEX) {
                String ixName = (c.getName() == null || c.getName().isEmpty())
                        ? context.getNaming().ixName(c.getTableName(), c.getColumns())
                        : c.getName();
                entity.getIndexes().putIfAbsent(ixName, IndexModel.builder()
                        .indexName(ixName)
                        .tableName(c.getTableName())
                        .columnNames(c.getColumns())
                        .isUnique(false)
                        .build());
                continue;
            }

            // 이름 보정
            String name = c.getName();
            if (name == null || name.isEmpty()) {
                name = switch (c.getType()) {
                    case UNIQUE -> context.getNaming().uqName(c.getTableName(), c.getColumns());
                    case CHECK  -> context.getNaming().ckName(c.getTableName(), c.getColumns());
                    case NOT_NULL -> context.getNaming().nnName(c.getTableName(), c.getColumns());
                    case PRIMARY_KEY -> context.getNaming().pkName(c.getTableName(), c.getColumns());
                    case DEFAULT -> context.getNaming().dfName(c.getTableName(), c.getColumns());
                    default -> context.getNaming().autoName(c.getTableName(), c.getColumns());
                };
                c.setName(name);
            }

            // 최종 반영
            entity.getConstraints().putIfAbsent(name, c);
        }
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
     * @MapsId 처리 전에 의존성이 해결되지 않은 상태인지 확인
     * 재시도가 필요한 일시적 문제인지 판단 (참조 엔티티 준비 상태만 확인)
     */
    private boolean hasUnresolvedMapsIdDeps(TypeElement typeElement, EntityModel child) {
        // 이미 invalid된 엔티티는 재시도 의미 없음
        if (!child.isValid()) {
            return false;
        }

        // 자기 자신의 PK는 @MapsId 승격에서 생성/승격하므로 재시도 조건에서 제외
        // (교착 위험 방지: @MapsId 전용 PK의 경우 이 단계에서 승격됨)

        // @MapsId 관계 속성들의 참조 엔티티 의존성 확인
        List<AttributeDescriptor> mapsIdDescriptors = context.getCachedDescriptors(typeElement).stream()
                .filter(d -> d.hasAnnotation(MapsId.class) && isRelationshipAttribute(d))
                .toList();

        for (AttributeDescriptor descriptor : mapsIdDescriptors) {
            ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
            OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);

            // 참조 엔티티 타입 해석
            Optional<TypeElement> refElementOpt =
                    relationshipSupport.resolveTargetEntity(descriptor, manyToOne, oneToOne, null, null);

            if (refElementOpt.isEmpty()) {
                return true; // target TypeElement 아직 없음 → 재시도 필요
            }

            TypeElement refElement = refElementOpt.get();
            String refEntityName = refElement.getQualifiedName().toString();
            EntityModel refEntity = context.getSchemaModel().getEntities().get(refEntityName);

            // 참조 엔티티가 아직 등록되지 않음
            if (refEntity == null) {
                return true; // target EntityModel 아직 없음 → 재시도 필요
            }

            // 참조 엔티티가 invalid 상태 (영구 오류)
            if (!refEntity.isValid()) {
                return false; // 영구 실패 → 재시도X
            }

            // 참조 엔티티의 PK가 아직 확정되지 않음
            if (context.findAllPrimaryKeyColumns(refEntity).isEmpty()) {
                return true; // target PK 미확정 → 재시도 필요
            }
        }

        return false; // 준비 완료 → 이번 라운드에서 승격 처리
    }



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