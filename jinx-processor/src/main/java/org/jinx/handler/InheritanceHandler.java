package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class InheritanceHandler {
    private final ProcessingContext context;

    public InheritanceHandler(ProcessingContext context) {
        this.context = context;
    }

    public void resolveInheritance(TypeElement typeElement, EntityModel entityModel) {
        Inheritance inheritance = typeElement.getAnnotation(Inheritance.class);
        if (inheritance == null) return;

        switch (inheritance.strategy()) {
            case SINGLE_TABLE:
                entityModel.setInheritance(InheritanceType.SINGLE_TABLE);
                DiscriminatorColumn discriminatorColumn = typeElement.getAnnotation(DiscriminatorColumn.class);
                if (discriminatorColumn != null) {
                    ColumnModel dColumn = ColumnModel.builder()
                            .columnName(discriminatorColumn.name().isEmpty() ? "dtype" : discriminatorColumn.name())
                            .javaType("java.lang.String")
                            .isPrimaryKey(false)
                            .isNullable(false)
                            .generationStrategy(GenerationStrategy.NONE)
                            .build();
                    if (entityModel.hasColumn(null, dColumn.getColumnName())) {
                        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Duplicate column name '" + dColumn.getColumnName() + "' for discriminator in entity " + entityModel.getEntityName(), typeElement);
                        entityModel.setValid(false);
                        return;
                    }
                    if (!entityModel.hasColumn(null, dColumn.getColumnName())) {
                        entityModel.putColumn(dColumn);
                    }
                }
                DiscriminatorValue discriminatorValue = typeElement.getAnnotation(DiscriminatorValue.class);
                if (discriminatorValue != null) {
                    entityModel.setDiscriminatorValue(discriminatorValue.value());
                }
                break;
            case JOINED:
                entityModel.setInheritance(InheritanceType.JOINED);
                findAndProcessJoinedChildren(entityModel, typeElement);
                break;
            case TABLE_PER_CLASS:
                entityModel.setInheritance(InheritanceType.TABLE_PER_CLASS);
                findAndProcessTablePerClassChildren(entityModel, typeElement);
                checkIdentityStrategy(typeElement, entityModel); // Check for IDENTITY strategy
                break;
        }
    }

    private void checkIdentityStrategy(TypeElement typeElement, EntityModel entityModel) {
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) enclosed;
            if (field.getAnnotation(Id.class) == null && field.getAnnotation(EmbeddedId.class) == null) {
                continue;
            }

            GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
            if (gv != null && (gv.strategy() == GenerationType.IDENTITY || gv.strategy() == GenerationType.AUTO)) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        String.format("IDENTITY generation strategy in TABLE_PER_CLASS inheritance may cause duplicate IDs. " +
                                        "Consider using SEQUENCE or TABLE strategy in entity %s, field %s",
                                entityModel.getEntityName(), field.getSimpleName()),
                        field);
            }
        }
    }

    private void findAndProcessJoinedChildren(EntityModel parentEntity, TypeElement parentType) {
        if (context.findAllPrimaryKeyColumns(parentEntity).isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Parent entity '" + parentType.getQualifiedName() + "' must define a primary key for JOINED inheritance.", parentType);
            parentEntity.setValid(false);
            return;
        }

        context.getSchemaModel().getEntities().values().stream()
                .filter(EntityModel::isJavaBackedEntity)
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName()))
                .forEach(childEntity -> {
                    String fqcn = childEntity.getFqcn();
                    TypeElement childType = (fqcn == null || fqcn.isBlank()) ? null : context.getElementUtils().getTypeElement(fqcn);
                    if (childType == null) {
                        // 타입이 없으면 스킵(원인 로깅은 선택)
                        context.getMessager().printMessage(
                                Diagnostic.Kind.NOTE,
                                "Skip inheritance child: cannot resolve TypeElement for " + fqcn);
                        return;
                    }
                    if (context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        try {
                            processSingleJoinedChild(childEntity, parentEntity, childType);
                            checkIdentityStrategy(childType, childEntity);
                        } catch (IllegalStateException ex) {
                            // 이미 ERROR/NOTE 로깅했다면 여기선 삼키고 진행
                            childEntity.setValid(false);
                        }
                    }
                });
    }

    public record JoinPair(ColumnModel parent, String childName) {}
    
    /**
     * 타입명을 정규화하여 비교할 수 있도록 합니다.
     * 예: "java.lang.Long" -> "Long", 박스됨 타입 처리 등
     */
    private String normalizeType(String javaType) {
        if (javaType == null) return null;
        
        // 기본 타입 정규화
        return switch (javaType) {
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Byte" -> "byte";
            case "java.lang.Short" -> "short";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            case "java.lang.Character" -> "char";
            default -> {
                // 패키지명 제거
                int lastDot = javaType.lastIndexOf('.');
                yield lastDot >= 0 ? javaType.substring(lastDot + 1) : javaType;
            }
        };
    }

    private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, TypeElement childType) {
        List<ColumnModel> parentPkCols = context.findAllPrimaryKeyColumns(parentEntity);
        if (parentPkCols.isEmpty()) {
            childEntity.setValid(false);
            return;
        }

        List<JoinPair> joinPairs = resolvePrimaryKeyJoinPairs(childType, parentPkCols);

        // 1) 검증 단계: 기존 컬럼과 타입/PK/nullable 조건을 모두 점검하고 추가될 컬럼은 pending 목록에만 만든다.
        List<ColumnModel> pendingAdds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String childTable = childEntity.getTableName();
        
        for (JoinPair jp : joinPairs) {
            ColumnModel parentPk = jp.parent();
            String childCol = jp.childName();

            ColumnModel existing = childEntity.findColumn(null, childCol);
            if (existing != null) {
                String wantType = parentPk.getJavaType();
                String haveType = existing.getJavaType();
                
                boolean typeMismatch = !normalizeType(haveType).equals(normalizeType(wantType));
                boolean pkMismatch = !existing.isPrimaryKey();
                boolean nullMismatch = existing.isNullable();
                
                if (typeMismatch || pkMismatch || nullMismatch) {
                    errors.add(
                        "JOINED column mismatch: child='" + childEntity.getEntityName() + "', column='" + childCol +
                        "' expected{type=" + wantType + ", pk=true, nullable=false} " +
                        "actual{type=" + haveType + ", pk=" + existing.isPrimaryKey() + ", nullable=" + existing.isNullable() + "}"
                    );
                }
                continue;
            }
            
            ColumnModel add = ColumnModel.builder()
                    .columnName(childCol)
                    .tableName(childTable)
                    .javaType(parentPk.getJavaType())
                    .length(parentPk.getLength())
                    .precision(parentPk.getPrecision())
                    .scale(parentPk.getScale())
                    .defaultValue(parentPk.getDefaultValue())
                    .comment(parentPk.getComment())
                    .isPrimaryKey(true)
                    .isNullable(false)
                    .build();
            
            pendingAdds.add(add);
        }
        
        // 2) 커밋 단계: 오류가 없을 때만 실제 childEntity에 put
        if (!errors.isEmpty()) {
            errors.forEach(msg -> context.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, childType));
            childEntity.setValid(false);
            return;
        }
        
        pendingAdds.forEach(childEntity::putColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(RelationshipType.JOINED_INHERITANCE)
                .tableName(childEntity.getTableName())
                .columns(joinPairs.stream().map(JoinPair::childName).toList())
                .referencedTable(parentEntity.getTableName())
                .referencedColumns(joinPairs.stream().map(j -> j.parent().getColumnName()).toList())
                .constraintName(context.getNaming().fkName(
                        childEntity.getTableName(),
                        joinPairs.stream().map(JoinPair::childName).toList(),
                        parentEntity.getTableName(),
                        joinPairs.stream().map(j -> j.parent().getColumnName()).toList()))
                .build();

        String fkName = relationship.getConstraintName();
        if (fkName == null || fkName.isBlank()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "JOINED inheritance FK constraint name is null/blank for child " + childEntity.getEntityName());
            childEntity.setValid(false);
            return;
        }
        String n = fkName.trim().toLowerCase(Locale.ROOT);
        boolean dup = childEntity.getRelationships().keySet().stream()
            .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
            .anyMatch(n::equals);
        if (dup) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Duplicate relationship constraint name: " + fkName, childType);
            childEntity.setValid(false);
            return;
        }

        childEntity.getRelationships().put(relationship.getConstraintName(), relationship);
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(InheritanceType.JOINED);
    }

    private List<JoinPair> resolvePrimaryKeyJoinPairs(TypeElement childType, List<ColumnModel> parentPkCols) {
        List<PrimaryKeyJoinColumn> annotations = collectPrimaryKeyJoinColumns(childType);
        if (annotations.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    String.format("Jinx is creating a default foreign key for the JOINED inheritance of entity '%s'. " +
                        "To disable this constraint, you must explicitly use @PrimaryKeyJoinColumn along with @JoinColumn and @ForeignKey(ConstraintMode.NO_CONSTRAINT).",
                        childType.getQualifiedName()),
                    childType);
            return parentPkCols.stream()
                    .map(col -> new JoinPair(col, col.getColumnName()))
                    .toList();
        }

        // 개수 검증
        if (annotations.size() != parentPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    String.format("JOINED inheritance PK mapping mismatch in %s: expected %d columns, but got %d",
                            childType.getQualifiedName(), parentPkCols.size(), annotations.size()));
            throw new IllegalStateException("PK mapping size mismatch");
        }

        List<JoinPair> result = new ArrayList<>();
        try {
            for (int i = 0; i < annotations.size(); i++) {
                PrimaryKeyJoinColumn anno = annotations.get(i);
                ColumnModel parentRef = resolveParentReference(parentPkCols, anno, i);
                String childName = anno.name().isEmpty() ? parentRef.getColumnName() : anno.name();
                result.add(new JoinPair(parentRef, childName));
            }
        } catch (IllegalStateException ex) {
            context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "JOINED inheritance PK mapping error context (" + childType.getQualifiedName() + "): " + ex.getMessage(), childType);
            throw ex; // Preserve behavior; avoid duplicate ERROR logs
        }
        return result;
    }

    private List<PrimaryKeyJoinColumn> collectPrimaryKeyJoinColumns(TypeElement childType) {
        List<PrimaryKeyJoinColumn> result = new ArrayList<>();

        PrimaryKeyJoinColumns multi = childType.getAnnotation(PrimaryKeyJoinColumns.class);
        if (multi != null && multi.value().length > 0) {
            return Arrays.asList(multi.value());
        }

        PrimaryKeyJoinColumn single = childType.getAnnotation(PrimaryKeyJoinColumn.class);
        if (single != null) {
            result.add(single);
        }

        return result;
    }

    private ColumnModel resolveParentReference(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn anno, int index) {
        if (!anno.referencedColumnName().trim().isEmpty()) {
            return parentPkCols.stream()
                    .filter(col -> col.getColumnName().equals(anno.referencedColumnName()))
                    .findFirst()
                    .orElseThrow(() -> {
                        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Referenced column '" + anno.referencedColumnName() + "' not found in parent primary keys");
                        return new IllegalStateException("Invalid referencedColumnName: " + anno.referencedColumnName());
                    });
        }
        return parentPkCols.get(index);
    }

    private void findAndProcessTablePerClassChildren(EntityModel parentEntity, TypeElement parentType) {
        context.getSchemaModel().getEntities().values().stream()
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName()))
                .forEach(childEntity -> {
                    TypeElement childType = context.getElementUtils().getTypeElement(childEntity.getEntityName());
                    if (childType != null && context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        processSingleTablePerClassChild(childEntity, parentEntity);
                        checkIdentityStrategy(childType, childEntity); // Check children too
                    }
                });
    }

    private static <T> List<T> safeList(List<T> src) {
        return (src == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(src);
    }

    private void processSingleTablePerClassChild(EntityModel childEntity, EntityModel parentEntity) {
        childEntity.setParentEntity(parentEntity.getEntityName());
        childEntity.setInheritance(InheritanceType.TABLE_PER_CLASS);

        parentEntity.getColumns().values().forEach(column -> {
            if (!childEntity.hasColumn(null, column.getColumnName())) {
                ColumnModel copiedColumn = ColumnModel.builder()
                        .tableName(childEntity.getTableName())
                        .columnName(column.getColumnName())
                        .javaType(column.getJavaType())
                        .isPrimaryKey(column.isPrimaryKey())
                        .isNullable(column.isNullable())
                        .isUnique(column.isUnique())
                        .length(column.getLength())
                        .precision(column.getPrecision())
                        .scale(column.getScale())
                        .defaultValue(column.getDefaultValue())
                        .generationStrategy(column.getGenerationStrategy())
                        .sequenceName(column.getSequenceName())
                        .tableGeneratorName(column.getTableGeneratorName())
                        .isLob(column.isLob())
                        .jdbcType(column.getJdbcType())
                        .fetchType(column.getFetchType())
                        .isOptional(column.isOptional())
                        .isVersion(column.isVersion())
                        .conversionClass(column.getConversionClass())
                        .temporalType(column.getTemporalType())
                        .build();
                childEntity.putColumn(copiedColumn);
            }
        });

        parentEntity.getConstraints().values().forEach(constraint -> {
            ConstraintModel copied = ConstraintModel.builder()
                    .name(constraint.getName())
                    .type(constraint.getType())
                    .columns(safeList(constraint.getColumns()))
                    .referencedTable(constraint.getReferencedTable())
                    .referencedColumns(safeList(constraint.getReferencedColumns()))
                    .build();

            childEntity.getConstraints().put(copied.getName(), copied);
        });
    }
}