package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Processor for @OneToMany relationships using Foreign Key strategy (owning side)
 */
public final class OneToManyOwningFkProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    
    public OneToManyOwningFkProcessor(ProcessingContext context, RelationshipSupport support) {
        this.context = context;
        this.support = support;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
        if (oneToMany == null || !oneToMany.mappedBy().isEmpty()) {
            return false; // Not owning side or not OneToMany
        }
        
        // OneToMany FK 전략: 반드시 JoinColumn이 있어야 하고, JoinTable은 없어야 함
        // 이 조건을 통과하면 process()에서 j == null인 경우는 발생하지 않음
        JoinTable jt = descriptor.getAnnotation(JoinTable.class);
        JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
        JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
        boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);
        
        return hasJoinColumn && jt == null;
    }
    
    @Override
    public void process(AttributeDescriptor attr, EntityModel ownerEntity) {
        OneToMany oneToMany = attr.getAnnotation(OneToMany.class);
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

        Optional<TypeElement> targetEntityElementOpt = support.resolveTargetEntity(attr, null, null, oneToMany, null);
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
            String firstTable = support.resolveJoinColumnTable(jlist.get(0), targetEntityModel);
            for (int i = 1; i < jlist.size(); i++) {
                String currentTable = support.resolveJoinColumnTable(jlist.get(i), targetEntityModel);
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

            // 일반 엔티티의 FK 네이밍: 속성명 기반 (fieldName + referencedPK)
            String fkName = (j != null && !j.name().isEmpty())
                    ? j.name()
                    : context.getNaming().foreignKeyColumnName(attr.name(), refName);

            // Validate duplicate FK names
            if (fkNames.contains(fkName) || toAdd.containsKey(fkName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkName + "' in "
                                + targetEntityModel.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
                targetEntityModel.setValid(false);
                return;
            }

            String tableNameForFk = support.resolveJoinColumnTable(j, targetEntityModel);
            // nullability 규칙: @OneToMany(FK 전략)에서는 JoinColumn.nullable()을 그대로 따름
            // 스펙상 OneToMany엔 optional이 없고, N쪽에 있는 FK의 nullability가 실제 제약이므로
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .tableName(tableNameForFk)
                    .javaType(ownerPk.getJavaType())
                    .isNullable(j.nullable())
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = support.findColumn(targetEntityModel, tableNameForFk, fkName);
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
            support.putColumn(targetEntityModel, col);
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
                : support.resolveJoinColumnTable(jlist.get(0), targetEntityModel);

        String constraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkNames,
                        ownerEntity.getTableName(), refNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!support.allSameConstraintMode(jlist)) {
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
                .cascadeTypes(support.toCascadeList(oneToMany.cascade()))
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
        support.addForeignKeyIndex(targetEntityModel, fkNames, fkBaseTable);
    }
}