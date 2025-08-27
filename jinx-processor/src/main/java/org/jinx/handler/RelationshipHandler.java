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
                        "Cannot use both @JoinTable and @JoinColumn(s) with @OneToMany.", field);
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
     * Process @ManyToOne or @OneToOne relationships
     * Adds FK column to the owning entity
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

        // Validate composite key
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

        // loop1: Pre-validation
        for (int i = 0; i < refPkList.size(); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            String referencedPkName = (jc != null && !jc.referencedColumnName().isEmpty())
                    ? jc.referencedColumnName() : refPkList.get(i).getColumnName();

            ColumnModel referencedPkColumn = refPkMap.get(referencedPkName);
            if (referencedPkColumn == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Referenced column name '" + referencedPkName + "' not found in primary keys of "
                                + referencedEntity.getEntityName(), field);
                ownerEntity.setValid(false);
                return;
            }

            String fieldName = field.getSimpleName().toString();
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                    ? jc.name()
                    : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

            // Validate duplicate FK names
            if (fkColumnNames.contains(fkColumnName) || toAdd.containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkColumnName + "' in "
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

            // Validate type conflicts
            if (ownerEntity.getColumns().containsKey(fkColumnName)) {
                ColumnModel existing = ownerEntity.getColumns().get(fkColumnName);
                if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for column '" + fkColumnName + "' on " + ownerEntity.getEntityName() +
                                    ". Foreign key requires type " + fkColumn.getJavaType() +
                                    " but existing column has type " + existing.getJavaType(), field);
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

            // Defer new column addition
            if (!ownerEntity.getColumns().containsKey(fkColumnName)) {
                toAdd.put(fkColumnName, fkColumn);
            }
            fkColumnNames.add(fkColumnName);
            referencedPkNames.add(referencedPkName);
        }

        // loop2: Apply
        ownerEntity.getColumns().putAll(toAdd);

        // Determine FK constraint name
        String explicitFkName = joinColumns.stream()
                .map(JoinColumn::foreignKey)
                .map(ForeignKey::name)
                .filter(name -> name != null && !name.isEmpty())
                .findFirst()
                .orElse(null);

        String relationConstraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(ownerEntity.getTableName(), fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNames);

        // Create relationship model
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
     * Process unidirectional @OneToMany with FK
     */
    private void processUnidirectionalOneToMany_FK(VariableElement field, EntityModel ownerEntity, OneToMany oneToMany) {
        // Validate generic type
        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany field must be a generic collection type. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve generic type parameter for @OneToMany field. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeMirror lhs = types.erasure(field.asType());
        TypeElement collTe = elems.getTypeElement("java.util.Collection");
        if (collTe == null) return;
        TypeMirror rhs = types.erasure(collTe.asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany field must be a subtype of java.util.Collection. field=" + field.getSimpleName(), field);
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
                                + targetEntityModel.getEntityName() + "." + field.getSimpleName(), field);
                targetEntityModel.setValid(false);
                return;
            }

            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .javaType(ownerPk.getJavaType())
                    .isNullable(j == null || j.nullable())
                    .build();

            // Validate type conflicts
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

            // Defer new column addition
            if (!targetEntityModel.getColumns().containsKey(fkName)) {
                toAdd.put(fkName, fkColumn);
            }
            fkNames.add(fkName);
            refNames.add(refName);
        }

        // loop2: Apply
        targetEntityModel.getColumns().putAll(toAdd);

        // Determine FK constraint name
        String constraintName = jlist.stream()
                .findFirst()
                .map(JoinColumn::foreignKey)
                .filter(fk -> !fk.name().isEmpty())
                .map(ForeignKey::name)
                .orElseGet(() -> context.getNaming().fkName(targetEntityModel.getTableName(), fkNames,
                        ownerEntity.getTableName(), refNames));

        // Create relationship model
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
     * Process unidirectional @OneToMany with JoinTable
     */
    private void processUnidirectionalOneToMany_JoinTable(VariableElement field, EntityModel ownerEntity, OneToMany oneToMany) {
        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany field must be a generic collection type. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve generic type parameter for @OneToMany field. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeMirror lhs = types.erasure(field.asType());
        TypeElement collTe = elems.getTypeElement("java.util.Collection");
        if (collTe == null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve the symbol java.util.Collection.", field);
            return;
        }
        TypeMirror rhs = types.erasure(collTe.asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@OneToMany field must be a subtype of java.util.Collection. field=" + field.getSimpleName(), field);
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
                    "Owner entity requires a primary key for @OneToMany with JoinTable.", field);
            return;
        }
        if (targetPks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Target entity requires a primary key for @OneToMany with JoinTable.", field);
            return;
        }

        JoinTable jt = field.getAnnotation(JoinTable.class);
        String joinTableName = (jt != null && !jt.name().isEmpty())
                ? jt.name()
                : context.getNaming().joinTableName(ownerEntity.getTableName(), targetEntity.getTableName());

        JoinColumn[] joinColumns = (jt != null) ? jt.joinColumns() : new JoinColumn[0];
        JoinColumn[] inverseJoinColumns = (jt != null) ? jt.inverseJoinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.joinColumns count must match owner primary key count: expected " + ownerPks.size()
                            + ", found " + joinColumns.length + ".", field);
            return;
        }
        if (inverseJoinColumns.length > 0 && inverseJoinColumns.length != targetPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable.inverseJoinColumns count must match target primary key count: expected " + targetPks.size()
                            + ", found " + inverseJoinColumns.length + ".", field);
            return;
        }

        try {
            validateExplicitJoinColumns(joinColumns, ownerPks, field, "owner");
            validateExplicitJoinColumns(inverseJoinColumns, targetPks, field, "target");
        } catch (IllegalStateException ex) {
            return;
        }

        String ownerFkConstraint = Arrays.stream(joinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);
        String targetFkConstraint = Arrays.stream(inverseJoinColumns)
                .map(JoinColumn::foreignKey).map(ForeignKey::name)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse(null);

        Map<String,String> ownerFkToPkMap;
        Map<String,String> targetFkToPkMap;
        try {
            ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), field, true);
            targetFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, targetPks, targetEntity.getTableName(), field, false);
        } catch (IllegalStateException ex) {
            return;
        }

        Set<String> ownerFks = new HashSet<>(ownerFkToPkMap.keySet());
        ownerFks.retainAll(targetFkToPkMap.keySet());
        if (!ownerFks.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable foreign key name collision across sides: " + ownerFks + " (owner vs target).", field);
            return;
        }

        // Final validation of mapping count
        if (ownerFkToPkMap.size() != ownerPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner join-columns could not be resolved to all primary key columns. expected=" + ownerPks.size()
                            + ", found=" + ownerFkToPkMap.size(), field);
            return;
        }
        if (targetFkToPkMap.size() != targetPks.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Target join-columns could not be resolved to all primary key columns. expected=" + targetPks.size()
                            + ", found=" + targetFkToPkMap.size(), field);
            return;
        }

        JoinTableDetails details = new JoinTableDetails(
                joinTableName, ownerFkToPkMap, targetFkToPkMap, ownerEntity, targetEntity,
                ownerFkConstraint, targetFkConstraint
        );

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            ensureJoinTableColumns(existing, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, field);
            ensureJoinTableRelationships(existing, details);
            addOneToManyJoinTableUnique(existing, targetFkToPkMap);
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, targetPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        // 1:N semantics: target FK set must be unique
        ensureJoinTableColumns(joinTableEntity, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, field);
        ensureJoinTableRelationships(joinTableEntity, details);
        addOneToManyJoinTableUnique(joinTableEntity, targetFkToPkMap);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private void validateExplicitJoinColumns(JoinColumn[] joinColumns, List<ColumnModel> pks,
                                             VariableElement field, String sideLabel) {
        if (pks.size() > 1 && joinColumns.length > 0) {
            for (int i = 0; i < joinColumns.length; i++) {
                JoinColumn jc = joinColumns[i];
                if (jc.referencedColumnName() == null || jc.referencedColumnName().isEmpty()) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Composite primary key requires explicit referencedColumnName for all @" + sideLabel
                                    + " JoinColumns (index " + i + ", pkCount = " + pks.size() + ", joinColumnsCount = " + joinColumns.length  + ").", field);
                    throw new IllegalStateException("invalid joinColumns");
                }
            }
        }
    }

    private Map<String, String> resolveJoinColumnMapping(JoinColumn[] joinColumns,
                                                         List<ColumnModel> referencedPks,
                                                         String entityTableName,
                                                         VariableElement field,
                                                         boolean isOwnerSide) {
        String side = isOwnerSide ? "owner" : "target";
        Map<String, String> mapping = new LinkedHashMap<>();
        if (joinColumns == null || joinColumns.length == 0) {
            referencedPks.forEach(pk -> {
                String fk = context.getNaming().foreignKeyColumnName(entityTableName, pk.getColumnName());
                if (mapping.containsKey(fk)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fk + "' in join table mapping for " + entityTableName + " (side=" + side + ")", field);
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
                            "referencedColumnName '" + pkName + "' is not a primary key column of " + entityTableName + " (side=" + side + ")", field);
                    throw new IllegalStateException("invalid referencedColumnName");
                }
                String fkName = jc.name().isEmpty()
                        ? context.getNaming().foreignKeyColumnName(entityTableName, pkName)
                        : jc.name();
                if (mapping.containsKey(fkName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fkName + "' in join table mapping for " + entityTableName, field);
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
        cols.sort(Comparator.naturalOrder());
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
     * Process owning @ManyToMany relationship
     */
    private void processOwningManyToMany(VariableElement field, EntityModel ownerEntity, ManyToMany manyToMany) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);

        if (!(field.asType() instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany field must be a generic collection type. field=" + field.getSimpleName(), field);
            return;
        }

        List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
        if (typeArgs == null || typeArgs.isEmpty() || !(typeArgs.get(0) instanceof DeclaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve generic type parameter for @ManyToMany field. field=" + field.getSimpleName(), field);
            return;
        }

        var types = context.getTypeUtils();
        var elems = context.getElementUtils();
        TypeElement collTe = elems.getTypeElement("java.util.Collection");
        if (collTe == null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve the symbol java.util.Collection", field);
            return;
        }
        TypeMirror lhs = types.erasure(field.asType());
        TypeMirror rhs = types.erasure(collTe.asType());
        if (!types.isAssignable(lhs, rhs)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@ManyToMany field must be a subtype of java.util.Collection. field=" + field.getSimpleName(), field);
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

        try {
            validateExplicitJoinColumns(joinColumns, ownerPks, field, "owner");
            validateExplicitJoinColumns(inverseJoinColumns, referencedPks, field, "target");
        } catch (IllegalStateException ex) {
            return;
        }

        Map<String, String> ownerFkToPkMap;
        Map<String, String> inverseFkToPkMap;

        try {
            ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), field, true);
            inverseFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, referencedPks, referencedEntity.getTableName(), field, false);
        } catch (IllegalStateException ex) {
            return;
        }

        Set<String> overlap = new HashSet<>(ownerFkToPkMap.keySet());
        overlap.retainAll(inverseFkToPkMap.keySet());
        if (!overlap.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable foreign key name collision across sides: " + overlap + " (owner vs target).", field);
            return;
        }

        JoinTableDetails details = new JoinTableDetails(joinTableName, ownerFkToPkMap, inverseFkToPkMap,
                ownerEntity, referencedEntity, ownerFkConstraint, inverseFkConstraint);

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            ensureJoinTableColumns(existing, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, field);
            ensureJoinTableRelationships(existing, details);
            addManyToManyPkConstraint(existing, ownerFkToPkMap, inverseFkToPkMap);
            return;
        }

        EntityModel joinTableEntity = createJoinTableEntity(details, ownerPks, referencedPks);
        addRelationshipsToJoinTable(joinTableEntity, details);

        // N:N semantics: composite PK(owner_fk + target_fk)
        addManyToManyPkConstraint(joinTableEntity, ownerFkToPkMap, inverseFkToPkMap);
        ensureJoinTableColumns(joinTableEntity, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, field);
        ensureJoinTableRelationships(joinTableEntity, details);

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private void addManyToManyPkConstraint(EntityModel joinTableEntity,
                                           Map<String,String> ownerFkToPkMap,
                                           Map<String,String> inverseFkToPkMap) {
        List<String> cols = new ArrayList<>();
        cols.addAll(ownerFkToPkMap.keySet());
        cols.addAll(inverseFkToPkMap.keySet());
        if (cols.isEmpty()) return;

        cols.sort(Comparator.naturalOrder());
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

        details.ownerFkToPkMap().forEach((fkName, pkName) -> {
            ColumnModel pk = ownerPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Owner primary key '" + pkName + "' not found while creating join table '" + details.joinTableName() + "'"));
            joinTableEntity.getColumns().put(fkName, ColumnModel.builder()
                    .columnName(fkName).javaType(pk.getJavaType()).isNullable(false).build());
        });

        details.inverseFkToPkMap().forEach((fkName, pkName) -> {
            ColumnModel pk = referencedPks.stream()
                    .filter(p -> p.getColumnName().equals(pkName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Referenced primary key '" + pkName + "' not found while creating join table '" + details.joinTableName() + "'"));
            joinTableEntity.getColumns().put(fkName, ColumnModel.builder()
                    .columnName(fkName).javaType(pk.getJavaType()).isNullable(false).build());
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
                    .columns(ownerFks)
                    .referencedTable(details.ownerEntity().getTableName())
                    .referencedColumns(ownerPks)
                    .constraintName(ownerFkName)
                    .build();
            jt.getRelationships().put(ownerFkName, rel);
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
                    .columns(targetFks)
                    .referencedTable(details.referencedEntity().getTableName())
                    .referencedColumns(targetPks)
                    .constraintName(targetFkName)
                    .build();
            jt.getRelationships().put(targetFkName, rel);
        }
    }

    private void ensureJoinTableColumns(
            EntityModel jt,
            List<ColumnModel> ownerPks, List<ColumnModel> targetPks,
            Map<String,String> ownerFkToPkMap, Map<String,String> targetFkToPkMap,
            VariableElement field) {

        Map<String, ColumnModel> pkTypeLookup = new HashMap<>();
        ownerPks.forEach(pk -> pkTypeLookup.put("owner::" + pk.getColumnName(), pk));
        targetPks.forEach(pk -> pkTypeLookup.put("target::" + pk.getColumnName(), pk));

        for (Map.Entry<String,String> e : ownerFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            ColumnModel pk = pkTypeLookup.get("owner::" + e.getValue());
            ensureOneColumn(jt, fkName, pk.getJavaType(), field);
        }

        for (Map.Entry<String,String> e : targetFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            ColumnModel pk = pkTypeLookup.get("target::" + e.getValue());
            ensureOneColumn(jt, fkName, pk.getJavaType(), field);
        }
    }

    private void ensureOneColumn(EntityModel jt, String colName, String javaType, VariableElement field) {
        ColumnModel existing = jt.getColumns().get(colName);
        if (existing == null) {
            jt.getColumns().put(colName, ColumnModel.builder()
                    .columnName(colName)
                    .javaType(javaType)
                    .isNullable(false)
                    .build());
            return;
        }

        if (!Objects.equals(existing.getJavaType(), javaType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Join table column type mismatch for '" + colName + "': expected " + javaType +
                            ", found " + existing.getJavaType(), field);
        }

        if (existing.isNullable()) {
            existing.setNullable(false);
        }
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