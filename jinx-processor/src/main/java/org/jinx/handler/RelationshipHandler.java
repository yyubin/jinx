package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

public class RelationshipHandler {
    private final ProcessingContext context;

    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveRelationships(TypeElement typeElement, EntityModel entityModel) {
        for (Element field : typeElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD ||
                    field.getAnnotation(Transient.class) != null ||
                    field.getModifiers().contains(Modifier.TRANSIENT)) {
                continue;
            }
            VariableElement variableField = (VariableElement) field;
            resolveFieldRelationships(variableField, entityModel);
        }
    }

    private void resolveFieldRelationships(VariableElement field, EntityModel ownerEntity) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
        OneToMany oneToMany = field.getAnnotation(OneToMany.class);

        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(field, ownerEntity, manyToOne, oneToOne);
            return;
        }

        if (oneToMany != null && oneToMany.mappedBy().isEmpty()) {
            JoinTable jt = field.getAnnotation(JoinTable.class);
            JoinColumns jcs = field.getAnnotation(JoinColumns.class);
            JoinColumn jc = field.getAnnotation(JoinColumn.class);
            boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);

            if (jt != null && hasJoinColumn) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@OneToMany에 @JoinTable과 @JoinColumn(s)를 함께 사용할 수 없습니다.", field);
                return;
            }

            if (hasJoinColumn) {
                processUnidirectionalOneToMany_FK(field, ownerEntity, oneToMany);
            } else {
                processUnidirectionalOneToMany_JoinTable(field, ownerEntity, oneToMany);
            }
            return;
        }

        if (manyToMany != null && manyToMany.mappedBy().isEmpty()) {
            processOwningManyToMany(field, ownerEntity, manyToMany);
        }
    }

    /**
     * @ManyToOne 또는 @OneToOne 관계 처리
     * 소유측 엔티티에 FK 컬럼을 추가
     */
    private void processToOneRelationship(VariableElement field, EntityModel ownerEntity,
                                          ManyToOne manyToOne, OneToOne oneToOne) {
        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) return;

        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key to be referenced.", field);
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));

        JoinColumns joinColumnsAnno = field.getAnnotation(JoinColumns.class);
        JoinColumn joinColumnAnno = field.getAnnotation(JoinColumn.class);

        List<JoinColumn> joinColumns = joinColumnsAnno != null ? Arrays.asList(joinColumnsAnno.value()) :
                (joinColumnAnno != null ? List.of(joinColumnAnno) : Collections.emptyList());

        // 복합 키 검증
        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on " + ownerEntity.getEntityName() + "." + field.getSimpleName(), field);
            ownerEntity.setValid(false);
            return;
        }

        if (!joinColumns.isEmpty() && joinColumns.size() != refPkList.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + field.getSimpleName() +
                            ". Expected " + refPkList.size() + " (from referenced PK), but got " + joinColumns.size() + ".", field);
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNames = new ArrayList<>();
        MapsId mapsId = field.getAnnotation(MapsId.class);
        String mapsIdAttr = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : null;

        // loop1: 사전 검증
        for (int i = 0; i < refPkList.size(); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            String referencedPkName = (jc != null && !jc.referencedColumnName().isEmpty())
                    ? jc.referencedColumnName() : refPkList.get(i).getColumnName();

            ColumnModel referencedPkColumn = refPkMap.get(referencedPkName);
            if (referencedPkColumn == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Attribute referencedColumnName='" + referencedPkName + "' not found in PKs of "
                                + referencedEntity.getEntityName(), field);
                ownerEntity.setValid(false);
                return;
            }

            String fieldName = field.getSimpleName().toString();
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                    ? jc.name()
                    : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

            // 중복 FK 이름 검증
            if (toAdd.containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate FK column name '" + fkColumnName + "' in "
                                + ownerEntity.getEntityName() + "." + field.getSimpleName(), field);
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
                        "@JoinColumn(nullable=true) conflicts with optional=false; treating as NOT NULL.", field);
            }

            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .javaType(referencedPkColumn.getJavaType())
                    .isPrimaryKey(makePk)
                    .isNullable(isNullable)
                    .build();

            // 타입 충돌 검증
            if (ownerEntity.getColumns().containsKey(fkColumnName)) {
                ColumnModel existing = ownerEntity.getColumns().get(fkColumnName);
                if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for column '" + fkColumnName + "' on " + ownerEntity.getEntityName() +
                                    ". FK requires type " + fkColumn.getJavaType() +
                                    " but existing column has type " + existing.getJavaType(), field);
                    ownerEntity.setValid(false);
                    return;
                }
            }

            // 신규 컬럼 보류
            if (!ownerEntity.getColumns().containsKey(fkColumnName)) {
                toAdd.put(fkColumnName, fkColumn);
            }
            fkColumnNames.add(fkColumnName);
            referencedPkNames.add(referencedPkName);
        }

        // loop2: 적용
        ownerEntity.getColumns().putAll(toAdd);

        // FK 제약 조건명 결정
        String explicitFkName = joinColumns.stream()
                .map(JoinColumn::foreignKey)
                .map(ForeignKey::name)
                .filter(name -> name != null && !name.isEmpty())
                .findFirst()
                .orElse(null);

        String relationConstraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(ownerEntity.getTableName(), fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNames);

        // 관계 모델 생성
        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNames)
                .mapsId(mapsId != null)
                .constraintName(relationConstraintName)
                .cascadeTypes(manyToOne != null ? toCascadeList(manyToOne.cascade()) : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();

        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);
    }

    /**
     * 단방향 @OneToMany FK 방식 처리
     */
    private void processUnidirectionalOneToMany_FK(VariableElement field, EntityModel ownerEntity, OneToMany oneToMany) {
        // 제네릭 타입 유효성 검사
        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드는 제네릭 컬렉션 타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeMirror lhs = types.erasure(field.asType());
        TypeMirror rhs = types.erasure(elems.getTypeElement("java.util.Collection").asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드는 java.util.Collection의 서브타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        TypeMirror targetType = typeArgs.get(0);
        TypeElement targetEntityElement = (TypeElement) ((DeclaredType) targetType).asElement();
        EntityModel targetEntityModel = context.getSchemaModel().getEntities()
                .get(targetEntityElement.getQualifiedName().toString());
        if (targetEntityModel == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + ownerEntity.getEntityName() + " must have a primary key for @OneToMany relationship.", field);
            targetEntityModel.setValid(false);
            return;
        }

        JoinColumns jcs = field.getAnnotation(JoinColumns.class);
        JoinColumn jc = field.getAnnotation(JoinColumn.class);
        List<JoinColumn> jlist = jcs != null ? Arrays.asList(jcs.value()) :
                (jc != null ? List.of(jc) : Collections.emptyList());

        if (!jlist.isEmpty() && jlist.size() != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + field.getSimpleName()
                            + ". Expected " + ownerPks.size() + ", but got " + jlist.size() + ".", field);
            targetEntityModel.setValid(false);
            return;
        }

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkNames = new ArrayList<>();
        List<String> refNames = new ArrayList<>();

        // loop1: 사전 검증
        for (int i = 0; i < ownerPks.size(); i++) {
            ColumnModel ownerPk = ownerPks.get(i);
            JoinColumn j = jlist.isEmpty() ? null : jlist.get(i);

            String refName = (j != null && !j.referencedColumnName().isEmpty())
                    ? j.referencedColumnName() : ownerPk.getColumnName();

            String fkName = (j != null && !j.name().isEmpty())
                    ? j.name()
                    : context.getNaming().foreignKeyColumnName(ownerEntity.getTableName(), refName);

            // 중복 FK 이름 검증
            if (toAdd.containsKey(fkName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate FK column name '" + fkName + "' in "
                                + targetEntityModel.getEntityName() + "." + field.getSimpleName(), field);
                targetEntityModel.setValid(false);
                return;
            }

            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .javaType(ownerPk.getJavaType())
                    .isNullable(j == null || j.nullable())
                    .build();

            // 타입 충돌 검증
            if (targetEntityModel.getColumns().containsKey(fkName)) {
                ColumnModel existing = targetEntityModel.getColumns().get(fkName);
                if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for implicit foreign key column '" + fkName + "' in " +
                                    targetEntityModel.getEntityName() + ". Expected type " + fkColumn.getJavaType() +
                                    " but found existing column with type " + existing.getJavaType(), field);
                    targetEntityModel.setValid(false);
                    return;
                }
            }

            // 신규 컬럼 보류
            if (!targetEntityModel.getColumns().containsKey(fkName)) {
                toAdd.put(fkName, fkColumn);
            }
            fkNames.add(fkName);
            refNames.add(refName);
        }

        // loop2: 적용
        targetEntityModel.getColumns().putAll(toAdd);

        // FK 제약 조건명 결정
        String constraintName = jlist.stream()
                .findFirst()
                .map(JoinColumn::foreignKey)
                .filter(fk -> !fk.name().isEmpty())
                .map(ForeignKey::name)
                .orElseGet(() -> context.getNaming().fkName(targetEntityModel.getTableName(), fkNames,
                        ownerEntity.getTableName(), refNames));

        // 관계 모델 생성
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.ONE_TO_MANY)
                .columns(fkNames)
                .referencedTable(ownerEntity.getTableName())
                .referencedColumns(refNames)
                .constraintName(constraintName)
                .cascadeTypes(toCascadeList(oneToMany.cascade()))
                .orphanRemoval(oneToMany.orphanRemoval())
                .fetchType(oneToMany.fetch())
                .build();

        targetEntityModel.getRelationships().put(rel.getConstraintName(), rel);
    }

    /**
     * 단방향 @OneToMany JoinTable 방식 처리
     */
    private void processUnidirectionalOneToMany_JoinTable(VariableElement field, EntityModel ownerEntity, OneToMany oneToMany) {
        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드는 제네릭 컬렉션 타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeMirror lhs = types.erasure(field.asType());
        TypeMirror rhs = types.erasure(elems.getTypeElement("java.util.Collection").asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany 필드는 java.util.Collection의 서브타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        TypeMirror targetType = typeArgs.get(0);
        TypeElement targetEntityElement = (TypeElement) ((DeclaredType) targetType).asElement();
        EntityModel targetEntity = context.getSchemaModel().getEntities()
                .get(targetEntityElement.getQualifiedName().toString());
        if (targetEntity == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        List<ColumnModel> targetPks = context.findAllPrimaryKeyColumns(targetEntity);

        if (ownerPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner 엔티티에 PK가 필요합니다(@OneToMany JoinTable).", field);
            return;
        }
        if (targetPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Target 엔티티에 PK가 필요합니다(@OneToMany JoinTable).", field);
            return;
        }

        JoinTable jt = field.getAnnotation(JoinTable.class);
        String joinTableName = (jt != null && !jt.name().isEmpty())
                ? jt.name()
                : context.getNaming().joinTableName(ownerEntity.getTableName(), targetEntity.getTableName());

        if (context.getSchemaModel().getEntities().containsKey(joinTableName)) {
            return;
        }

        JoinColumn[] joinColumns = (jt != null) ? jt.joinColumns() : new JoinColumn[0];
        JoinColumn[] inverseJoinColumns = (jt != null) ? jt.inverseJoinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.joinColumns 개수가 Owner PK 개수와 일치해야 합니다: expected " + ownerPks.size()
                            + ", found " + joinColumns.length + ".", field);
            return;
        }
        if (inverseJoinColumns.length > 0 && inverseJoinColumns.length != targetPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.inverseJoinColumns 개수가 Target PK 개수와 일치해야 합니다: expected " + targetPks.size()
                            + ", found " + inverseJoinColumns.length + ".", field);
            return;
        }

        String ownerFkConstraint = Arrays.stream(joinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);
        String targetFkConstraint = Arrays.stream(inverseJoinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);

        Map<String,String> ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName());
        Map<String,String> targetFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, targetPks, targetEntity.getTableName());

        JoinTableDetails details = new JoinTableDetails(
                joinTableName, ownerFkToPkMap, targetFkToPkMap, ownerEntity, targetEntity,
                ownerFkConstraint, targetFkConstraint
        );

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, targetPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    /**
     * 소유측 @ManyToMany 관계 처리
     */
    private void processOwningManyToMany(VariableElement field, EntityModel ownerEntity, ManyToMany manyToMany) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);

        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany 필드는 제네릭 컬렉션 타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany 필드의 제네릭 타입 파라미터를 확인할 수 없습니다. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeMirror lhs = types.erasure(field.asType());
        TypeMirror rhs = types.erasure(elems.getTypeElement("java.util.Collection").asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany 필드는 java.util.Collection의 서브타입이어야 합니다. field=" + field.getSimpleName(), field);
            return;
        }

        TypeMirror targetType = typeArgs.get(0);
        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) targetType).asElement();
        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);
        List<ColumnModel> referencedPks = context.findAllPrimaryKeyColumns(referencedEntity);

        if (ownerPks.isEmpty() || referencedPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entities in a @ManyToMany relationship must have a primary key.", field);
            return;
        }

        String joinTableName = (joinTable != null && !joinTable.name().isEmpty())
                ? joinTable.name()
                : context.getNaming().joinTableName(ownerEntity.getTableName(), referencedEntity.getTableName());

        if (context.getSchemaModel().getEntities().containsKey(joinTableName)) {
            return;
        }

        JoinColumn[] joinColumns = (joinTable != null) ? joinTable.joinColumns() : new JoinColumn[0];
        JoinColumn[] inverseJoinColumns = (joinTable != null) ? joinTable.inverseJoinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The number of @JoinColumn annotations for " + ownerEntity.getTableName() +
                            " must match its primary key columns: expected " + ownerPks.size() +
                            ", found " + joinColumns.length + ".", field);
            return;
        }
        if (inverseJoinColumns.length > 0 && inverseJoinColumns.length != referencedPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The number of @InverseJoinColumn annotations for " + referencedEntity.getTableName() +
                            " must match its primary key columns: expected " + referencedPks.size() +
                            ", found " + inverseJoinColumns.length + ".", field);
            return;
        }

        String ownerFkConstraint = Arrays.stream(joinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);

        String inverseFkConstraint = Arrays.stream(inverseJoinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);

        Map<String, String> ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName());
        Map<String, String> inverseFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, referencedPks, referencedEntity.getTableName());

        JoinTableDetails details = new JoinTableDetails(joinTableName, ownerFkToPkMap, inverseFkToPkMap,
                ownerEntity, referencedEntity, ownerFkConstraint, inverseFkConstraint);

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, referencedPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private Map<String, String> resolveJoinColumnMapping(JoinColumn[] joinColumns, List<ColumnModel> referencedPks, String entityTableName) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (joinColumns == null || joinColumns.length == 0) {
            referencedPks.forEach(pk -> mapping.put(
                    context.getNaming().foreignKeyColumnName(entityTableName, pk.getColumnName()),
                    pk.getColumnName()
            ));
        } else {
            for (int i = 0; i < joinColumns.length; i++) {
                JoinColumn jc = joinColumns[i];
                String pkName = jc.referencedColumnName().isEmpty()
                        ? referencedPks.get(i).getColumnName()
                        : jc.referencedColumnName();
                String fkName = jc.name().isEmpty()
                        ? context.getNaming().foreignKeyColumnName(entityTableName, pkName)
                        : jc.name();
                if (mapping.containsKey(fkName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate FK column '" + fkName + "' in join table mapping for " + entityTableName);
                    continue;
                }
                mapping.put(fkName, pkName);
            }
        }
        return mapping;
    }

    private EntityModel createJoinTableEntity(JoinTableDetails details, List<ColumnModel> ownerPks, List<ColumnModel> referencedPks) {
        EntityModel joinTableEntity = EntityModel.builder()
                .entityName(details.joinTableName())
                .tableName(details.joinTableName())
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();

        details.ownerFkToPkMap().forEach((fkName, pkName) -> {
            ColumnModel pk = ownerPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Owner PK '" + pkName + "' not found while creating join table '" + details.joinTableName() + "'"));
            joinTableEntity.getColumns().put(fkName, ColumnModel.builder()
                    .columnName(fkName).javaType(pk.getJavaType()).isPrimaryKey(true).isNullable(false).build());
        });

        details.inverseFkToPkMap().forEach((fkName, pkName) -> {
            ColumnModel pk = referencedPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Referenced PK '" + pkName + "' not found while creating join table '" + details.joinTableName() + "'"));
            joinTableEntity.getColumns().put(fkName, ColumnModel.builder()
                    .columnName(fkName).javaType(pk.getJavaType()).isPrimaryKey(true).isNullable(false).build());
        });

        return joinTableEntity;
    }

    private void addRelationshipsToJoinTable(EntityModel joinTableEntity, JoinTableDetails details) {
        List<String> ownerFkColumns = new ArrayList<>(details.ownerFkToPkMap().keySet());
        List<String> ownerPkColumns = new ArrayList<>(details.ownerFkToPkMap().values());

        RelationshipModel ownerRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .columns(ownerFkColumns)
                .referencedTable(details.ownerEntity().getTableName())
                .referencedColumns(ownerPkColumns)
                .constraintName(details.ownerFkConstraintName() != null
                        ? details.ownerFkConstraintName()
                        : context.getNaming().fkName(details.joinTableName(), ownerFkColumns,
                        details.ownerEntity().getTableName(), ownerPkColumns))
                .build();
        joinTableEntity.getRelationships().put(ownerRel.getConstraintName(), ownerRel);

        List<String> targetFkColumns = new ArrayList<>(details.inverseFkToPkMap().keySet());
        List<String> targetPkColumns = new ArrayList<>(details.inverseFkToPkMap().values());

        RelationshipModel targetRel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .columns(targetFkColumns)
                .referencedTable(details.referencedEntity().getTableName())
                .referencedColumns(targetPkColumns)
                .constraintName(details.inverseFkConstraintName() != null
                        ? details.inverseFkConstraintName()
                        : context.getNaming().fkName(details.joinTableName(), targetFkColumns,
                        details.referencedEntity().getTableName(), targetPkColumns))
                .build();
        joinTableEntity.getRelationships().put(targetRel.getConstraintName(), targetRel);
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
            return (TypeElement) declaredType.asElement();
        }
        return null;
    }

    private List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? Collections.emptyList() : Arrays.stream(arr).toList();
    }

    private record JoinTableDetails(
            String joinTableName,
            Map<String, String> ownerFkToPkMap,
            Map<String, String> inverseFkToPkMap,
            EntityModel ownerEntity,
            EntityModel referencedEntity,
            String ownerFkConstraintName,
            String inverseFkConstraintName
    ) {}
}