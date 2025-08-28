package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.*;

import org.jinx.descriptor.AttributeDescriptor;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;

public class RelationshipHandler {
    private final ProcessingContext context;

    /**
     * Creates a RelationshipHandler tied to the given processing context.
     *
     * The provided ProcessingContext is used for schema access, messaging (diagnostics),
     * and naming utilities throughout relationship resolution.
     */
    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Resolves JPA-like relationship annotations placed on an attribute and registers the resulting
     * relationship model and any required DDL artifacts on the provided entity model.
     *
     * <p>Inspects the attribute descriptor for relationship annotations in the following priority:
     * ManyToOne / OneToOne, OneToMany, ManyToMany. For to-one annotations (ManyToOne or OneToOne)
     * it delegates to processing for to-one relationships. For OneToMany and ManyToMany it distinguishes
     * between owning (no `mappedBy`) and inverse (with `mappedBy`) sides and delegates to the
     * appropriate handlers to create foreign keys, join tables, or record inverse-side logical
     * relationships without emitting DDL.</p>
     *
     * <p>Side effects:
     * - May add columns, relationships, constraints, and indexes to the given EntityModel.
     * - May emit diagnostics (errors/warnings/notes) via the processing context.</p>
     *
     * @param descriptor   attribute descriptor containing the element and its relationship annotations
     *                     (used to determine relationship type and for diagnostic locations)
     * @param entityModel  the owning entity model to update with relationship and DDL artifacts
     */
    public void resolve(AttributeDescriptor descriptor, EntityModel entityModel) {
        ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
        ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);

        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(descriptor, entityModel, manyToOne, oneToOne);
            return;
        }

        if (oneToMany != null) {
            if (oneToMany.mappedBy().isEmpty()) {
                // Owning side: generate DDL artifacts (FK or join table)
                JoinTable jt = descriptor.getAnnotation(JoinTable.class);
                JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
                JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
                boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);

                if (jt != null && hasJoinColumn) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Cannot use both @JoinTable and @JoinColumn(s) with @OneToMany.", descriptor.elementForDiagnostics());
                    return;
                }

                if (hasJoinColumn) {
                    processUnidirectionalOneToMany_FK(descriptor, entityModel, oneToMany);
                } else {
                    processUnidirectionalOneToMany_JoinTable(descriptor, entityModel, oneToMany);
                }
            } else {
                // Inverse side: no DDL artifacts, only logical relationship tracking
                processInverseSideOneToMany(descriptor, entityModel, oneToMany);
            }
            return;
        }

        if (manyToMany != null) {
            if (manyToMany.mappedBy().isEmpty()) {
                // Owning side: generate DDL artifacts (join table)
                processOwningManyToMany(descriptor, entityModel, manyToMany);
            } else {
                // Inverse side: no DDL artifacts, only logical relationship tracking
                processInverseSideManyToMany(descriptor, entityModel, manyToMany);
            }
        }
    }

    /**
     * Compatibility overload that wraps a VariableElement in a FieldAttributeDescriptor and delegates to {@link #resolve(AttributeDescriptor, EntityModel)}.
     *
     * @param field the field element to resolve as an attribute
     * @param ownerEntity the entity model that owns the field
     */
    public void resolve(VariableElement field, EntityModel ownerEntity) {
        AttributeDescriptor fieldAttr = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
        resolve(fieldAttr, ownerEntity);
    }

    /**
     * Resolve an owning to-one relationship (ManyToOne or OneToOne) and create the necessary
     * foreign-key columns, constraints, and RelationshipModel on the owning entity.
     *
     * <p>When this method runs on the owning side it:
     * - determines the referenced entity and its primary key columns;
     * - validates and maps {@code @JoinColumn(s)} (including composite keys) or synthesizes FK column names;
     * - honors {@code @MapsId} to mark FK columns as primary keys when required;
     * - creates or updates ColumnModel entries on the owning entity (type/nullable/PK adjustments);
     * - computes and validates the FK constraint name and foreign key constraint mode (including NO_CONSTRAINT);
     * - registers a RelationshipModel (MANY_TO_ONE or ONE_TO_ONE) on the owner and optionally
     *   adds a UNIQUE constraint when {@code @JoinColumn(unique=true)} is present;
     * - ensures an index exists for the FK columns.
     *
     * <p>If the relationship is the inverse side (OneToOne with mappedBy), or if the referenced
     * entity cannot be resolved or lacks a primary key, the method returns without creating DDL.
     *
     * @param attr descriptor for the attribute carrying the relationship annotations; used for annotation
     *             values and diagnostics
     * @param ownerEntity the entity model that owns the relationship and where FK columns/constraints
     *                    should be applied
     * @param manyToOne the {@code @ManyToOne} annotation instance if present, otherwise {@code null}
     * @param oneToOne the {@code @OneToOne} annotation instance if present, otherwise {@code null}
     */
    private void processToOneRelationship(AttributeDescriptor attr, EntityModel ownerEntity,
                                          ManyToOne manyToOne, OneToOne oneToOne) {
        if (oneToOne != null && !oneToOne.mappedBy().isEmpty()) {
            // inverse side: FK/관계 제약 생성 금지 (모델에 논리 관계만 기록할지 여부는 설계에 따라)
            return;
        }
        
        Optional<TypeElement> referencedTypeElementOpt = resolveTargetEntity(attr, manyToOne, oneToOne, null, null);
        if (referencedTypeElementOpt.isEmpty()) return;
        TypeElement referencedTypeElement = referencedTypeElementOpt.get();

        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key to be referenced.", attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));

        JoinColumns joinColumnsAnno = attr.getAnnotation(JoinColumns.class);
        JoinColumn joinColumnAnno = attr.getAnnotation(JoinColumn.class);

        List<JoinColumn> joinColumns = joinColumnsAnno != null ? Arrays.asList(joinColumnsAnno.value()) :
                (joinColumnAnno != null ? List.of(joinColumnAnno) : Collections.emptyList());

        // Validate composite key
        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on " + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (!joinColumns.isEmpty() && joinColumns.size() != refPkList.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + attr.name() +
                            ". Expected " + refPkList.size() + " (from referenced PK), but got " + joinColumns.size() + ".", attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNames = new ArrayList<>();
        MapsId mapsId = attr.getAnnotation(MapsId.class);
        String mapsIdAttr = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : null;

        // Validate that all JoinColumns use the same table for composite keys
        if (joinColumns.size() > 1) {
            String firstTable = resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            for (int i = 1; i < joinColumns.size(); i++) {
                String currentTable = resolveJoinColumnTable(joinColumns.get(i), ownerEntity);
                if (!firstTable.equals(currentTable)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn annotations in composite key must target the same table. Found: '" + firstTable + "' and '" + currentTable + "'.", attr.elementForDiagnostics());
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
                                + referencedEntity.getEntityName(), attr.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            String fieldName = attr.name();
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                    ? jc.name()
                    : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

            // Validate duplicate FK names
            if (fkColumnNames.contains(fkColumnName) || toAdd.containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkColumnName + "' in "
                                + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            boolean makePk;
            if (mapsId == null) {
                makePk = false;
            } else if (mapsIdAttr == null) {
                makePk = true;
            } else {
                makePk = referencedPkName.equalsIgnoreCase(mapsIdAttr)
                        || referencedPkName.endsWith("_" + mapsIdAttr);
            }

            boolean associationOptional =
                    (manyToOne != null) ? manyToOne.optional() : oneToOne.optional();
            boolean columnNullableFromAnno =
                    (jc != null) ? jc.nullable() : associationOptional;
            boolean isNullable = !makePk && (associationOptional && columnNullableFromAnno);

            if (!associationOptional && jc != null && jc.nullable()) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@JoinColumn(nullable=true) conflicts with optional=false; treating as NOT NULL.", attr.elementForDiagnostics());
            }

            String tableNameForFk = resolveJoinColumnTable(jc, ownerEntity);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .tableName(tableNameForFk)
                    .javaType(referencedPkColumn.getJavaType())
                    .isPrimaryKey(makePk)
                    .isNullable(isNullable)
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = findColumn(ownerEntity, tableNameForFk, fkColumnName);
            if (existing != null) {
                if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for column '" + fkColumnName + "' in table '" + tableNameForFk + "' on " + ownerEntity.getEntityName() +
                                    ". Foreign key requires type " + fkColumn.getJavaType() +
                                    " but existing column has type " + existing.getJavaType(), attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
                // If types match, apply relationship constraints (@MapsId/nullable) to existing column
                if (makePk && !existing.isPrimaryKey()) {
                    existing.setPrimaryKey(true);
                }
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
            putColumn(ownerEntity, col);
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
                            "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + n + "'.", attr.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        // FK 제약 이름 생성 시 실제 FK가 위치하는 테이블 기준
        String fkBaseTable = joinColumns.isEmpty()
            ? ownerEntity.getTableName()
            : resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            
        String relationConstraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!allSameConstraintMode(joinColumns)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All @JoinColumn.foreignKey.value must be identical for composite FK on " 
                    + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }
        
        // Check for @ForeignKey(NO_CONSTRAINT) 
        boolean noConstraint = !joinColumns.isEmpty() &&
                joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        // Create relationship model
        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .tableName(fkBaseTable) // FK가 걸리는 테이블 (보조 테이블 포함)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNames)
                .mapsId(mapsId != null)
                .noConstraint(noConstraint)
                .constraintName(relationConstraintName)
                .cascadeTypes(manyToOne != null ? toCascadeList(manyToOne.cascade()) : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();

        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);
        
        // @JoinColumn(unique=true) 처리 - 1:1 관계에서 DB 유니크 제약 추가
        boolean anyUnique = joinColumns.stream().anyMatch(JoinColumn::unique);
        if (anyUnique) {
            String uqName = context.getNaming().uqName(fkBaseTable, fkColumnNames);
            if (!ownerEntity.getConstraints().containsKey(uqName)) {
                ownerEntity.getConstraints().put(uqName, ConstraintModel.builder()
                    .name(uqName).type(ConstraintType.UNIQUE)
                    .tableName(fkBaseTable).columns(new ArrayList<>(fkColumnNames)).build());
            }
        }
        
        // FK 컬럼에 자동 인덱스 생성 (성능 향상을 위해)
        addForeignKeyIndex(ownerEntity, fkColumnNames, fkBaseTable);
    }

    /**
     * Process a unidirectional {@code @OneToMany} where the owning side maps the relationship using
     * foreign key columns on the target (many) table.
     *
     * <p>This method validates the attribute is a collection and resolves the target entity, ensures
     * both sides have appropriate primary keys, and processes any {@code @JoinColumn(s)} present:
     * - validates join column count and table consistency for composite keys,
     * - determines or generates FK column names and creates missing columns on the target entity,
     * - validates Java type compatibility with any existing columns,
     * - determines the FK constraint name (honoring explicit {@code @ForeignKey.name} or building one),
     * - honors {@code ConstraintMode.NO_CONSTRAINT} when specified,
     * - records a ONE_TO_MANY RelationshipModel on the target entity (FK lives on the many side),
     * - emits diagnostics and marks entities invalid on fatal errors,
     * - converts {@code @JoinColumn(unique = true)} into a UNIQUE constraint with a warning, and
     * - ensures an index exists for the FK columns.
     *
     * <p>All diagnostics are reported through the processing context. No DDL is emitted here; the
     * method mutates the in-memory EntityModel (columns, relationships, constraints, indexes) used by
     * later schema generation.
     *
     * @param attr the attribute descriptor for the owning field annotated with {@code @OneToMany}
     * @param ownerEntity the entity model of the owning (one) side
     * @param oneToMany the {@code @OneToMany} annotation instance on the attribute
     */
    private void processUnidirectionalOneToMany_FK(AttributeDescriptor attr, EntityModel ownerEntity, OneToMany oneToMany) {
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

        // attr.isCollection()으로 이미 검증했으므로 중복 Collection 검사 제거

        Optional<TypeElement> targetEntityElementOpt = resolveTargetEntity(attr, null, null, oneToMany, null);
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
            String firstTable = resolveJoinColumnTable(jlist.get(0), targetEntityModel);
            for (int i = 1; i < jlist.size(); i++) {
                String currentTable = resolveJoinColumnTable(jlist.get(i), targetEntityModel);
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

        // loop1: Pre-validation
        for (int i = 0; i < ownerPks.size(); i++) {
            ColumnModel ownerPk = ownerPks.get(i);
            JoinColumn j = jlist.isEmpty() ? null : jlist.get(i);

            String refName = (j != null && !j.referencedColumnName().isEmpty())
                    ? j.referencedColumnName() : ownerPk.getColumnName();

            String fkName = (j != null && !j.name().isEmpty())
                    ? j.name()
                    : context.getNaming().foreignKeyColumnName(ownerEntity.getTableName(), refName);

            // Validate duplicate FK names
            if (fkNames.contains(fkName) || toAdd.containsKey(fkName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkName + "' in "
                                + targetEntityModel.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
                targetEntityModel.setValid(false);
                return;
            }

            String tableNameForFk = resolveJoinColumnTable(j, targetEntityModel);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .tableName(tableNameForFk)
                    .javaType(ownerPk.getJavaType())
                    .isNullable(j == null || j.nullable())
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = findColumn(targetEntityModel, tableNameForFk, fkName);
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
            putColumn(targetEntityModel, col);
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
        
        // FK 제약 이름 생성 시 실제 FK가 위치하는 테이블 기준 
        String fkBaseTable = jlist.isEmpty()
            ? targetEntityModel.getTableName()
            : resolveJoinColumnTable(jlist.get(0), targetEntityModel);
            
        String constraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkNames,
                        ownerEntity.getTableName(), refNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!allSameConstraintMode(jlist)) {
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
        // NOTE: 관계 타입과 실제 FK 방향의 의미적 차이
        // - 도메인 관점: User(1) -> Orders(N) = ONE_TO_MANY (JPA 애노테이션 기준)
        // - 스키마 관점: Orders 테이블에 user_id FK = MANY_TO_ONE (실제 FK 방향)
        // 여기서는 도메인 의미를 보존하여 ONE_TO_MANY로 기록 (JPA 표준 준수)
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.ONE_TO_MANY)
                .tableName(fkBaseTable) // FK가 걸리는 테이블 (many 쪽 테이블)
                .columns(fkNames)                          // FK 컬럼들 (many 쪽 테이블에 위치)
                .referencedTable(ownerEntity.getTableName())   // 참조 테이블 (one 쪽)
                .referencedColumns(refNames)               // 참조 컬럼들 (one 쪽 PK)
                .noConstraint(noConstraint)
                .constraintName(constraintName)
                .cascadeTypes(toCascadeList(oneToMany.cascade()))
                .orphanRemoval(oneToMany.orphanRemoval())
                .fetchType(oneToMany.fetch())
                .build();

        targetEntityModel.getRelationships().put(rel.getConstraintName(), rel);
        
        // @JoinColumn(unique=true) 처리 - OneToMany에서 unique FK 제약 (실질적으로 OneToOne)
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
        
        // FK 컬럼에 자동 인덱스 생성 (성능 향상을 위해)
        addForeignKeyIndex(targetEntityModel, fkNames, fkBaseTable);
    }

    /**
         * Resolve and materialize an owning, unidirectional @OneToMany relationship that uses a join table.
         *
         * <p>When the attribute is a collection and the relation is the owning side, this method:
         * - resolves the target entity and ensures both owner and target have primary keys;
         * - validates and normalizes {@code @JoinTable}, {@code @JoinColumn} and {@code inverseJoinColumns} settings
         *   (counts, composite-key consistency, constraint-name / ConstraintMode consistency, and name collisions);
         * - maps join-table foreign key columns to owner/target primary key columns;
         * - reuses an existing join-table entity if one exists (verifying it is a join table) or creates a new one;
         * - ensures join-table columns, foreign-key relationships, indexes and any required UNIQUE constraint to enforce
         *   1:N semantics on the target-side FK; and
         * - applies {@code @JoinTable.uniqueConstraints} if present.
         *
         * <p>Diagnostics (errors and warnings) are emitted and processing is aborted on fatal validation failures.
         *
         * @param attr descriptor for the collection attribute annotated with {@code @OneToMany} (owning, unidirectional)
         * @param ownerEntity the entity model that owns the collection (the source/owner side)
         * @param oneToMany the {@code @OneToMany} annotation instance from the attribute
         */
    private void processUnidirectionalOneToMany_JoinTable(AttributeDescriptor attr, EntityModel ownerEntity, OneToMany oneToMany) {
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

        // attr.isCollection()으로 이미 검증했으므로 중복 Collection 검사 제거

        Optional<TypeElement> targetEntityElementOpt = resolveTargetEntity(attr, null, null, oneToMany, null);
        if (targetEntityElementOpt.isEmpty()) return;
        TypeElement targetEntityElement = targetEntityElementOpt.get();
        EntityModel targetEntity = context.getSchemaModel().getEntities()
                .get(targetEntityElement.getQualifiedName().toString());
        if (targetEntity == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        List<ColumnModel> targetPks = context.findAllPrimaryKeyColumns(targetEntity);

        if (ownerPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner entity requires a primary key for @OneToMany with JoinTable.", attr.elementForDiagnostics());
            return;
        }
        if (targetPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Target entity requires a primary key for @OneToMany with JoinTable.", attr.elementForDiagnostics());
            return;
        }

        JoinTable jt = attr.getAnnotation(JoinTable.class);
        String joinTableName = (jt != null && !jt.name().isEmpty())
                ? jt.name()
                : context.getNaming().joinTableName(ownerEntity.getTableName(), targetEntity.getTableName());

        JoinColumn[] joinColumns = (jt != null) ? jt.joinColumns() : new JoinColumn[0];
        JoinColumn[] inverseJoinColumns = (jt != null) ? jt.inverseJoinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.joinColumns count must match owner primary key count: expected " + ownerPks.size()
                            + ", found " + joinColumns.length + ".", attr.elementForDiagnostics());
            return;
        }
        if (inverseJoinColumns.length > 0 && inverseJoinColumns.length != targetPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.inverseJoinColumns count must match target primary key count: expected " + targetPks.size()
                            + ", found " + inverseJoinColumns.length + ".", attr.elementForDiagnostics());
            return;
        }

        try {
            validateExplicitJoinColumns(joinColumns, ownerPks, attr, "owner");
            validateExplicitJoinColumns(inverseJoinColumns, targetPks, attr, "target");
        } catch (IllegalStateException ex) {
            return;
        }

        // Validate FK constraint names and ConstraintMode consistency for join table
        String ownerFkConstraint = null;
        for (JoinColumn jc : joinColumns) {
            String n = jc.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (ownerFkConstraint == null) {
                    ownerFkConstraint = n;
                } else if (!ownerFkConstraint.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All owner-side @JoinColumn.foreignKey names must be identical in join table. Found: '" + ownerFkConstraint + "' and '" + n + "'.", attr.elementForDiagnostics());
                    return;
                }
            }
        }
        
        if (!allSameConstraintMode(Arrays.asList(joinColumns))) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All owner-side @JoinColumn.foreignKey.value must be identical for composite FK in join table on " 
                    + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            return;
        }
        boolean ownerNoConstraint = joinColumns.length > 0 &&
                joinColumns[0].foreignKey().value() == ConstraintMode.NO_CONSTRAINT;
        
        String targetFkConstraint = null;
        for (JoinColumn jc : inverseJoinColumns) {
            String n = jc.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (targetFkConstraint == null) {
                    targetFkConstraint = n;
                } else if (!targetFkConstraint.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All inverse-side @JoinColumn.foreignKey names must be identical in join table. Found: '" + targetFkConstraint + "' and '" + n + "'.", attr.elementForDiagnostics());
                    return;
                }
            }
        }
        
        if (!allSameConstraintMode(Arrays.asList(inverseJoinColumns))) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All inverse-side @JoinColumn.foreignKey.value must be identical for composite FK in join table on " 
                    + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            return;
        }
        boolean inverseNoConstraint = inverseJoinColumns.length > 0 &&
                inverseJoinColumns[0].foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        Map<String,String> ownerFkToPkMap;
        Map<String,String> targetFkToPkMap;
        try {
            ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), attr, true);
            targetFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, targetPks, targetEntity.getTableName(), attr, false);
        } catch (IllegalStateException ex) {
            return;
        }

        Set<String> ownerFks = new HashSet<>(ownerFkToPkMap.keySet());
        ownerFks.retainAll(targetFkToPkMap.keySet());
        if (!ownerFks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable foreign key name collision across sides: " + ownerFks + " (owner vs target).", attr.elementForDiagnostics());
            return;
        }

        // Final validation of mapping count
        if (ownerFkToPkMap.size() != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner join-columns could not be resolved to all primary key columns. expected=" + ownerPks.size()
                            + ", found=" + ownerFkToPkMap.size(), attr.elementForDiagnostics());
            return;
        }
        if (targetFkToPkMap.size() != targetPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Target join-columns could not be resolved to all primary key columns. expected=" + targetPks.size()
                            + ", found=" + targetFkToPkMap.size(), attr.elementForDiagnostics());
            return;
        }

        JoinTableDetails details = new JoinTableDetails(
                joinTableName, ownerFkToPkMap, targetFkToPkMap, ownerEntity, targetEntity,
                ownerFkConstraint, targetFkConstraint, ownerNoConstraint, inverseNoConstraint
        );

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            // 조인테이블 이름 충돌 검증: 기존 엔티티가 진짜 조인테이블인지 확인
            if (existing.getTableType() != EntityModel.TableType.JOIN_TABLE) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable name '" + joinTableName + "' conflicts with a non-join entity/table.", attr.elementForDiagnostics());
                return;
            }
            
            ensureJoinTableColumns(existing, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, attr);
            ensureJoinTableRelationships(existing, details);
            addOneToManyJoinTableUnique(existing, targetFkToPkMap);
            
            // Process @JoinTable.uniqueConstraints for existing table
            if (jt != null && jt.uniqueConstraints().length > 0) {
                addJoinTableUniqueConstraints(existing, jt.uniqueConstraints(), attr);
            }
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, targetPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        // 1:N semantics: target FK set must be unique
        ensureJoinTableColumns(joinTableEntity, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, attr);
        ensureJoinTableRelationships(joinTableEntity, details);
        addOneToManyJoinTableUnique(joinTableEntity, targetFkToPkMap);
        
        // Process @JoinTable.uniqueConstraints
        if (jt != null && jt.uniqueConstraints().length > 0) {
            addJoinTableUniqueConstraints(joinTableEntity, jt.uniqueConstraints(), attr);
        }

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    /**
     * Validates explicit referencedColumnName presence on provided {@code @JoinColumn}s when the
     * referenced entity has a composite primary key.
     *
     * <p>If the {@code pks} list contains more than one primary key column (composite PK) and any of
     * the supplied {@code joinColumns} is missing a non-empty {@code referencedColumnName}, an error
     * diagnostic is emitted (using {@code attr} for location) and an {@link IllegalStateException}
     * is thrown.
     *
     * @param joinColumns the {@code @JoinColumn} annotations to validate
     * @param pks the list of primary key columns on the referenced entity; composite PK is detected
     *            when {@code pks.size() > 1}
     * @param attr attribute descriptor whose element is used for diagnostic reporting
     * @param sideLabel label used in diagnostic messages to indicate the relationship side (e.g.
     *                  "joinColumns" or "inverseJoinColumns")
     */
    private void validateExplicitJoinColumns(JoinColumn[] joinColumns, List<ColumnModel> pks,
                                             AttributeDescriptor attr, String sideLabel) {
        if (pks.size() > 1 && joinColumns.length > 0) {
            for (int i = 0; i < joinColumns.length; i++) {
                JoinColumn jc = joinColumns[i];
                if (jc.referencedColumnName() == null || jc.referencedColumnName().isEmpty()) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Composite primary key requires explicit referencedColumnName for all @" + sideLabel
                                    + " JoinColumns (index " + i + ", pkCount = " + pks.size() + ", joinColumnsCount = " + joinColumns.length  + ").", attr.elementForDiagnostics());
                    throw new IllegalStateException("invalid joinColumns");
                }
            }
        }
    }

    /**
     * Build a mapping from join-table foreign key column names to the referenced primary-key column names.
     *
     * If no {@code joinColumns} are provided, a naming strategy is used to generate FK column names for each
     * referenced PK in order. If {@code joinColumns} are present, each entry is validated and used:
     * - a missing {@code referencedColumnName} defaults to the PK at the same index in {@code referencedPks};
     * - the referenced column name must match one of the referenced entity's primary-key columns;
     * - a missing {@code name} is populated via the naming strategy.
     *
     * Validation errors are reported to the processing messager and result in an {@link IllegalStateException}:
     * - duplicate FK column names within this mapping;
     * - a {@code referencedColumnName} that is not a primary-key column of {@code entityTableName}.
     *
     * @param joinColumns     explicit join-column annotations (may be null or empty)
     * @param referencedPks   list of primary-key columns on the referenced entity (order matters when joinColumns is absent)
     * @param entityTableName base table name of the referenced entity (used for diagnostics and naming)
     * @param attr            attribute descriptor used for diagnostics context
     * @param isOwnerSide     true when resolving the owner side (affects diagnostic messages)
     * @return a linked map whose keys are join-table FK column names and values are the referenced PK column names
     * @throws IllegalStateException if validation fails (duplicate FK name or invalid referencedColumnName)
     */
    private Map<String, String> resolveJoinColumnMapping(JoinColumn[] joinColumns,
                                                         List<ColumnModel> referencedPks,
                                                         String entityTableName,
                                                         AttributeDescriptor attr,
                                                         boolean isOwnerSide) {
        String side = isOwnerSide ? "owner" : "target";
        Map<String, String> mapping = new LinkedHashMap<>();
        if (joinColumns == null || joinColumns.length == 0) {
            referencedPks.forEach(pk -> {
                String fk = context.getNaming().foreignKeyColumnName(entityTableName, pk.getColumnName());
                if (mapping.containsKey(fk)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fk + "' in join table mapping for " + entityTableName + " (side=" + side + ")", attr.elementForDiagnostics());
                    throw new IllegalStateException("duplicate fk");
                }
                mapping.put(fk, pk.getColumnName());
            });
        } else {
            Set<String> pkNames = referencedPks.stream().map(ColumnModel::getColumnName).collect(Collectors.toSet());
            for (int i = 0; i < joinColumns.length; i++) {
                JoinColumn jc = joinColumns[i];
                String pkName = jc.referencedColumnName().isEmpty()
                        ? referencedPks.get(i).getColumnName()
                        : jc.referencedColumnName();
                if (!pkNames.contains(pkName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "referencedColumnName '" + pkName + "' is not a primary key column of " + entityTableName + " (side=" + side + ")", attr.elementForDiagnostics());
                    throw new IllegalStateException("invalid referencedColumnName");
                }
                String fkName = jc.name().isEmpty()
                        ? context.getNaming().foreignKeyColumnName(entityTableName, pkName)
                        : jc.name();
                if (mapping.containsKey(fkName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fkName + "' in join table mapping for " + entityTableName, attr.elementForDiagnostics());
                    throw new IllegalStateException("duplicate fk");
                }
                mapping.put(fkName, pkName);
            }
        }
        return mapping;
    }

    /**
     * Adds a UNIQUE constraint to the join table covering the target-side foreign key columns.
     *
     * If the provided target FK→PK mapping is non-empty this creates a ConstraintModel with a
     * generated unique name (via the naming strategy) for the join table and registers it on the
     * joinTableEntity. Column order from the mapping's key set is preserved.
     *
     * @param joinTableEntity the join-table EntityModel to receive the UNIQUE constraint
     * @param targetFkToPkMap mapping of join-table FK column names (keys) to referenced PK column names;
     *                        the keys determine the UNIQUE constraint columns and their order
     */
    private void addOneToManyJoinTableUnique(EntityModel joinTableEntity,
                                             Map<String,String> targetFkToPkMap) {
        List<String> cols = new ArrayList<>(targetFkToPkMap.keySet());
        // 컬럼 순서 유지 (성능 최적화를 위해)
        if (!cols.isEmpty()) {
            String ucName = context.getNaming().uqName(joinTableEntity.getTableName(), cols);
            ConstraintModel constraintModel = ConstraintModel.builder()
                    .name(ucName)
                    .type(ConstraintType.UNIQUE)
                    .tableName(joinTableEntity.getTableName())
                    .columns(cols)
                    .build();
            if (!joinTableEntity.getConstraints().containsKey(ucName)) {
                joinTableEntity.getConstraints().put(ucName, constraintModel);
            }
        }
    }
    
    /**
         * Process and attach any {@code @JoinTable.uniqueConstraints} to the join table entity.
         *
         * <p>For each {@link UniqueConstraint} this method:
         * <ul>
         *   <li>skips constraints with empty {@code columnNames} (warns);</li>
         *   <li>validates that every named column exists on the join table and emits an error and stops
         *       processing the constraints if any column is missing;</li>
         *   <li>uses the explicit constraint {@code name} when provided, otherwise generates a name
         *       via the project's naming strategy;</li>
         *   <li>creates a {@link ConstraintModel} of type UNIQUE and registers it on the join table;</li>
         *   <li>warns and skips when a constraint with the same name already exists; otherwise emits a
         *       note after successfully adding the constraint.</li>
         * </ul>
         *
         * @param joinTableEntity the join-table entity to which unique constraints will be added
         * @param uniqueConstraints the {@code uniqueConstraints} declared on the {@code @JoinTable}
         * @param attr attribute descriptor used as the source for diagnostics (messages)
         */
    private void addJoinTableUniqueConstraints(EntityModel joinTableEntity, 
                                               UniqueConstraint[] uniqueConstraints, 
                                               AttributeDescriptor attr) {
        for (UniqueConstraint uc : uniqueConstraints) {
            String[] columnNames = uc.columnNames();
            if (columnNames.length == 0) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "UniqueConstraint with empty columnNames in @JoinTable on " + 
                        joinTableEntity.getEntityName() + ". Skipping.", attr.elementForDiagnostics());
                continue;
            }
            
            // Validate that all specified columns exist in the join table
            List<String> validColumns = new ArrayList<>();
            for (String colName : columnNames) {
                if (joinTableEntity.hasColumn(joinTableEntity.getTableName(), colName)) {
                    validColumns.add(colName);
                } else {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "UniqueConstraint references non-existent column '" + colName + 
                            "' in join table " + joinTableEntity.getTableName(), attr.elementForDiagnostics());
                    return;
                }
            }
            
            String constraintName;
            if (uc.name() != null && !uc.name().isEmpty()) {
                constraintName = uc.name();
            } else {
                // Generate constraint name using naming strategy
                constraintName = context.getNaming().uqName(joinTableEntity.getTableName(), validColumns);
            }
            
            // Create and add unique constraint
            ConstraintModel uniqueConstraintModel = ConstraintModel.builder()
                    .name(constraintName)
                    .type(ConstraintType.UNIQUE)
                    .tableName(joinTableEntity.getTableName())
                    .columns(validColumns)
                    .build();
            
            if (joinTableEntity.getConstraints().containsKey(constraintName)) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Duplicate unique constraint name '" + constraintName + "' in join table " +
                        joinTableEntity.getTableName() + ". Skipping duplicate.", attr.elementForDiagnostics());
            } else {
                joinTableEntity.getConstraints().put(constraintName, uniqueConstraintModel);
                context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Added unique constraint '" + constraintName + "' on columns [" + 
                        String.join(", ", validColumns) + "] to join table " + joinTableEntity.getTableName());
            }
        }
    }

    /**
         * Processes an owning-side `@ManyToMany` relationship for the given attribute.
         *
         * <p>Validates the mapping, resolves the referenced entity and primary keys, and ensures or
         * creates the join table and its schema artifacts (FK columns, FK relationships, composite
         * primary key, unique constraints and indexes) according to any `@JoinTable` / `@JoinColumn`
         * configuration. Emits diagnostics for invalid configurations (type/generic errors, PK
         * absence, mismatched join column counts, duplicate FK names, cross-side column collisions,
         * inconsistent ConstraintMode, etc.). If a join table entity with the same name already
         * exists, this method validates compatibility and augments it.</p>
         *
         * <p>Side effects:
         * - May create or update an EntityModel representing the join table and register it in the
         *   processing schema model.
         * - May add columns, relationships, constraints, and indexes to owner/target/join table
         *   entities.
         * - Emits diagnostics via the processing context for any errors, warnings, or notes.</p>
         *
         * @param attr descriptor for the owning attribute annotated with `@ManyToMany`
         * @param ownerEntity the entity model that owns this relationship
         * @param manyToMany the `@ManyToMany` annotation instance present on the attribute
         */
    private void processOwningManyToMany(AttributeDescriptor attr, EntityModel ownerEntity, ManyToMany manyToMany) {
        JoinTable joinTable = attr.getAnnotation(JoinTable.class);

        if (!attr.isCollection()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany field must be a collection type. field=" + attr.name(), attr.elementForDiagnostics());
            return;
        }

        Optional<DeclaredType> typeArg = attr.genericArg(0);
        if (typeArg.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve generic type parameter for @ManyToMany field. field=" + attr.name(), attr.elementForDiagnostics());
            return;
        }

        // attr.isCollection()으로 이미 검증했으므로 중복 Collection 검사 제거

        Optional<TypeElement> referencedTypeElementOpt = resolveTargetEntity(attr, null, null, null, manyToMany);
        if (referencedTypeElementOpt.isEmpty()) return;
        TypeElement referencedTypeElement = referencedTypeElementOpt.get();
        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        List<ColumnModel> referencedPks = context.findAllPrimaryKeyColumns(referencedEntity);

        if (ownerPks.isEmpty() || referencedPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entities in a @ManyToMany relationship must have a primary key.", attr.elementForDiagnostics());
            return;
        }

        String joinTableName = (joinTable != null && !joinTable.name().isEmpty())
                ? joinTable.name()
                : context.getNaming().joinTableName(ownerEntity.getTableName(), referencedEntity.getTableName());

        JoinColumn[] joinColumns = (joinTable != null) ? joinTable.joinColumns() : new JoinColumn[0];
        JoinColumn[] inverseJoinColumns = (joinTable != null) ? joinTable.inverseJoinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The number of @JoinColumn annotations for " + ownerEntity.getTableName() +
                            " must match its primary key columns: expected " + ownerPks.size() +
                            ", found " + joinColumns.length + ".", attr.elementForDiagnostics());
            return;
        }
        if (inverseJoinColumns.length > 0 && inverseJoinColumns.length != referencedPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The number of @InverseJoinColumn annotations for " + referencedEntity.getTableName() +
                            " must match its primary key columns: expected " + referencedPks.size() +
                            ", found " + inverseJoinColumns.length + ".", attr.elementForDiagnostics());
            return;
        }

        // Validate FK constraint names and ConstraintMode consistency for join table
        String ownerFkConstraint = null;
        if (!allSameConstraintMode(Arrays.asList(joinColumns))) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All owner-side @JoinColumn.foreignKey.value must be identical for composite FK in join table on " 
                    + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            return;
        }
        boolean ownerNoConstraint = joinColumns.length > 0 && joinColumns[0].foreignKey().value() == ConstraintMode.NO_CONSTRAINT;
        for (JoinColumn jc : joinColumns) {
            String n = jc.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (ownerFkConstraint == null) {
                    ownerFkConstraint = n;
                } else if (!ownerFkConstraint.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All owner-side @JoinColumn.foreignKey names must be identical in join table. Found: '" + ownerFkConstraint + "' and '" + n + "'.", attr.elementForDiagnostics());
                    return;
                }
            }
        }

        String inverseFkConstraint = null;
        if (!allSameConstraintMode(Arrays.asList(inverseJoinColumns))) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All inverse-side @JoinColumn.foreignKey.value must be identical for composite FK in join table on " 
                    + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            return;
        }
        boolean inverseNoConstraint = inverseJoinColumns.length > 0 && inverseJoinColumns[0].foreignKey().value() == ConstraintMode.NO_CONSTRAINT;
        for (JoinColumn jc : inverseJoinColumns) {
            String n = jc.foreignKey().name();
            if (n != null && !n.isEmpty()) {
                if (inverseFkConstraint == null) {
                    inverseFkConstraint = n;
                } else if (!inverseFkConstraint.equals(n)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All inverse-side @JoinColumn.foreignKey names must be identical in join table. Found: '" + inverseFkConstraint + "' and '" + n + "'.", attr.elementForDiagnostics());
                    return;
                }
            }
        }

        try {
            validateExplicitJoinColumns(joinColumns, ownerPks, attr, "owner");
            validateExplicitJoinColumns(inverseJoinColumns, referencedPks, attr, "target");
        } catch (IllegalStateException ex) {
            return;
        }

        Map<String, String> ownerFkToPkMap;
        Map<String, String> inverseFkToPkMap;

        try {
            ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), attr, true);
            inverseFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, referencedPks, referencedEntity.getTableName(), attr, false);
        } catch (IllegalStateException ex) {
            return;
        }

        Set<String> overlap = new HashSet<>(ownerFkToPkMap.keySet());
        overlap.retainAll(inverseFkToPkMap.keySet());
        if (!overlap.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable foreign key name collision across sides: " + overlap + " (owner vs target).", attr.elementForDiagnostics());
            return;
        }

        JoinTableDetails details = new JoinTableDetails(joinTableName, ownerFkToPkMap, inverseFkToPkMap,
                ownerEntity, referencedEntity, ownerFkConstraint, inverseFkConstraint, ownerNoConstraint, inverseNoConstraint);

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            // 조인테이블 이름 충돌 검증: 기존 엔티티가 진짜 조인테이블인지 확인
            if (existing.getTableType() != EntityModel.TableType.JOIN_TABLE) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable name '" + joinTableName + "' conflicts with a non-join entity/table.", attr.elementForDiagnostics());
                return;
            }
            
            ensureJoinTableColumns(existing, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, attr);
            ensureJoinTableRelationships(existing, details);
            addManyToManyPkConstraint(existing, ownerFkToPkMap, inverseFkToPkMap);
            
            // Process @JoinTable.uniqueConstraints for existing table
            if (joinTable != null && joinTable.uniqueConstraints().length > 0) {
                addJoinTableUniqueConstraints(existing, joinTable.uniqueConstraints(), attr);
            }
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, referencedPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        // N:N semantics: composite PK(owner_fk + target_fk)
        addManyToManyPkConstraint(joinTableEntity, ownerFkToPkMap, inverseFkToPkMap);
        ensureJoinTableColumns(joinTableEntity, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, attr);
        ensureJoinTableRelationships(joinTableEntity, details);
        
        // Process @JoinTable.uniqueConstraints
        if (joinTable != null && joinTable.uniqueConstraints().length > 0) {
            addJoinTableUniqueConstraints(joinTableEntity, joinTable.uniqueConstraints(), attr);
        }

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    /**
     * Ensures the join table has a composite primary key composed of the join-table
     * foreign key columns (owner-side columns followed by inverse/target-side columns).
     *
     * If no FK columns are provided this is a no-op. If a primary key with the
     * generated name already exists on the join table, the method does nothing.
     * Otherwise it creates and registers a PRIMARY_KEY ConstraintModel on the
     * joinTableEntity using the naming strategy from the processing context.
     *
     * @param joinTableEntity the join table entity to modify
     * @param ownerFkToPkMap  mapping of owner-side join-table FK column name -> referenced PK column name;
     *                        the map's keys determine the owner-side PK column ordering
     * @param inverseFkToPkMap mapping of inverse/target-side join-table FK column name -> referenced PK column name;
     *                         the map's keys determine the inverse-side PK column ordering
     */
    private void addManyToManyPkConstraint(EntityModel joinTableEntity,
                                           Map<String,String> ownerFkToPkMap,
                                           Map<String,String> inverseFkToPkMap) {
        List<String> cols = new ArrayList<>();
        cols.addAll(ownerFkToPkMap.keySet());
        cols.addAll(inverseFkToPkMap.keySet());
        if (cols.isEmpty()) return;

        // 컬럼 순서 유지: owner_fk → target_fk 순으로 (성능 최적화를 위해)
        String pkName = context.getNaming().pkName(joinTableEntity.getTableName(), cols);
        if (!joinTableEntity.getConstraints().containsKey(pkName)) {
            ConstraintModel pkConstraint = ConstraintModel.builder()
                    .name(pkName)
                    .type(ConstraintType.PRIMARY_KEY)
                    .tableName(joinTableEntity.getTableName())
                    .columns(cols)
                    .build();
            joinTableEntity.getConstraints().put(pkName, pkConstraint);
        }
    }

    /**
     * Create an EntityModel representing the join table and populate it with FK columns
     * mapped to the provided owner and referenced primary key columns.
     *
     * The returned EntityModel has TableType.JOIN_TABLE and contains non-nullable FK
     * columns named per details.ownerFkToPkMap() and details.inverseFkToPkMap(), with
     * javaType copied from the corresponding referenced primary key ColumnModel.
     *
     * @param details        join table metadata (names and FK→PK mappings)
     * @param ownerPks       primary key columns of the owner entity (used to resolve owner-side FK types)
     * @param referencedPks  primary key columns of the referenced/target entity (used to resolve inverse-side FK types)
     * @return the created join table EntityModel populated with FK columns
     * @throws IllegalStateException if any mapped PK name cannot be found in the supplied PK lists
     */
    private EntityModel createJoinTableEntity(JoinTableDetails details, List<ColumnModel> ownerPks, List<ColumnModel> referencedPks) {
        EntityModel joinTableEntity = EntityModel.builder()
                .entityName(details.joinTableName())
                .tableName(details.joinTableName())
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();

        // Process owner-side FK columns with error handling
        for (Map.Entry<String, String> entry : details.ownerFkToPkMap().entrySet()) {
            String fkName = entry.getKey();
            String pkName = entry.getValue();
            ColumnModel pk = ownerPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElse(null);
            if (pk == null) {
                String availablePks = ownerPks.stream()
                        .map(ColumnModel::getColumnName)
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Owner primary key '" + pkName + 
                        "' not found while creating join table '" + details.joinTableName() + 
                        "'. Available PKs: [" + availablePks + "]");
            }
            joinTableEntity.putColumn(ColumnModel.builder()
                    .columnName(fkName).tableName(details.joinTableName()).javaType(pk.getJavaType()).isNullable(false).build());
        }

        // Process referenced-side FK columns with error handling
        for (Map.Entry<String, String> entry : details.inverseFkToPkMap().entrySet()) {
            String fkName = entry.getKey();
            String pkName = entry.getValue();
            ColumnModel pk = referencedPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElse(null);
            if (pk == null) {
                String availablePks = referencedPks.stream()
                        .map(ColumnModel::getColumnName)
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Referenced primary key '" + pkName + 
                        "' not found while creating join table '" + details.joinTableName() + 
                        "'. Available PKs: [" + availablePks + "]");
            }
            joinTableEntity.putColumn(ColumnModel.builder()
                    .columnName(fkName).tableName(details.joinTableName()).javaType(pk.getJavaType()).isNullable(false).build());
        }

        return joinTableEntity;
    }

    /**
     * Creates and registers the two MANY_TO_ONE relationships that link a join table to its owner
     * and referenced entities, and ensures indexes exist for the join-table foreign key columns.
     *
     * <p>The method:
     * - Builds an owner-side MANY_TO_ONE RelationshipModel using details.ownerFkToPkMap()
     *   and registers it on the join table entity.
     * - Builds a target-side MANY_TO_ONE RelationshipModel using details.inverseFkToPkMap()
     *   and registers it on the join table entity.
     * - Uses the provided constraint names if present; otherwise generates names via the
     *   naming strategy from the processing context.
     * - Honors the no-constraint flags in JoinTableDetails to mark relationships that should
     *   not produce DB-level foreign key constraints.
     * - Adds non-unique indexes for each foreign-key column group on the join table when
     *   appropriate.
     *
     * @param joinTableEntity the EntityModel representing the join table to receive relationships
     * @param details         encapsulates join-table metadata (FK<->PK mappings, constraint names,
     *                        and no-constraint flags) used to build the relationships
     */
    private void addRelationshipsToJoinTable(EntityModel joinTableEntity, JoinTableDetails details) {
        List<String> ownerFkColumns = new ArrayList<>(details.ownerFkToPkMap().keySet());
        List<String> ownerPkColumns = new ArrayList<>(details.ownerFkToPkMap().values());

        RelationshipModel ownerRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName(details.joinTableName()) // FK가 걸리는 테이블 (조인테이블)
                .columns(ownerFkColumns)
                .referencedTable(details.ownerEntity().getTableName())
                .referencedColumns(ownerPkColumns)
                .noConstraint(details.ownerNoConstraint())
                .constraintName(details.ownerFkConstraintName() != null
                        ? details.ownerFkConstraintName()
                        : context.getNaming().fkName(details.joinTableName(), ownerFkColumns,
                        details.ownerEntity().getTableName(), ownerPkColumns))
                .build();
        joinTableEntity.getRelationships().put(ownerRel.getConstraintName(), ownerRel);
        
        // Owner FK 컬럼에 인덱스 생성
        addForeignKeyIndex(joinTableEntity, ownerFkColumns, details.joinTableName());

        List<String> targetFkColumns = new ArrayList<>(details.inverseFkToPkMap().keySet());
        List<String> targetPkColumns = new ArrayList<>(details.inverseFkToPkMap().values());

        RelationshipModel targetRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName(details.joinTableName()) // FK가 걸리는 테이블 (조인테이블)
                .columns(targetFkColumns)
                .referencedTable(details.referencedEntity().getTableName())
                .referencedColumns(targetPkColumns)
                .noConstraint(details.inverseNoConstraint())
                .constraintName(details.inverseFkConstraintName() != null
                        ? details.inverseFkConstraintName()
                        : context.getNaming().fkName(details.joinTableName(), targetFkColumns,
                        details.referencedEntity().getTableName(), targetPkColumns))
                .build();
        joinTableEntity.getRelationships().put(targetRel.getConstraintName(), targetRel);
        
        // Target FK 컬럼에 인덱스 생성
        addForeignKeyIndex(joinTableEntity, targetFkColumns, details.joinTableName());
    }

    /**
     * Resolve the referenced entity type for a relationship attribute.
     *
     * <p>Returns the target entity TypeElement determined in this order:
     * <ol>
     *   <li>the `targetEntity` element of the first non-default relationship annotation provided
     *       (ManyToOne, OneToOne, OneToMany, ManyToMany), if explicitly set;</li>
     *   <li>for collection-valued relationships (OneToMany or ManyToMany), the first generic
     *       type argument of the attribute;</li>
     *   <li>otherwise the declared attribute type itself.</li>
     * </ol>
     *
     * <p>If an explicit `targetEntity` class is provided, this method uses the processing
     * environment to resolve it to a TypeElement. Returns an empty Optional when the
     * resulting TypeElement cannot be resolved.
     *
     * @param attr the attribute descriptor describing the field or property
     * @param m2o the ManyToOne annotation instance if present, otherwise null
     * @param o2o the OneToOne annotation instance if present, otherwise null
     * @param o2m the OneToMany annotation instance if present, otherwise null
     * @param m2m the ManyToMany annotation instance if present, otherwise null
     * @return an Optional containing the resolved TypeElement for the target entity, or empty if not resolvable
     */
    private Optional<TypeElement> resolveTargetEntity(AttributeDescriptor attr,
            ManyToOne m2o, OneToOne o2o, OneToMany o2m, ManyToMany m2m) {
        Class<?> te = null;
        if (m2o != null && m2o.targetEntity() != void.class) te = m2o.targetEntity();
        else if (o2o != null && o2o.targetEntity() != void.class) te = o2o.targetEntity();
        else if (o2m != null && o2m.targetEntity() != void.class) te = o2m.targetEntity();
        else if (m2m != null && m2m.targetEntity() != void.class) te = m2m.targetEntity();

        if (te != null) {
            return Optional.ofNullable(context.getElementUtils().getTypeElement(te.getName()));
        }
        // 컬렉션이면 genericArg(0), 아니면 attr.type()
        if ((o2m != null) || (m2m != null)) {
            return attr.genericArg(0).map(dt -> (TypeElement) dt.asElement());
        }
        TypeElement ref = getReferencedTypeElement(attr.type());
        return Optional.ofNullable(ref);
    }

    /**
     * Determine which table a {@code @JoinColumn} refers to, defaulting to the owner's primary table.
     *
     * If {@code jc} is null or its {@code table} attribute is empty, returns the owner's primary table.
     * If {@code jc.table} matches the owner's primary table or one of its secondary tables, returns that value.
     * Otherwise emits a warning and returns the owner's primary table.
     *
     * @param jc the JoinColumn annotation instance (may be null)
     * @param owner the owning entity model whose primary/secondary tables are considered
     * @return the table name to use for the join column (never null)
     */
    private String resolveJoinColumnTable(JoinColumn jc, EntityModel owner) {
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
    
    /**
     * Find a column on the given entity constrained to a specific table.
     *
     * This looks up a column by table name and column name to avoid collisions
     * between primary and secondary tables that may contain identical column names.
     *
     * @param entity the entity model to search
     * @param tableName the physical table name to restrict the search to
     * @param columnName the column name to find
     * @return the matching ColumnModel, or {@code null} if no matching column exists
     */
    private ColumnModel findColumn(EntityModel entity, String tableName, String columnName) {
        return entity.findColumn(tableName, columnName);
    }
    
    /**
     * Add a column to the given entity, using a table+column key to avoid name collisions.
     *
     * @param entity the entity model to receive the column
     * @param column the column model to add to the entity
     */
    private void putColumn(EntityModel entity, ColumnModel column) {
        entity.putColumn(column);
    }
    
    /**
     * Returns true if the given set of columns on the specified table is exactly covered
     * by an existing PRIMARY KEY or UNIQUE constraint on the entity.
     *
     * The comparison is order-insensitive and requires an exact match of column names;
     * partial or superset matches do not count.
     *
     * @param e the entity model to inspect
     * @param table the table name within the entity to check
     * @param cols the list of column names to test for coverage
     * @return true if a PRIMARY KEY or UNIQUE constraint exists on `table` whose columns
     *         exactly match `cols`, false otherwise
     */
    boolean coveredByPkOrUnique(EntityModel e, String table, List<String> cols) {
        Set<String> want = new LinkedHashSet<>(cols); // 순서 무시 비교
        for (ConstraintModel c : e.getConstraints().values()) {
            if (!Objects.equals(table, c.getTableName())) continue;
            if (c.getType() == ConstraintType.PRIMARY_KEY || c.getType() == ConstraintType.UNIQUE) {
                if (new LinkedHashSet<>(c.getColumns()).equals(want)) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the entity already has a non-unique index on the specified table
     * with exactly the given column sequence.
     *
     * The column order must match exactly.
     *
     * @param e the entity model to inspect
     * @param table the table name to check for the index
     * @param cols the ordered list of column names for the index
     * @return true if an index with the same table and column sequence exists, false otherwise
     */
    boolean hasSameIndex(EntityModel e, String table, List<String> cols) {
        for (IndexModel im : e.getIndexes().values()) {
            if (Objects.equals(table, im.getTableName()) && im.getColumnNames().equals(cols)) return true;
        }
        return false;
    }

    /**
     * Ensure a non-unique index exists for the given foreign-key columns on the specified table.
     *
     * If the provided fkColumns list is null/empty, already covered by a PRIMARY KEY or UNIQUE
     * constraint, or an equivalent index already exists, this method returns without modifying the entity.
     * Otherwise it creates an IndexModel (non-unique) named using the naming strategy and registers it
     * on the entity.
     *
     * Notes:
     * - The column order of the index is preserved from the provided fkColumns list.
     * - Index creation is skipped when an index with the same generated name or identical column sequence already exists.
     *
     * @param entity    the entity model to update with the new index
     * @param fkColumns the foreign-key column names (in desired index column order)
     * @param tableName the base table name where the index should be applied
     */
    void addForeignKeyIndex(EntityModel entity, List<String> fkColumns, String tableName) {
        if (fkColumns == null || fkColumns.isEmpty()) return;
        if (coveredByPkOrUnique(entity, tableName, fkColumns)) return;

        // 인덱스 컬럼 순서는 "FK 컬럼이 생성된 순서"를 유지하세요 (아래 3번 참고)
        List<String> colsInOrder = List.copyOf(fkColumns);
        if (hasSameIndex(entity, tableName, colsInOrder)) return;
        String indexName = context.getNaming().ixName(tableName, colsInOrder);
        if (entity.getIndexes().containsKey(indexName)) return;

        IndexModel ix = IndexModel.builder()
            .indexName(indexName).tableName(tableName)
            .columnNames(colsInOrder).isUnique(false).build();
        entity.getIndexes().put(indexName, ix);
    }

    /**
     * Extracts a TypeElement from the given TypeMirror when it represents a declared (named) type.
     *
     * @param typeMirror the type to inspect; may be any TypeMirror
     * @return the corresponding TypeElement if the mirror is a DeclaredType whose element is a TypeElement, otherwise {@code null}
     */
    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
            return (TypeElement) declaredType.asElement();
        }
        return null;
    }

    private List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? Collections.emptyList() : Arrays.stream(arr).toList();
    }

    /**
     * Ensures the join table has MANY_TO_ONE relationship entries for both owner and inverse sides
     * and creates corresponding non-unique indexes for the foreign key columns.
     *
     * <p>The method:
     * - Builds owner-side and target-side FK → referenced PK column lists from {@code details}.
     * - Determines constraint names (uses explicit names from {@code details} or generates one via
     *   the naming strategy).
     * - If a relationship with the computed constraint name does not already exist on the join
     *   table, creates a RelationshipModel (type MANY_TO_ONE) with the appropriate table, columns,
     *   referenced table/columns and {@code noConstraint} flag, and registers it on the join table.
     * - Ensures an index exists for each FK column group via {@link #addForeignKeyIndex(EntityModel, List, String)}.
     *
     * @param jt the join-table EntityModel to update
     * @param details encapsulates mappings between join-table FK columns and owner/target PKs,
     *                explicit constraint names, and flags indicating whether constraints should be omitted
     */
    private void ensureJoinTableRelationships(EntityModel jt, JoinTableDetails details) {
        List<String> ownerFks = new ArrayList<>(details.ownerFkToPkMap().keySet());
        List<String> ownerPks = new ArrayList<>(details.ownerFkToPkMap().values());
        String ownerFkName = details.ownerFkConstraintName() != null
                ? details.ownerFkConstraintName()
                : context.getNaming().fkName(details.joinTableName(), ownerFks,
                details.ownerEntity().getTableName(), ownerPks);

        if (!jt.getRelationships().containsKey(ownerFkName)) {
            RelationshipModel rel = RelationshipModel.builder()
                    .type(RelationshipType.MANY_TO_ONE)
                    .tableName(details.joinTableName()) // FK가 걸리는 테이블 (조인테이블)
                    .columns(ownerFks)
                    .referencedTable(details.ownerEntity().getTableName())
                    .referencedColumns(ownerPks)
                    .noConstraint(details.ownerNoConstraint())
                    .constraintName(ownerFkName)
                    .build();
            jt.getRelationships().put(ownerFkName, rel);
            
            // Owner FK 컬럼에 인덱스 생성
            addForeignKeyIndex(jt, ownerFks, details.joinTableName());
        }

        List<String> targetFks = new ArrayList<>(details.inverseFkToPkMap().keySet());
        List<String> targetPks = new ArrayList<>(details.inverseFkToPkMap().values());
        String targetFkName = details.inverseFkConstraintName() != null
                ? details.inverseFkConstraintName()
                : context.getNaming().fkName(details.joinTableName(), targetFks,
                details.referencedEntity().getTableName(), targetPks);

        if (!jt.getRelationships().containsKey(targetFkName)) {
            RelationshipModel rel = RelationshipModel.builder()
                    .type(RelationshipType.MANY_TO_ONE)
                    .tableName(details.joinTableName()) // FK가 걸리는 테이블 (조인테이블)
                    .columns(targetFks)
                    .referencedTable(details.referencedEntity().getTableName())
                    .referencedColumns(targetPks)
                    .noConstraint(details.inverseNoConstraint())
                    .constraintName(targetFkName)
                    .build();
            jt.getRelationships().put(targetFkName, rel);
            
            // Target FK 컬럼에 인덱스 생성
            addForeignKeyIndex(jt, targetFks, details.joinTableName());
        }
    }

    /**
     * Ensure the join table contains foreign-key columns that match the Java types of the referenced primary-key columns.
     *
     * For each entry in ownerFkToPkMap and targetFkToPkMap this method looks up the referenced primary-key
     * ColumnModel (from ownerPks/targetPks) and calls {@link #ensureOneColumn(EntityModel, String, String, String, AttributeDescriptor)}
     * to create or validate a corresponding column on the join table. The provided AttributeDescriptor is used for diagnostics.
     *
     * @param jt the join-table entity to update
     * @param ownerPks list of primary-key columns from the owner entity (used to derive types for owner-side FK columns)
     * @param targetPks list of primary-key columns from the referenced/target entity (used to derive types for target-side FK columns)
     * @param ownerFkToPkMap mapping from join-table owner-side FK column name -> owner PK column name
     * @param targetFkToPkMap mapping from join-table target-side FK column name -> target PK column name
     * @param attr attribute descriptor used as the source for diagnostic messages
     */
    private void ensureJoinTableColumns(
            EntityModel jt,
            List<ColumnModel> ownerPks, List<ColumnModel> targetPks,
            Map<String,String> ownerFkToPkMap, Map<String,String> targetFkToPkMap,
            AttributeDescriptor attr) {

        Map<String, ColumnModel> pkTypeLookup = new HashMap<>();
        ownerPks.forEach(pk -> pkTypeLookup.put("owner::" + pk.getColumnName(), pk));
        targetPks.forEach(pk -> pkTypeLookup.put("target::" + pk.getColumnName(), pk));

        for (Map.Entry<String,String> e : ownerFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            ColumnModel pk = pkTypeLookup.get("owner::" + e.getValue());
            ensureOneColumn(jt, fkName, pk.getJavaType(), jt.getTableName(), attr);
        }

        for (Map.Entry<String,String> e : targetFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            ColumnModel pk = pkTypeLookup.get("target::" + e.getValue());
            ensureOneColumn(jt, fkName, pk.getJavaType(), jt.getTableName(), attr);
        }
    }

    /**
     * Ensure a column with the given name and Java type exists on the specified table of the join-table entity.
     *
     * If the column is missing, it is created as non-nullable. If a column exists but its Java type differs,
     * an error diagnostic is emitted referencing the provided attribute. If the existing column is nullable,
     * it is updated to be non-nullable.
     *
     * @param javaType the expected Java type name for the column (used to validate type compatibility)
     * @param attr     attribute descriptor used as the source for diagnostics when emitting errors
     */
    private void ensureOneColumn(EntityModel jt, String colName, String javaType, String tableName, AttributeDescriptor attr) {
        ColumnModel existing = jt.findColumn(tableName, colName);
        if (existing == null) {
            jt.putColumn(ColumnModel.builder()
                    .columnName(colName)
                    .tableName(tableName)
                    .javaType(javaType)
                    .isNullable(false)
                    .build());
            return;
        }

        if (!Objects.equals(existing.getJavaType(), javaType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Join table column type mismatch for '" + colName + "': expected " + javaType +
                            ", found " + existing.getJavaType(), attr.elementForDiagnostics());
        }

        if (existing.isNullable()) {
            existing.setNullable(false);
        }
    }

    /**
     * Handle the inverse side of a @OneToMany relationship: record a logical relationship only and do not
     * generate any DDL artifacts.
     *
     * <p>This method resolves the target entity from the attribute descriptor, verifies that the
     * specified mappedBy attribute exists on the target entity, and emits diagnostics describing the
     * outcome. No foreign keys, join tables, constraints, or columns are created for inverse sides.
     *
     * <p>Behavior:
     * - If the target entity cannot be resolved, a warning is emitted and processing is skipped.
     * - If the mappedBy attribute is not found on the target entity, a warning is emitted and processing is skipped.
     * - On success, a note is emitted indicating the inverse relationship was processed (no DDL generated).
     *
     * @param attr the attribute descriptor for the field annotated with {@code @OneToMany}
     * @param ownerEntity the entity model that owns the attribute (inverse side)
     * @param oneToMany the {@code @OneToMany} annotation instance read from the attribute
     */
    private void processInverseSideOneToMany(AttributeDescriptor attr, EntityModel ownerEntity, OneToMany oneToMany) {
        // Inverse side: mappedBy is specified, no DB artifacts should be created
        String mappedBy = oneToMany.mappedBy();
        
        Optional<TypeElement> targetEntityElementOpt = resolveTargetEntity(attr, null, null, oneToMany, null);
        if (targetEntityElementOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot resolve target entity for inverse @OneToMany(mappedBy='" + mappedBy + "') on " + 
                    ownerEntity.getEntityName() + "." + attr.name() + ". Skipping logical relationship tracking.", 
                    attr.elementForDiagnostics());
            return;
        }
        
        TypeElement targetEntityElement = targetEntityElementOpt.get();
        String targetEntityName = targetEntityElement.getQualifiedName().toString();
        
        // Verify the mappedBy attribute exists on the target entity
        AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntity.getEntityName(), targetEntityName, mappedBy);
        if (mappedByAttr == null) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot find mappedBy attribute '" + mappedBy + "' on target entity " + targetEntityName + 
                    " for inverse @OneToMany. Relationship may be incomplete.", attr.elementForDiagnostics());
            return;
        }
        
        // Log the inverse side relationship (no DDL generation)
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing inverse @OneToMany(mappedBy='" + mappedBy + "') on " + ownerEntity.getEntityName() + 
                "." + attr.name() + " -> " + targetEntityName + ". No DDL artifacts generated (inverse side).");
    }
    
    /**
     * Handle the inverse side of a `@ManyToMany` mapping: record/log the logical relationship but do not
     * create any database artifacts.
     *
     * <p>Resolves the target entity from the attribute or annotation, verifies that the `mappedBy`
     * attribute exists on the target entity, and emits diagnostics (notes or warnings) describing the
     * inverse-side processing. If the target entity cannot be resolved or the `mappedBy` attribute is
     * missing, a warning is emitted and no relationship is recorded. This method never generates DDL
     * (foreign keys, join tables, etc.) for the inverse side.
     *
     * @param attr descriptor of the attribute annotated with `@ManyToMany`
     * @param ownerEntity entity model that owns the attribute being processed
     * @param manyToMany the `@ManyToMany` annotation instance from the attribute
     */
    private void processInverseSideManyToMany(AttributeDescriptor attr, EntityModel ownerEntity, ManyToMany manyToMany) {
        // Inverse side: mappedBy is specified, no DB artifacts should be created
        String mappedBy = manyToMany.mappedBy();
        
        Optional<TypeElement> targetEntityElementOpt = resolveTargetEntity(attr, null, null, null, manyToMany);
        if (targetEntityElementOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot resolve target entity for inverse @ManyToMany(mappedBy='" + mappedBy + "') on " + 
                    ownerEntity.getEntityName() + "." + attr.name() + ". Skipping logical relationship tracking.", 
                    attr.elementForDiagnostics());
            return;
        }
        
        TypeElement targetEntityElement = targetEntityElementOpt.get();
        String targetEntityName = targetEntityElement.getQualifiedName().toString();
        
        // Verify the mappedBy attribute exists on the target entity
        AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntity.getEntityName(), targetEntityName, mappedBy);
        if (mappedByAttr == null) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot find mappedBy attribute '" + mappedBy + "' on target entity " + targetEntityName + 
                    " for inverse @ManyToMany. Relationship may be incomplete.", attr.elementForDiagnostics());
            return;
        }
        
        // Log the inverse side relationship (no DDL generation)
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing inverse @ManyToMany(mappedBy='" + mappedBy + "') on " + ownerEntity.getEntityName() + 
                "." + attr.name() + " -> " + targetEntityName + ". No DDL artifacts generated (inverse side).");
    }
    
    /**
     * Locate the AttributeDescriptor on the target entity that corresponds to the given
     * mappedBy attribute name, using cached descriptors and guarding against cycles.
     *
     * <p>Searches the cached attribute descriptors for the target entity and returns the first
     * descriptor whose name equals {@code mappedByAttributeName}. To prevent infinite recursion
     * when walking mappedBy chains, the method records the (targetEntityName, mappedByAttributeName)
     * pair as visited and will emit a warning and return {@code null} if that pair is encountered again.
     *
     * @param ownerEntityName        the fully qualified name of the owning entity (used only for diagnostic text)
     * @param targetEntityName       the fully qualified name of the target entity to search
     * @param mappedByAttributeName  the attribute name on the target entity to find
     * @return the matching AttributeDescriptor, or {@code null} if the target entity or attribute is not found
     */
    private AttributeDescriptor findMappedByAttribute(String ownerEntityName, String targetEntityName, String mappedByAttributeName) {
        // Check for infinite recursion
        if (context.isMappedByVisited(targetEntityName, mappedByAttributeName)) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Detected cyclic mappedBy reference: " + ownerEntityName + " -> " + 
                    targetEntityName + "." + mappedByAttributeName + ". Breaking cycle to prevent infinite recursion.");
            return null;
        }
        
        // Mark this relationship as being visited
        context.markMappedByVisited(targetEntityName, mappedByAttributeName);
        
        try {
            EntityModel targetEntity = context.getSchemaModel().getEntities().get(targetEntityName);
            if (targetEntity == null) {
                return null;
            }
            
            TypeElement targetTypeElement = context.getElementUtils().getTypeElement(targetEntityName);
            if (targetTypeElement == null) {
                return null;
            }
            
            // Get cached descriptors to avoid re-computation
            List<AttributeDescriptor> targetDescriptors = context.getCachedDescriptors(targetTypeElement);
            
            // Find attribute with matching name using the same naming convention
            return targetDescriptors.stream()
                    .filter(desc -> desc.name().equals(mappedByAttributeName))
                    .findFirst()
                    .orElse(null);
        } finally {
            // Clean up the visited marker after processing
            // Note: In a real implementation, you might want to keep visited markers 
            // for the duration of the entire processing cycle, not just this call
            // context.markMappedByUnvisited(targetEntityName, mappedByAttributeName);
        }
    }

    private record JoinTableDetails(
            String joinTableName,
            Map<String, String> ownerFkToPkMap,
            Map<String, String> inverseFkToPkMap,
            EntityModel ownerEntity,
            EntityModel referencedEntity,
            String ownerFkConstraintName,
            String inverseFkConstraintName,
            boolean ownerNoConstraint,
            boolean inverseNoConstraint
    ) {}

    /**
     * Returns true if the list is empty or every JoinColumn in the list has the same
     * ForeignKey.ConstraintMode (i.e., the same `foreignKey().value()`).
     *
     * @param jcs the list of JoinColumn instances to compare
     * @return true when the list is empty or all entries share the same constraint mode; false otherwise
     */
    boolean allSameConstraintMode(List<JoinColumn> jcs) {
        if (jcs.isEmpty()) return true;
        ConstraintMode first = jcs.get(0).foreignKey().value();
        for (JoinColumn jc : jcs) {
            if (jc.foreignKey().value() != first) return false;
        }
        return true;
    }
}