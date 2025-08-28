package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.descriptor.PropertyAttributeDescriptor;
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

    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        // 1) 우선 디스크립터 캐시가 있다면 그대로 사용
        try {
            java.util.List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
            if (descriptors != null && !descriptors.isEmpty()) {
                for (AttributeDescriptor d : descriptors) {
                    resolve(d, ownerEntity);
                }
                return;
            }
        } catch (Exception ignore) {
            // 캐시 미구현/예외 시 필드 스캔으로 폴백
        }

        // 2) 폴백: 타입의 멤버를 직접 스캔(예전 테스트 유지)
        for (Element e : ownerType.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e instanceof VariableElement ve) {
                // 기존 테스트가 필드 애노테이션을 목킹하므로 그대로 재사용
                resolve(ve, ownerEntity); // 기존 호환 오버로드 사용
            } else if (e.getKind() == ElementKind.METHOD && e instanceof ExecutableElement ex) {
                // (선택) 게터에 관계 애노가 붙어있다면 프로퍼티 디스크립터로 처리
                if (hasRelationshipAnnotation(ex)) {
                    AttributeDescriptor pd = new PropertyAttributeDescriptor(
                            ex, context.getTypeUtils(), context.getElementUtils());
                    resolve(pd, ownerEntity);
                }
            }
        }
    }

    private boolean hasRelationshipAnnotation(ExecutableElement ex) {
        return ex.getAnnotation(ManyToOne.class) != null ||
                ex.getAnnotation(OneToOne.class)  != null ||
                ex.getAnnotation(OneToMany.class) != null ||
                ex.getAnnotation(ManyToMany.class)!= null;
    }


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
     * 호환성을 위한 VariableElement 오버로드
     * VariableElement를 AttributeDescriptor로 래핑해서 처리
     */
    public void resolve(VariableElement field, EntityModel ownerEntity) {
        AttributeDescriptor fieldAttr = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
        resolve(fieldAttr, ownerEntity);
    }

    /**
     * Process @ManyToOne or @OneToOne relationships
     * Adds FK column to the owning entity
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
        
        // 1:1(단일 FK)이며 @MapsId가 아니고, @JoinColumn 생략이거나(unique=true 지정)인 경우 UNIQUE 제약 추가
        boolean isSingleFk = fkColumnNames.size() == 1;
        boolean shouldAddUnique = (oneToOne != null) && (mapsId == null) && isSingleFk
                && (joinColumns.isEmpty() || joinColumns.get(0).unique());
        if (shouldAddUnique && !coveredByPkOrUnique(ownerEntity, fkBaseTable, fkColumnNames)) {
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
     * Process unidirectional @OneToMany with FK
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
     * Process unidirectional @OneToMany with JoinTable
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
     * Process @JoinTable.uniqueConstraints and add them to the join table entity
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
     * Process owning @ManyToMany relationship
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
        if (joinTable != null && joinTable.uniqueConstraints() != null && joinTable.uniqueConstraints().length > 0) {
            addJoinTableUniqueConstraints(joinTableEntity, joinTable.uniqueConstraints(), attr);
        }

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

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

    private Optional<TypeElement> resolveTargetEntity(AttributeDescriptor attr,
            ManyToOne m2o, OneToOne o2o, OneToMany o2m, ManyToMany m2m) {
        // 1) 명시적 targetEntity 우선 (APT 안전)
        TypeElement explicit = null;
        if (m2o != null) explicit = classValToTypeElement(() -> m2o.targetEntity());
        else if (o2o != null) explicit = classValToTypeElement(() -> o2o.targetEntity());
        else if (o2m != null) explicit = classValToTypeElement(() -> o2m.targetEntity());
        else if (m2m != null) explicit = classValToTypeElement(() -> m2m.targetEntity());
        if (explicit != null) return Optional.of(explicit);

        // 2) 컬렉션이면 제네릭 인자 사용
        if ((o2m != null) || (m2m != null)) {
            return attr.genericArg(0).map(dt -> (TypeElement) dt.asElement());
        }
        // 3) 필드/프로퍼티 타입으로 추론
        return Optional.ofNullable(getReferencedTypeElement(attr.type()));
    }

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
     * 테이블명을 고려하여 컴럼을 찾는 헬퍼 메서드
     * 주/보조 테이블에 동일한 컴럼명이 있을 때 충돌 방지
     */
    private ColumnModel findColumn(EntityModel entity, String tableName, String columnName) {
        return entity.findColumn(tableName, columnName);
    }
    
    /**
     * 컴럼을 엔티티 모델에 추가하는 헬퍼 메서드
     * 테이블과 컬럼명을 조합한 키를 사용하여 충돌 방지
     */
    private void putColumn(EntityModel entity, ColumnModel column) {
        entity.putColumn(column);
    }
    
    /**
     * FK 컬럼 집합이 PK나 UNIQUE 제약으로 이미 커버되는지 확인
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

    boolean hasSameIndex(EntityModel e, String table, List<String> cols) {
        for (IndexModel im : e.getIndexes().values()) {
            if (Objects.equals(table, im.getTableName()) && im.getColumnNames().equals(cols)) return true;
        }
        return false;
    }

    /**
     * FK 컬럼에 자동 인덱스 생성 (성능 향상을 위해)
     * PK/UNIQUE로 이미 커버된 경우는 생략
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

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
            return (TypeElement) declaredType.asElement();
        }
        return null;
    }
    
    /**
     * 클래스값(annotation)에서 TypeElement를 안전하게 추출합니다.
     * APT 환경에서 MirroredTypeException을 적절히 처리합니다.
     */
    private TypeElement classValToTypeElement(java.util.function.Supplier<Class<?>> getter) {
        try {
            Class<?> clz = getter.get();
            if (clz == void.class) return null;
            return context.getElementUtils().getTypeElement(clz.getName());
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return getReferencedTypeElement(mte.getTypeMirror());
        } catch (Throwable t) {
            return null;
        }
    }

    private List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? Collections.emptyList() : Arrays.stream(arr).toList();
    }

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
     * Process inverse side @OneToMany: no DDL artifacts, only logical relationship tracking
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
     * Process inverse side @ManyToMany: no DDL artifacts, only logical relationship tracking
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
     * Find the corresponding attribute descriptor in the target entity using the same naming convention
     * as AttributeDescriptorFactory.extractAttributeName() with caching and cycle detection.
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
            if (targetDescriptors == null) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Descriptor cache miss for target entity " + targetEntityName +
                        " while resolving mappedBy '" + mappedByAttributeName + "'. Skipping.");
                return null;
            }
            
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

    boolean allSameConstraintMode(List<JoinColumn> jcs) {
        if (jcs.isEmpty()) return true;
        ConstraintMode first = jcs.get(0).foreignKey().value();
        for (JoinColumn jc : jcs) {
            if (jc.foreignKey().value() != first) return false;
        }
        return true;
    }
    
    /**
     * 지정된 컬럼들이 이미 PK나 UNIQUE 제약으로 커버되는지 확인합니다.
     * 중복 UNIQUE 제약 생성을 방지하기 위해 사용됩니다.
     */
    private boolean coveredByPkOrUnique(EntityModel entity, String tableName, List<String> columns) {
        // PK로 커버되는지 확인
        List<String> pkColumns = context.findAllPrimaryKeyColumns(entity);
        if (!pkColumns.isEmpty() && entity.getTableName().equals(tableName)) {
            if (pkColumns.size() == columns.size() && pkColumns.containsAll(columns)) {
                return true;
            }
        }
        
        // 기존 UNIQUE 제약으로 커버되는지 확인
        return entity.getConstraints().values().stream()
                .filter(c -> c.getType() == ConstraintType.UNIQUE)
                .filter(c -> tableName.equals(c.getTableName()))
                .anyMatch(c -> c.getColumns() != null 
                        && c.getColumns().size() == columns.size() 
                        && c.getColumns().containsAll(columns));
    }
}