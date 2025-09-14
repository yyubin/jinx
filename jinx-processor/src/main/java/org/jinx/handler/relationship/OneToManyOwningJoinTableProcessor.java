package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processor for @OneToMany relationships using Join Table strategy (owning side)
 */
public final class OneToManyOwningJoinTableProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    private final RelationshipJoinSupport joinSupport;
    
    public OneToManyOwningJoinTableProcessor(ProcessingContext context, RelationshipSupport support, RelationshipJoinSupport joinSupport) {
        this.context = context;
        this.support = support;
        this.joinSupport = joinSupport;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
        if (oneToMany == null || !oneToMany.mappedBy().isEmpty()) {
            return false; // Not owning side or not OneToMany
        }
        
        // Check if it has no JoinColumn(s) (defaults to join table) or explicit JoinTable
        JoinTable jt = descriptor.getAnnotation(JoinTable.class);
        JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
        JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
        boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);
        
        return !hasJoinColumn || jt != null;
    }
    
    @Override
    public void process(AttributeDescriptor attr, EntityModel ownerEntity) {
        OneToMany oneToMany = attr.getAnnotation(OneToMany.class);
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

        if (!validateExplicitJoinColumns(joinColumns, ownerPks, attr, "owner")) return;
        if (!validateExplicitJoinColumns(inverseJoinColumns, targetPks, attr, "target")) return;

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

        if (!support.allSameConstraintMode(Arrays.asList(joinColumns))) {
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

        if (!support.allSameConstraintMode(Arrays.asList(inverseJoinColumns))) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All inverse-side @JoinColumn.foreignKey.value must be identical for composite FK in join table on "
                            + ownerEntity.getEntityName() + "." + attr.name(), attr.elementForDiagnostics());
            return;
        }
        boolean inverseNoConstraint = inverseJoinColumns.length > 0 &&
                inverseJoinColumns[0].foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        Map<String,String> ownerFkToPkMap =
                resolveJoinColumnMapping(joinColumns, ownerPks, ownerEntity.getTableName(), attr, true);
        if (ownerFkToPkMap == null) return;
        Map<String,String> targetFkToPkMap =
                resolveJoinColumnMapping(inverseJoinColumns, targetPks, targetEntity.getTableName(), attr, false);
        if (targetFkToPkMap == null) return;

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

        // JoinTable 이름이 owner/referenced 엔티티 테이블명과 충돌하는지 검증
        if (!joinSupport.validateJoinTableNameConflict(joinTableName, ownerEntity, targetEntity, attr)) return;

        EntityModel existing = context.getSchemaModel().getEntities().get(joinTableName);
        if (existing != null) {
            // 조인테이블 이름 충돌 검증: 기존 엔티티가 진짜 조인테이블인지 확인
            if (existing.getTableType() != EntityModel.TableType.JOIN_TABLE) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "JoinTable name '" + joinTableName + "' conflicts with a non-join entity/table.", attr.elementForDiagnostics());
                return;
            }

            // 기존 JoinTable의 FK 컬럼셋이 일치하는지 검증 (스키마 일관성 보장)
            if (!joinSupport.validateJoinTableFkConsistency(existing, details, attr)) return;

            joinSupport.ensureJoinTableColumns(existing, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, attr);
            joinSupport.ensureJoinTableRelationships(existing, details);
            joinSupport.addOneToManyJoinTableUnique(existing, targetFkToPkMap);

            // Process @JoinTable.uniqueConstraints for existing table
            if (jt != null && jt.uniqueConstraints().length > 0) {
                joinSupport.addJoinTableUniqueConstraints(existing, jt.uniqueConstraints(), attr);
            }
            return;
        }

        Optional<EntityModel> joinTableEntityOp = joinSupport.createJoinTableEntity(details, ownerPks, targetPks);
        if (joinTableEntityOp.isEmpty()) return;
        EntityModel joinTableEntity = joinTableEntityOp.get();
        joinSupport.ensureJoinTableColumns(joinTableEntity, ownerPks, targetPks, ownerFkToPkMap, targetFkToPkMap, attr);
        joinSupport.ensureJoinTableRelationships(joinTableEntity, details);
        joinSupport.addOneToManyJoinTableUnique(joinTableEntity, targetFkToPkMap);

        // Process @JoinTable.uniqueConstraints
        if (jt != null && jt.uniqueConstraints().length > 0) {
            joinSupport.addJoinTableUniqueConstraints(joinTableEntity, jt.uniqueConstraints(), attr);
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



}