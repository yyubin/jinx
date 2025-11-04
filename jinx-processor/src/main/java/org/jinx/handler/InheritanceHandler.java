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

/**
 * Handles JPA inheritance strategies (@Inheritance).
 *
 * <p>This handler is responsible for:
 * <ul>
 *   <li>Resolving inheritance hierarchies for SINGLE_TABLE, JOINED, and TABLE_PER_CLASS strategies.</li>
 *   <li>Processing @DiscriminatorColumn and @DiscriminatorValue for SINGLE_TABLE and JOINED strategies.</li>
 *   <li>Creating foreign key relationships for JOINED inheritance.</li>
 *   <li>Copying columns and constraints for TABLE_PER_CLASS inheritance.</li>
 *   <li>Validating inheritance-related configurations.</li>
 * </ul>
 */
public class InheritanceHandler {
    private final ProcessingContext context;

    public InheritanceHandler(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Resolves the inheritance strategy for a given entity.
     *
     * @param typeElement The TypeElement of the entity.
     * @param entityModel The EntityModel representing the entity.
     */
    public void resolveInheritance(TypeElement typeElement, EntityModel entityModel) {
        Inheritance inheritance = typeElement.getAnnotation(Inheritance.class);
        if (inheritance == null) return;

        switch (inheritance.strategy()) {
            case SINGLE_TABLE:
                entityModel.setInheritance(InheritanceType.SINGLE_TABLE);
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
        handleDiscriminator(typeElement, entityModel, inheritance.strategy());
    }

    /**
     * Handles the @DiscriminatorColumn for SINGLE_TABLE and JOINED strategies.
     *
     * @param typeElement The TypeElement of the entity.
     * @param entityModel The EntityModel representing the entity.
     * @param strategy The inheritance strategy.
     */
    private void handleDiscriminator(TypeElement typeElement, EntityModel entityModel, InheritanceType strategy) {
        DiscriminatorColumn dc = typeElement.getAnnotation(DiscriminatorColumn.class);

        boolean isSingleTable = strategy == InheritanceType.SINGLE_TABLE;
        boolean isJoined = strategy == InheritanceType.JOINED;

        // For SINGLE_TABLE, a discriminator is created by default if not present. For JOINED, it's created only if specified.
        if (dc == null && !isSingleTable) return;

        String rawName = (dc != null && !dc.name().isEmpty()) ? dc.name() : "DTYPE";
        String name = rawName.trim();

        DiscriminatorType dtype = (dc != null) ? dc.discriminatorType() : DiscriminatorType.STRING;
        int len = (dc != null) ? dc.length() : 31;

        String columnDef = (dc != null) ? dc.columnDefinition() : "";
        String options = (dc != null) ? dc.options() : "";

        List<String> errors = new ArrayList<>();

        // 1) columnDefinition and options are mutually exclusive.
        if (!columnDef.isBlank() && !options.isBlank()) {
            errors.add("@DiscriminatorColumn: columnDefinition and options cannot be used together");
        }

        // 2) Check for duplicate column names.
        if (entityModel.hasColumn(null, name)) {
            errors.add("Duplicate column name '" + name + "' for discriminator in entity " + entityModel.getEntityName());
        }

        // 3) Validate length and type.
        if ((dtype == DiscriminatorType.STRING || dtype == DiscriminatorType.CHAR) && len <= 0) {
            errors.add("Invalid discriminator length: " + len + " (must be > 0)");
        }

        boolean needCharAdjust = false;
        if (dtype == DiscriminatorType.CHAR && len != 1) {
            // Warn and auto-correct.
            needCharAdjust = true;
            len = 1;
        }

        if (!errors.isEmpty()) {
            for (String e : errors) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR, e, typeElement);
            }
            entityModel.setValid(false);
            return;
        }

        if (needCharAdjust) {
            context.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@DiscriminatorColumn(length) adjusted to 1 for CHAR type",
                    typeElement
            );
        }

        // Type mapping.
        String javaType = switch (dtype) {
            case STRING, CHAR -> "java.lang.String";
            case INTEGER -> "java.lang.Integer";
        };
        ColumnModel.ColumnModelBuilder colb = ColumnModel.builder()
                .tableName(entityModel.getTableName())
                .columnName(name)
                .javaType(javaType)
                .isPrimaryKey(false)
                .isNullable(false)
                .generationStrategy(GenerationStrategy.NONE)
                .columnKind(ColumnModel.ColumnKind.DISCRIMINATOR)
                .discriminatorType(dtype)
                .columnDefinition(columnDef.isBlank() ? null : columnDef)
                .options(options.isBlank() ? null : options);

        if (dtype == DiscriminatorType.STRING || dtype == DiscriminatorType.CHAR) {
            colb.length(len);
        }
        ColumnModel col = colb.build();
        entityModel.putColumn(col);
    }

    /**
     * Checks for the use of IDENTITY generation strategy with TABLE_PER_CLASS inheritance, which can be problematic.
     *
     * @param typeElement The TypeElement of the entity.
     * @param entityModel The EntityModel representing the entity.
     */
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

    /**
     * Finds and processes all child entities for a parent using JOINED inheritance.
     *
     * @param parentEntity The parent entity model.
     * @param parentType The parent entity's TypeElement.
     */
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
                        // Skip if type cannot be resolved (logging the reason is optional).
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
                            // If already logged as ERROR/NOTE, swallow here to proceed.
                            childEntity.setValid(false);
                        }
                    }
                });
    }

    public record JoinPair(ColumnModel parent, String childName) {}
    
    /**
     * Normalizes a Java type name for comparison.
     * e.g., "java.lang.Long" -> "long", handles boxed types.
     * @param javaType The Java type to normalize.
     * @return The normalized type name.
     */
    private String normalizeType(String javaType) {
        if (javaType == null) return null;
        String jt = javaType.trim();
        // Normalize primitive wrapper types.
        return switch (jt) {
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Byte" -> "byte";
            case "java.lang.Short" -> "short";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            case "java.lang.Character" -> "char";
            default -> {
                // Remove package name.
                int lastDot = javaType.lastIndexOf('.');
                yield lastDot >= 0 ? javaType.substring(lastDot + 1) : javaType;
            }
        };
    }

    /**
     * Processes a single child entity in a JOINED inheritance hierarchy.
     *
     * @param childEntity The child entity model.
     * @param parentEntity The parent entity model.
     * @param childType The child entity's TypeElement.
     */
    private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, TypeElement childType) {
        List<ColumnModel> parentPkCols = context.findAllPrimaryKeyColumns(parentEntity);
        if (parentPkCols.isEmpty()) {
            childEntity.setValid(false);
            return;
        }

        List<JoinPair> joinPairs = resolvePrimaryKeyJoinPairs(childType, parentPkCols);
        if (joinPairs == null) {
            childEntity.setValid(false);
            return;
        }

        // 1) Validation phase: Check existing columns for type/PK/nullable compatibility and create a pending list for new columns.
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

        // 2) Commit phase: Only add columns to the child entity if no errors occurred.
        if (!errors.isEmpty()) {
            errors.forEach(msg -> context.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, childType));
            childEntity.setValid(false);
            return;
        }

        pendingAdds.forEach(childEntity::putColumn);

        // Process @ForeignKey annotation.
        ForeignKeyInfo fkInfo = extractForeignKeyInfo(childType);

        // Determine FK constraint name.
        String constraintName;
        if (fkInfo.explicitName != null && !fkInfo.explicitName.isEmpty()) {
            // Use explicitly specified name.
            constraintName = fkInfo.explicitName;
        } else {
            // Auto-generate (use JOINED inheritance-specific naming to avoid conflicts).
            constraintName = context.getNaming().fkName(
                    childEntity.getTableName(),
                    joinPairs.stream().map(JoinPair::childName).toList(),
                    parentEntity.getTableName(),
                    joinPairs.stream().map(j -> j.parent().getColumnName()).toList());

            // Avoid duplicates: add a suffix if the constraint name conflicts with an existing one.
            constraintName = ensureUniqueConstraintName(childEntity, constraintName);
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(RelationshipType.JOINED_INHERITANCE)
                .tableName(childEntity.getTableName())
                .columns(joinPairs.stream().map(JoinPair::childName).toList())
                .referencedTable(parentEntity.getTableName())
                .referencedColumns(joinPairs.stream().map(j -> j.parent().getColumnName()).toList())
                .constraintName(constraintName)
                .noConstraint(fkInfo.noConstraint)
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

    /**
     * Ensures a unique constraint name by adding a suffix (_1, _2, etc.) if a conflict is found.
     *
     * @param entity The entity model.
     * @param baseName The base name for the constraint.
     * @return A unique constraint name.
     */
    private String ensureUniqueConstraintName(EntityModel entity, String baseName) {
        String candidate = baseName;
        int suffix = 1;

        while (isConstraintNameUsed(entity, candidate)) {
            candidate = baseName + "_" + suffix;
            suffix++;
            if (suffix > 100) {
                // Prevent infinite loops.
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Too many constraint name collisions for base name: " + baseName);
                break;
            }
        }

        return candidate;
    }

    /**
     * Checks if a constraint name is already in use within an entity.
     *
     * @param entity The entity model.
     * @param name The constraint name to check.
     * @return True if the name is used, false otherwise.
     */
    private boolean isConstraintNameUsed(EntityModel entity, String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return entity.getRelationships().keySet().stream()
            .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
            .anyMatch(normalized::equals);
    }

    /**
     * Extracts @ForeignKey information from @PrimaryKeyJoinColumn annotations.
     *
     * @param childType The child entity's TypeElement.
     * @return A ForeignKeyInfo object containing the extracted information.
     */
    private ForeignKeyInfo extractForeignKeyInfo(TypeElement childType) {
        ForeignKeyInfo info = new ForeignKeyInfo();

        // Check for @PrimaryKeyJoinColumns.
        PrimaryKeyJoinColumns multiAnno = childType.getAnnotation(PrimaryKeyJoinColumns.class);
        if (multiAnno != null && multiAnno.value().length > 0) {
            for (PrimaryKeyJoinColumn pkjc : multiAnno.value()) {
                processPrimaryKeyJoinColumnForeignKey(pkjc, info, childType);
            }
            return info;
        }

        // Check for @PrimaryKeyJoinColumn.
        PrimaryKeyJoinColumn singleAnno = childType.getAnnotation(PrimaryKeyJoinColumn.class);
        if (singleAnno != null) {
            processPrimaryKeyJoinColumnForeignKey(singleAnno, info, childType);
        }

        return info;
    }

    /**
     * Processes the foreignKey attribute of a @PrimaryKeyJoinColumn.
     *
     * @param pkjc The @PrimaryKeyJoinColumn annotation.
     * @param info The ForeignKeyInfo object to populate.
     * @param childType The child entity's TypeElement for error reporting.
     */
    private void processPrimaryKeyJoinColumnForeignKey(PrimaryKeyJoinColumn pkjc, ForeignKeyInfo info, TypeElement childType) {
        ForeignKey fk = pkjc.foreignKey();
        if (fk == null) return;

        // Check for ConstraintMode.NO_CONSTRAINT.
        if (fk.value() == ConstraintMode.NO_CONSTRAINT) {
            info.noConstraint = true;
        }

        // Check for an explicit name.
        String name = fk.name();
        if (name != null && !name.isEmpty()) {
            if (info.explicitName == null) {
                info.explicitName = name;
            } else if (!info.explicitName.equals(name)) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "All @PrimaryKeyJoinColumn.foreignKey names must be identical. Found: '" +
                    info.explicitName + "' and '" + name + "'.", childType);
            }
        }
    }

    /**
     * Internal class to hold @ForeignKey information.
     */
    private static class ForeignKeyInfo {
        boolean noConstraint = false;
        String explicitName = null;
    }

    /**
     * Resolves the join pairs for a JOINED inheritance relationship based on @PrimaryKeyJoinColumn annotations.
     *
     * @param childType The child entity's TypeElement.
     * @param parentPkCols The list of primary key columns from the parent entity.
     * @return A list of JoinPair objects, or null if there is a configuration error.
     */
    private List<JoinPair> resolvePrimaryKeyJoinPairs(TypeElement childType, List<ColumnModel> parentPkCols) {
        List<PrimaryKeyJoinColumn> annotations = collectPrimaryKeyJoinColumns(childType);
        if (annotations.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    String.format("Jinx is creating a default foreign key for the JOINED inheritance of entity '%s'. " +
                        "To disable this constraint, use @PrimaryKeyJoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)).",
                        childType.getQualifiedName()),
                    childType);
            return parentPkCols.stream()
                    .map(col -> new JoinPair(col, col.getColumnName()))
                    .toList();
        }

        // Validate annotation count.
        if (annotations.size() != parentPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    String.format("JOINED inheritance PK mapping mismatch in %s: expected %d columns, but got %d",
                            childType.getQualifiedName(), parentPkCols.size(), annotations.size()));
            return null;
        }

        List<JoinPair> result = new ArrayList<>();
        try {
            for (int i = 0; i < annotations.size(); i++) {
                PrimaryKeyJoinColumn anno = annotations.get(i);
                ColumnModel parentRef = resolveParentReference(parentPkCols, anno, i);
                if (parentRef == null) {
                    return null;
                }
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

    /**
     * Collects all @PrimaryKeyJoinColumn annotations from a TypeElement, handling both single and repeated annotations.
     *
     * @param childType The TypeElement to inspect.
     * @return A list of @PrimaryKeyJoinColumn annotations.
     */
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

    /**
     * Resolves the parent primary key column referenced by a @PrimaryKeyJoinColumn annotation.
     *
     * @param parentPkCols The list of parent primary key columns.
     * @param anno The @PrimaryKeyJoinColumn annotation.
     * @param index The index of the annotation, used for positional matching.
     * @return The referenced parent ColumnModel, or null if not found.
     */
    private ColumnModel resolveParentReference(List<ColumnModel> parentPkCols, PrimaryKeyJoinColumn anno, int index) {
        if (!anno.referencedColumnName().trim().isEmpty()) {
            Optional<ColumnModel> found = parentPkCols.stream()
                    .filter(col -> col.getColumnName().equals(anno.referencedColumnName()))
                    .findFirst();
            if (found.isEmpty()) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Referenced column '" + anno.referencedColumnName() + "' not found in parent primary keys");
                return null;
            }
            return found.get();
        }
        return parentPkCols.get(index);
    }

    /**
     * Finds and processes all child entities for a parent using TABLE_PER_CLASS inheritance.
     *
     * @param parentEntity The parent entity model.
     * @param parentType The parent entity's TypeElement.
     */
    private void findAndProcessTablePerClassChildren(EntityModel parentEntity, TypeElement parentType) {
        context.getSchemaModel().getEntities().values().stream()
                .filter(childCandidate -> !childCandidate.getEntityName().equals(parentEntity.getEntityName()))
                .forEach(childEntity -> {
                    String fqcn = childEntity.getFqcn() == null ? childEntity.getEntityName() : childEntity.getFqcn();
                    TypeElement childType = context.getElementUtils().getTypeElement(fqcn);
                    if (childType != null && context.getTypeUtils().isSubtype(childType.asType(), parentType.asType())) {
                        processSingleTablePerClassChild(childEntity, parentEntity);
                        checkIdentityStrategy(childType, childEntity); // Check children too
                    }
                });
    }

    private static <T> List<T> safeList(List<T> src) {
        return (src == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(src);
    }

    /**
     * Processes a single child entity in a TABLE_PER_CLASS inheritance hierarchy by copying parent properties.
     *
     * @param childEntity The child entity model.
     * @param parentEntity The parent entity model.
     */
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