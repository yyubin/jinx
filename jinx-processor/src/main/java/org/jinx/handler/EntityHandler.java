package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
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

    public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler,
                         ConstraintHandler constraintHandler, SequenceHandler sequenceHandler,
                         ElementCollectionHandler elementCollectionHandler, TableGeneratorHandler tableGeneratorHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.constraintHandler = constraintHandler;
        this.sequenceHandler = sequenceHandler;
        this.elementCollectionHandler = elementCollectionHandler;
        this.tableGeneratorHandler = tableGeneratorHandler;
    }

    public void handle(TypeElement typeElement) {
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

        // 5. 상속 필드부터 주입 (PK 후보 포함 가능)
        processMappedSuperclasses(typeElement, entity);

        // 6. 복합키 처리
        if (!processCompositeKeys(typeElement, entity)) return;

        // 7. 필드 처리
        processEntityFields(typeElement, entity);

        // 8. 보조 테이블 처리 및 상속 관계 처리 -> PK가 확정된 뒤에 FK 생성
        processJoinedTables(typeElement, entity);

        // @MapsId 기반 PK 승격 반영 이후(2차 패스)로 검증 시점(PK)을 지연
    }

    public void runDeferredJoinedFks() {
        int size = context.getDeferredEntities().size();
        for (int i = 0; i < size; i++) {
            EntityModel child = context.getDeferredEntities().poll();
            if (child == null) break;
            if (!child.isValid()) continue;

            TypeElement te = context.getElementUtils().getTypeElement(child.getEntityName());
            if (te == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Deferred JOINED: cannot resolve TypeElement for " + child.getEntityName() + " – re-queue");
                context.getDeferredEntities().offer(child);
                continue;
            }
            processInheritanceJoin(te, child);
            // 부모가 여전히 없으면 processInheritanceJoin 내부에서 다시 enqueue
            // 하지만 여기서는 '이번 라운드' 스냅샷만 처리해서 무한루프 방지
        }
    }

    private void processMappedSuperclasses(TypeElement typeElement, EntityModel entity) {
        for (TypeElement ms : getMappedSuperclasses(typeElement)) {
            processMappedSuperclass(ms, entity);
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

    /**
     * 보조 테이블(SecondaryTable)과 상속 관계(JOINED)의 조인 테이블을 통합 처리합니다.
     */
    private void processJoinedTables(TypeElement type, EntityModel entity) {
        List<SecondaryTable> secondaryTables = collectSecondaryTables(type);

        for (SecondaryTable t: secondaryTables) {
            processTableLike(new SecondaryTableAdapter(t, context), entity);
        }

        processInheritanceJoin(type, entity);

        List<ColumnModel> primaryKeyColumns = context.findAllPrimaryKeyColumns(entity);

        // FIX: SecondaryTable을 처리하기 전에 주 테이블의 PK가 있는지 확인
        if (!secondaryTables.isEmpty() && primaryKeyColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity with @SecondaryTable must have a primary key (@Id or @EmbeddedId).", type);
            entity.setValid(false);
            return; // PK가 없으면 SecondaryTable 처리를 진행하지 않음
        }

        for (SecondaryTable t : secondaryTables) {
            // 변수명을 parentPkCols에서 primaryKeyColumns로 명확하게 변경
            processJoinTable(t.name(), Arrays.asList(t.pkJoinColumns()), type, entity, primaryKeyColumns, entity.getTableName(), RelationshipType.SECONDARY_TABLE);
        }
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
            for (int i = 0; i < pkjcs.size(); i++) {
                PrimaryKeyJoinColumn a = pkjcs.get(i);
                ColumnModel parentRef = resolveParentRef(parentPkCols, a, i);
                String childCol = a.name().isEmpty() ? parentRef.getColumnName() : a.name();
                childCols.add(childCol);
                refCols.add(parentRef.getColumnName());
            }
        }

        String fkName = context.getNaming().fkName(
                tableName, childCols, referencedTableName, refCols);

        RelationshipModel rel = RelationshipModel.builder()
                .type(relType)
                .columns(childCols)
                .referencedTable(referencedTableName)
                .referencedColumns(refCols)
                .constraintName(fkName)
                .build();
        entity.getRelationships().put(rel.getConstraintName(), rel);

        // 보조 테이블의 경우엔 일반적으로 자식(보조) 테이블 쪽 컬럼을 생성해야 함
        ensureChildPkColumnsExist(entity, tableName, childCols, parentPkCols);
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

    private void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        elementCollectionHandler.processElementCollection(field, ownerEntity);
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

    private VariableElement getEmbeddedIdField(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD && e.getAnnotation(EmbeddedId.class) != null)
                .map(VariableElement.class::cast)
                .findFirst().orElse(null);
    }

    private void processEmbeddedId(VariableElement embeddedIdField, EntityModel entity) {
        embeddedHandler.processEmbedded(embeddedIdField, entity, new HashSet<>());
        TypeElement embeddableType = (TypeElement) ((DeclaredType) embeddedIdField.asType()).asElement();
        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;
                String columnName = field.getSimpleName().toString();
                ColumnModel column = entity.getColumns().get(columnName);
                if (column != null) {
                    column.setPrimaryKey(true);
                }
            }
        }
    }

    private List<TypeElement> getMappedSuperclasses(TypeElement typeElement) {
        List<TypeElement> superclasses = new ArrayList<>();
        TypeMirror superclass = typeElement.getSuperclass();
        while (superclass != null && superclass.getKind() == TypeKind.DECLARED) {
            TypeElement superElement = (TypeElement) ((DeclaredType) superclass).asElement();
            if (superElement.getAnnotation(MappedSuperclass.class) != null) {
                superclasses.add(superElement);
            }
            superclass = superElement.getSuperclass();
        }
        Collections.reverse(superclasses);
        return superclasses;
    }

    private void processMappedSuperclass(TypeElement superclass, EntityModel entity) {
        for (Element enclosed : superclass.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD && enclosed.getAnnotation(Transient.class) == null) {
                VariableElement field = (VariableElement) enclosed;
                ColumnModel column = columnHandler.createFrom(field, Collections.emptyMap());
                if (column != null) {
                    entity.getColumns().putIfAbsent(column.getColumnName(), column);
                }
            }
        }
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

        VariableElement embeddedIdField = getEmbeddedIdField(typeElement);
        if (embeddedIdField != null) {
            processEmbeddedId(embeddedIdField, entity);
        }
        return true;
    }

    private void processEntityFields(TypeElement typeElement, EntityModel entity) {
        Map<String, SecondaryTable> tableMappings = buildTableMappings(entity, collectSecondaryTables(typeElement));
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (shouldSkipField(enclosed)) continue;
            VariableElement field = (VariableElement) enclosed;
            processField(field, entity, tableMappings);
        }
    }

    private boolean shouldSkipField(Element element) {
        if (element.getKind() != ElementKind.FIELD) return true;
        Set<Modifier> mods = element.getModifiers();
        if (mods.contains(Modifier.TRANSIENT) || mods.contains(Modifier.STATIC)) return true;
        if (element.getAnnotation(Transient.class) != null) return true;
        return false;
    }

    private void processField(VariableElement field, EntityModel entity, Map<String, SecondaryTable> tableMappings) {
        if (field.getAnnotation(ElementCollection.class) != null) {
            processElementCollection(field, entity);
        } else if (field.getAnnotation(Embedded.class) != null) {
            embeddedHandler.processEmbedded(field, entity, new HashSet<>());
        } else if (field.getAnnotation(EmbeddedId.class) == null) {
            processRegularField(field, entity, tableMappings);
        }
    }

    private void processRegularField(VariableElement field, EntityModel entity, Map<String, SecondaryTable> tableMappings) {
        Column column = field.getAnnotation(Column.class);
        String targetTable = determineTargetTable(column, tableMappings, entity.getTableName());
        ColumnModel columnModel = columnHandler.createFrom(field, Collections.emptyMap());
        if (columnModel != null) {
            columnModel.setTableName(targetTable);
            entity.getColumns().putIfAbsent(columnModel.getColumnName(), columnModel);
        }
    }

    private String determineTargetTable(Column column, Map<String, SecondaryTable> tableMappings, String defaultTable) {
        if (column == null || column.table().isEmpty()) return defaultTable;
        SecondaryTable st = tableMappings.get(column.table());
        if (st == null) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Unknown table '" + column.table() + "' in @Column.table; falling back to '" + defaultTable + "'");
            return defaultTable;
        }
        return st.name();
    }

    private ColumnModel resolveParentRef(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn a, int idx) {
        if (!a.referencedColumnName().isEmpty()) {
            return parentPkCols.stream()
                    .filter(p -> p.getColumnName().equals(a.referencedColumnName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Bad referencedColumnName: " + a.referencedColumnName()));
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

            ColumnModel existing = childEntity.getColumns().get(childColName);
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
                childEntity.getColumns().put(childColName, newCol);
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