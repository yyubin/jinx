package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processor for @ManyToOne and @OneToOne relationships
 */
public final class ToOneRelationshipProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    
    public ToOneRelationshipProcessor(ProcessingContext context) {
        this.context = context;
        this.support = new RelationshipSupport(context);
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
        
        // ManyToOne는 항상 owning side (mappedBy 없음)
        if (manyToOne != null) {
            return true;
        }
        
        // OneToOne은 mappedBy가 없는 경우만 (owning side)
        if (oneToOne != null && oneToOne.mappedBy().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public void process(AttributeDescriptor descriptor, EntityModel ownerEntity) {
        ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);

        // supports()에서 이미 owning side만 필터링하므로 inverse side 체크 불필요

        Optional<TypeElement> referencedTypeElementOpt = support.resolveTargetEntity(descriptor, manyToOne, oneToOne, null, null);
        if (referencedTypeElementOpt.isEmpty()) return;
        TypeElement referencedTypeElement = referencedTypeElementOpt.get();

        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) {
            // 참조된 엔티티가 아직 처리되지 않은 경우 처리를 지연
            // 이는 엔티티들이 의존성 순서와 무관하게 처리되는 상황 대비
            context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Deferring FK generation for @" + (manyToOne != null ? "ManyToOne" : "OneToOne") +
                    " relationship to '" + referencedTypeElement.getQualifiedName() +
                    "' (referenced entity not yet processed). Will retry in deferred pass.",
                    descriptor.elementForDiagnostics());

            String ownerEntityName = ownerEntity.getEntityName();
            if (!context.getDeferredNames().contains(ownerEntityName)) {
                context.getDeferredEntities().offer(ownerEntity);
                context.getDeferredNames().add(ownerEntityName);
            }
            return;
        }

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Entity " + referencedEntity.getEntityName() + " must have a primary key to be referenced.", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c));

        JoinColumns joinColumnsAnno = descriptor.getAnnotation(JoinColumns.class);
        JoinColumn joinColumnAnno = descriptor.getAnnotation(JoinColumn.class);

        List<JoinColumn> joinColumns = joinColumnsAnno != null ? Arrays.asList(joinColumnsAnno.value()) :
                (joinColumnAnno != null ? List.of(joinColumnAnno) : Collections.emptyList());

        // Validate composite key
        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on " + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (!joinColumns.isEmpty() && joinColumns.size() != refPkList.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch on " + ownerEntity.getEntityName() + "." + descriptor.name() +
                            ". Expected " + refPkList.size() + " (from referenced PK), but got " + joinColumns.size() + ".", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // Check if this relationship has already been processed (deferred retry scenario)
        RelationshipModel existingRelationship = ownerEntity.getRelationships().values().stream()
                .filter(rel -> descriptor.name().equals(rel.getSourceAttributeName()))
                .findFirst()
                .orElse(null);

        Map<String, ColumnModel> toAdd = new LinkedHashMap<>();
        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNames = new ArrayList<>();
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        String mapsIdAttr = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : null;

        // If relationship already exists, skip FK creation but still check for UNIQUE constraint (OneToOne)
        if (existingRelationship != null) {
            // Extract FK column names from existing relationship for UNIQUE constraint check
            fkColumnNames = new ArrayList<>(existingRelationship.getColumns());
            String fkBaseTable = existingRelationship.getTableName();

            // Jump to UNIQUE constraint check for OneToOne
            // OneToOne 관계는 논리적으로 항상 UNIQUE해야 하므로 @JoinColumn.unique 값과 무관하게 추가
            if (oneToOne != null && mapsId == null && fkColumnNames.size() == 1) {
                if (!support.coveredByPkOrUnique(ownerEntity, fkBaseTable, fkColumnNames)) {
                    String uqName = context.getNaming().uqName(fkBaseTable, fkColumnNames);
                    if (!ownerEntity.getConstraints().containsKey(uqName)) {
                        ownerEntity.getConstraints().put(uqName, ConstraintModel.builder()
                            .name(uqName).type(ConstraintType.UNIQUE)
                            .tableName(fkBaseTable).columns(new ArrayList<>(fkColumnNames)).build());
                    }
                }
            }

            // FK index check (may have been skipped in previous pass)
            support.addForeignKeyIndex(ownerEntity, fkColumnNames, fkBaseTable);
            return; // Already processed, skip FK and relationship creation
        }

        // Validate that all JoinColumns use the same table for composite keys
        if (joinColumns.size() > 1) {
            String firstTable = support.resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            for (int i = 1; i < joinColumns.size(); i++) {
                String currentTable = support.resolveJoinColumnTable(joinColumns.get(i), ownerEntity);
                if (!firstTable.equals(currentTable)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "All @JoinColumn annotations in composite key must target the same table. Found: '" + firstTable + "' and '" + currentTable + "'.", descriptor.elementForDiagnostics());
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
                                + referencedEntity.getEntityName(), descriptor.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            String fieldName = descriptor.name();
            // 일반 엔티티의 FK 네이밍: 속성명 기반 (fieldName + referencedPK)
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                    ? jc.name()
                    : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

            // Validate duplicate FK names
            if (fkColumnNames.contains(fkColumnName) || toAdd.containsKey(fkColumnName)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate foreign key column name '" + fkColumnName + "' in "
                                + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
                ownerEntity.setValid(false);
                return;
            }

            // PK 승격은 후처리(MapsId) 단계에서 수행. 여기서는 nullability만 결정.
            // nullability 규칙: ToOne 관계에서는 optional과 JoinColumn.nullable() 둘 다 고려
            // - optional=false이면 무조건 NOT NULL (JoinColumn.nullable=true여도 무시)
            // - optional=true이면 JoinColumn.nullable() 값을 따름 (기본값: true)
            boolean associationOptional =
                    (manyToOne != null) ? manyToOne.optional() : oneToOne.optional();
            boolean columnNullableFromAnno =
                    (jc != null) ? jc.nullable() : associationOptional;
            boolean isNullable = associationOptional && columnNullableFromAnno;

            if (!associationOptional && jc != null && jc.nullable()) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@JoinColumn(nullable=true) conflicts with optional=false; treating as NOT NULL.", descriptor.elementForDiagnostics());
            }

            // @MapsId로 PK가 될 컬럼이라도 여기서는 isNullable=true로 생성될 수 있음.
            // 최종 nullability는 processMapsIdAttributes에서 PK로 승격하며 isNullable=false로 강제함.
            if (!associationOptional) {
                isNullable = false;
            }

            String tableNameForFk = support.resolveJoinColumnTable(jc, ownerEntity);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .tableName(tableNameForFk)
                    .javaType(referencedPkColumn.getJavaType())
                    .isPrimaryKey(false) // PK 승격은 MapsId 후처리 단계에서.
                    .isNullable(isNullable)
                    .build();

            // Validate type conflicts (table-aware lookup)
            ColumnModel existing = ownerEntity.findColumn(tableNameForFk, fkColumnName);
            if (existing != null) {
                if (existing.getJavaType() == null) {
                    existing.setJavaType(fkColumn.getJavaType());
                }
                if (!Objects.equals(existing.getJavaType(), fkColumn.getJavaType())) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Type mismatch for column '" + fkColumnName + "' in table '" + tableNameForFk + "' on " + ownerEntity.getEntityName() +
                                    ". Foreign key requires type " + fkColumn.getJavaType() +
                                    " but existing column has type " + existing.getJavaType(), descriptor.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
                // If types match, apply relationship constraints (nullable) to existing column
                // PK promotion is deferred.
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
            ownerEntity.putColumn(col);
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
                            "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + n + "'.", descriptor.elementForDiagnostics());
                    ownerEntity.setValid(false);
                    return;
                }
            }
        }

        // FK 제약 이름 생성 시 실제 FK가 위치하는 테이블 기준
        String fkBaseTable = joinColumns.isEmpty()
            ? ownerEntity.getTableName()
            : support.resolveJoinColumnTable(joinColumns.get(0), ownerEntity);
            
        String relationConstraintName = (explicitFkName != null) ? explicitFkName :
                context.getNaming().fkName(fkBaseTable, fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNames);

        // Validate @ForeignKey ConstraintMode consistency
        if (!support.allSameConstraintMode(joinColumns)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All @JoinColumn.foreignKey.value must be identical for composite FK on " 
                    + ownerEntity.getEntityName() + "." + descriptor.name(), descriptor.elementForDiagnostics());
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
                .mapsIdKeyPath(mapsIdAttr) // @MapsId.value() 저장
                .noConstraint(noConstraint)
                .constraintName(relationConstraintName)
                .sourceAttributeName(descriptor.name())
                .cascadeTypes(manyToOne != null ? support.toCascadeList(manyToOne.cascade()) : support.toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();

        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);
        
        // 1:1(단일 FK)이며 @MapsId가 아닌 경우 UNIQUE 제약 추가
        // OneToOne 관계는 논리적으로 항상 UNIQUE해야 하므로 @JoinColumn.unique 값과 무관하게 추가
        boolean isSingleFk = fkColumnNames.size() == 1;
        boolean shouldAddUnique = (oneToOne != null) && (mapsId == null) && isSingleFk;
        if (shouldAddUnique && !support.coveredByPkOrUnique(ownerEntity, fkBaseTable, fkColumnNames)) {
            String uqName = context.getNaming().uqName(fkBaseTable, fkColumnNames);
            if (!ownerEntity.getConstraints().containsKey(uqName)) {
                ownerEntity.getConstraints().put(uqName, ConstraintModel.builder()
                    .name(uqName).type(ConstraintType.UNIQUE)
                    .tableName(fkBaseTable).columns(new ArrayList<>(fkColumnNames)).build());
            }
        }
        
        // FK 컬럼에 자동 인덱스 생성 (성능 향상을 위해)
        support.addForeignKeyIndex(ownerEntity, fkColumnNames, fkBaseTable);
    }

}