package org.jinx.handler.relationship;

import jakarta.persistence.UniqueConstraint;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.tools.Diagnostic;
import java.util.*;

public final class RelationshipJoinSupport {
    private final ProcessingContext context;
    private final RelationshipSupport support;

    public RelationshipJoinSupport(ProcessingContext context, RelationshipSupport support) {
        this.context = context;
        this.support = support;
    }

    public void ensureJoinTableRelationships(EntityModel jt, JoinTableDetails details) {
        List<Map.Entry<String, String>> ownerPairs = new ArrayList<>(details.ownerFkToPkMap().entrySet());
        List<String> ownerFks = ownerPairs.stream().map(Map.Entry::getKey).toList();
        List<String> ownerPks = ownerPairs.stream().map(Map.Entry::getValue).toList();
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
            support.addForeignKeyIndex(jt, ownerFks, details.joinTableName());
        }

        List<Map.Entry<String, String>> targetPairs = new ArrayList<>(details.inverseFkToPkMap().entrySet());
        List<String> targetFks = targetPairs.stream().map(Map.Entry::getKey).toList();
        List<String> targetPks = targetPairs.stream().map(Map.Entry::getValue).toList();
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
            support.addForeignKeyIndex(jt, targetFks, details.joinTableName());
        }
    }

    public void addOneToManyJoinTableUnique(EntityModel joinTableEntity,
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
    public void addJoinTableUniqueConstraints(EntityModel joinTableEntity,
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

    public EntityModel createJoinTableEntity(JoinTableDetails details, List<ColumnModel> ownerPks, List<ColumnModel> referencedPks) {
        EntityModel joinTableEntity = EntityModel.builder()
                .entityName(details.joinTableName())
                .tableName(details.joinTableName())
                .tableType(EntityModel.TableType.JOIN_TABLE)
                .build();

        // Process owner-side FK columns with error handling
        // nullability 규칙: JoinTable의 FK는 항상 NOT NULL (관계 무결성 보장)
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

    public void addRelationshipsToJoinTable(EntityModel joinTableEntity, JoinTableDetails details) {
        List<Map.Entry<String, String>> ownerPairs = new ArrayList<>(details.ownerFkToPkMap().entrySet());
        List<String> ownerFkColumns = ownerPairs.stream().map(Map.Entry::getKey).toList();
        List<String> ownerPkColumns = ownerPairs.stream().map(Map.Entry::getValue).toList();

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
        support.addForeignKeyIndex(joinTableEntity, ownerFkColumns, details.joinTableName());

        List<Map.Entry<String, String>> targetPairs = new ArrayList<>(details.inverseFkToPkMap().entrySet());
        List<String> targetFkColumns = targetPairs.stream().map(Map.Entry::getKey).toList();
        List<String> targetPkColumns = targetPairs.stream().map(Map.Entry::getValue).toList();

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
        support.addForeignKeyIndex(joinTableEntity, targetFkColumns, details.joinTableName());
    }

    public void ensureJoinTableColumns(
            EntityModel jt,
            List<ColumnModel> ownerPks, List<ColumnModel> targetPks,
            Map<String,String> ownerFkToPkMap, Map<String,String> targetFkToPkMap,
            AttributeDescriptor attr) {

        Map<String, ColumnModel> pkTypeLookup = new HashMap<>();
        ownerPks.forEach(pk -> pkTypeLookup.put("owner::" + pk.getColumnName(), pk));
        targetPks.forEach(pk -> pkTypeLookup.put("target::" + pk.getColumnName(), pk));

        for (Map.Entry<String,String> e : ownerFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            String pkName = e.getValue();
            ColumnModel pk = pkTypeLookup.get("owner::" + pkName);
            if (pk == null) {
                context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Unknown owner PK '" + pkName + "' mapped from FK '" + fkName +
                    "' on join table '" + jt.getTableName() + "'. Known owner PKs: " +
                    pkTypeLookup.keySet().stream().filter(k -> k.startsWith("owner::")).toList(),
                    attr.elementForDiagnostics()
                );
                jt.setValid(false);
                return;
            }
            if (pk.getJavaType() == null) {
                context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Owner PK '" + pkName + "' has no Java type; cannot create FK '" + fkName +
                    "' on join table '" + jt.getTableName() + "'.",
                    attr.elementForDiagnostics()
                );
                jt.setValid(false);
                return;
            }
            ensureOneColumn(jt, fkName, pk.getJavaType(), jt.getTableName(), attr);
        }

        for (Map.Entry<String,String> e : targetFkToPkMap.entrySet()) {
            String fkName = e.getKey();
            String pkName = e.getValue();
            ColumnModel pk = pkTypeLookup.get("target::" + pkName);
            if (pk == null) {
                context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Unknown target PK '" + pkName + "' mapped from FK '" + fkName +
                    "' on join table '" + jt.getTableName() + "'. Known target PKs: " +
                    pkTypeLookup.keySet().stream().filter(k -> k.startsWith("target::")).toList(),
                    attr.elementForDiagnostics()
                );
                jt.setValid(false);
                return;
            }
            if (pk.getJavaType() == null) {
                context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Target PK '" + pkName + "' has no Java type; cannot create FK '" + fkName +
                    "' on join table '" + jt.getTableName() + "'.",
                    attr.elementForDiagnostics()
                );
                jt.setValid(false);
                return;
            }
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
     * JoinTable 이름이 owner/referenced 엔티티 테이블명과 충돌하는지 검증
     */
    public void validateJoinTableNameConflict(String joinTableName, EntityModel ownerEntity, EntityModel referencedEntity, AttributeDescriptor attr) {
        // JoinTable 이름이 owner 테이블명과 동일한지 검증
        if (joinTableName.equals(ownerEntity.getTableName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable name '" + joinTableName + "' conflicts with owner entity table name '" + ownerEntity.getTableName() + "'.", 
                    attr.elementForDiagnostics());
            throw new IllegalStateException("JoinTable name conflicts with owner table");
        }
        
        // JoinTable 이름이 referenced 테이블명과 동일한지 검증
        if (joinTableName.equals(referencedEntity.getTableName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JoinTable name '" + joinTableName + "' conflicts with referenced entity table name '" + referencedEntity.getTableName() + "'.", 
                    attr.elementForDiagnostics());
            throw new IllegalStateException("JoinTable name conflicts with referenced table");
        }
    }

    /**
     * 기존 JoinTable과 FK 컬럼셋이 일치하는지 검증 (스키마 일관성 보장)
     */
    public void validateJoinTableFkConsistency(EntityModel existingJoinTable, JoinTableDetails details, AttributeDescriptor attr) {
        // 기존 JoinTable의 컬럼들과 새로 요구되는 FK 컬럼들이 정확히 일치하는지 검증
        Set<String> existingColumns = existingJoinTable.getColumns().keySet();
        Set<String> requiredColumns = new HashSet<>();
        requiredColumns.addAll(details.ownerFkToPkMap().keySet());
        requiredColumns.addAll(details.inverseFkToPkMap().keySet());
        
        if (!existingColumns.equals(requiredColumns)) {
            Set<String> missingColumns = new HashSet<>(requiredColumns);
            missingColumns.removeAll(existingColumns);
            
            Set<String> extraColumns = new HashSet<>(existingColumns);
            extraColumns.removeAll(requiredColumns);
            
            StringBuilder error = new StringBuilder();
            error.append("JoinTable '").append(details.joinTableName()).append("' FK column set mismatch. ");
            
            if (!missingColumns.isEmpty()) {
                error.append("Missing columns: ").append(missingColumns).append(". ");
            }
            if (!extraColumns.isEmpty()) {
                error.append("Extra columns: ").append(extraColumns).append(". ");
            }
            
            error.append("Expected columns: ").append(requiredColumns).append(", ");
            error.append("Found columns: ").append(existingColumns).append(".");
            
            context.getMessager().printMessage(Diagnostic.Kind.ERROR, error.toString(), attr.elementForDiagnostics());
            throw new IllegalStateException("JoinTable FK column set mismatch");
        }
    }
}
