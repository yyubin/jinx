package org.jinx.handler.relationship;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processor for @ManyToMany relationships (owning side)
 */
public final class ManyToManyOwningProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    private final RelationshipJoinSupport joinSupport;
    
    public ManyToManyOwningProcessor(ProcessingContext context, RelationshipSupport support, RelationshipJoinSupport joinSupport) {
        this.context = context;
        this.support = support;
        this.joinSupport = joinSupport;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
        return manyToMany != null && manyToMany.mappedBy().isEmpty();
    }
    
    @Override
    public void process(AttributeDescriptor attr, EntityModel ownerEntity) {
        ManyToMany manyToMany = attr.getAnnotation(ManyToMany.class);
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

        Optional<TypeElement> referencedTypeElementOpt = support.resolveTargetEntity(attr, null, null, null, manyToMany);
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
        if (!support.allSameConstraintMode(Arrays.asList(joinColumns))) {
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
        if (!support.allSameConstraintMode(Arrays.asList(inverseJoinColumns))) {
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

        boolean okOwner = validateExplicitJoinColumns(joinColumns, ownerPks, attr, "owner");
        boolean okInverse = validateExplicitJoinColumns(inverseJoinColumns, referencedPks, attr, "target");
        if (!(okOwner && okInverse)) return;

        Map<String, String> ownerFkToPkMap;
        Map<String, String> inverseFkToPkMap;

        ownerFkToPkMap = resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), attr, true);
        if (ownerFkToPkMap == null) return;
        inverseFkToPkMap = resolveJoinColumnMapping(inverseJoinColumns, referencedPks, referencedEntity.getTableName(), attr, false);
        if (inverseFkToPkMap == null) return;

        Set<String> overlap = new HashSet<>(ownerFkToPkMap.keySet());
        overlap.retainAll(inverseFkToPkMap.keySet());
        if (!overlap.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable foreign key name collision across sides: " + overlap + " (owner vs target).", attr.elementForDiagnostics());
            return;
        }

        JoinTableDetails details = new JoinTableDetails(joinTableName, ownerFkToPkMap, inverseFkToPkMap,
                ownerEntity, referencedEntity, ownerFkConstraint, inverseFkConstraint, ownerNoConstraint, inverseNoConstraint);

        // JoinTable 이름이 owner/referenced 엔티티 테이블명과 충돌하는지 검증
        try {
            joinSupport.validateJoinTableNameConflict(joinTableName, ownerEntity, referencedEntity, attr);
        } catch (IllegalStateException ex) {
            return;
        }

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            // 조인테이블 이름 충돌 검증: 기존 엔티티가 진짜 조인테이블인지 확인
            if (existing.getTableType() != EntityModel.TableType.JOIN_TABLE) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "JoinTable name '" + joinTableName + "' conflicts with a non-join entity/table.", attr.elementForDiagnostics());
                return;
            }

            // 기존 JoinTable의 FK 컬럼셋이 일치하는지 검증 (스키마 일관성 보장)
            try {
                joinSupport.validateJoinTableFkConsistency(existing, details, attr);
            } catch (IllegalStateException ex) {
                return;
            }

            joinSupport.ensureJoinTableColumns(existing, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, attr);
            joinSupport.ensureJoinTableRelationships(existing, details);
            addManyToManyPkConstraint(existing, ownerFkToPkMap, inverseFkToPkMap);

            // Process @JoinTable.uniqueConstraints for existing table
            if (joinTable != null && joinTable.uniqueConstraints().length > 0) {
                joinSupport.addJoinTableUniqueConstraints(existing, joinTable.uniqueConstraints(), attr);
            }
            return;
        }

        Optional<EntityModel> joinTableEntityOp = joinSupport.createJoinTableEntity(details, ownerPks, referencedPks);
        if (joinTableEntityOp.isEmpty()) return;
        EntityModel joinTableEntity = joinTableEntityOp.get();
        joinSupport.ensureJoinTableColumns(joinTableEntity, ownerPks, referencedPks, ownerFkToPkMap, inverseFkToPkMap, attr);
        joinSupport.ensureJoinTableRelationships(joinTableEntity, details);

        // N:N semantics: composite PK(owner_fk + target_fk)
        addManyToManyPkConstraint(joinTableEntity, ownerFkToPkMap, inverseFkToPkMap);

        // Process @JoinTable.uniqueConstraints
        if (joinTable != null && joinTable.uniqueConstraints() != null && joinTable.uniqueConstraints().length > 0) {
            joinSupport.addJoinTableUniqueConstraints(joinTableEntity, joinTable.uniqueConstraints(), attr);
        }

        context.getSchemaModel().getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private boolean validateExplicitJoinColumns(JoinColumn[] joinColumns, List<ColumnModel> pks,
                                                AttributeDescriptor attr, String sideLabel) {
        if (pks.size() > 1 && joinColumns.length > 0) {
            for (int i = 0; i < joinColumns.length; i++) {
                JoinColumn jc = joinColumns[i];
                if (jc.referencedColumnName() == null || jc.referencedColumnName().isEmpty()) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Composite primary key requires explicit referencedColumnName for all @" + sideLabel
                                    + " JoinColumns (index " + i + ", pkCount = " + pks.size() + ", joinColumnsCount = " + joinColumns.length  + ").", attr.elementForDiagnostics());
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, String> resolveJoinColumnMapping(JoinColumn[] joinColumns,
                                                         List<ColumnModel> referencedPks,
                                                         String entityTableName,
                                                         AttributeDescriptor attr,
                                                         boolean isOwnerSide) {
        String side = isOwnerSide ? "owner" : "target";
        Map<String, String> mapping = new LinkedHashMap<>();
        if (joinColumns == null || joinColumns.length == 0) {
            for (ColumnModel pk : referencedPks) {
                // 조인테이블의 FK 네이밍: 참조 테이블명 기반 (entityTableName + referencedPK)
                String fk = context.getNaming().foreignKeyColumnName(entityTableName, pk.getColumnName());
                if (mapping.containsKey(fk)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fk + "' in join table mapping for " + entityTableName + " (side=" + side + ")", attr.elementForDiagnostics());
                    return null;
                }
                mapping.put(fk, pk.getColumnName());
            }
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
                    return null;
                }
                // 조인테이블의 FK 네이밍: 참조 테이블명 기반 (entityTableName + referencedPK)
                String fkName = jc.name().isEmpty()
                        ? context.getNaming().foreignKeyColumnName(entityTableName, pkName)
                        : jc.name();
                if (mapping.containsKey(fkName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate foreign key column '" + fkName + "' in join table mapping for " + entityTableName, attr.elementForDiagnostics());
                    return null;
                }
                mapping.put(fkName, pkName);
            }
        }
        return mapping;
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
}