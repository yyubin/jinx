package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.AttributeDescriptorFactory;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;
import java.util.stream.Collectors;

public class EmbeddedHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final RelationshipHandler relationshipHandler;
    private final AttributeDescriptorFactory descriptorFactory;
    private final Types typeUtils;
    private final Elements elementUtils;

    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler, RelationshipHandler relationshipHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.relationshipHandler = relationshipHandler;
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
        this.descriptorFactory = new AttributeDescriptorFactory(typeUtils, elementUtils, context);
    }

    public void processEmbedded(VariableElement field, EntityModel ownerEntity, Set<String> processedTypes) {
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processEmbedded(fieldDescriptor, ownerEntity, processedTypes);
    }

    public void processEmbedded(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, false, "", nameOverrides, tableOverrides, assocOverrides);
    }

    public void processEmbeddedId(VariableElement field, EntityModel ownerEntity, Set<String> processedTypes) {
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processEmbeddedId(fieldDescriptor, ownerEntity, processedTypes);
    }

    public void processEmbeddedId(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, true, "", nameOverrides, tableOverrides, assocOverrides);
    }

    public void processEmbeddableFields(TypeElement embeddableType, EntityModel ownerCollectionTable, Set<String> processedTypes, String prefix, VariableElement collectionField) {
        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        AttributeDescriptor collectionDescriptor = (collectionField != null) ? new FieldAttributeDescriptor(collectionField, typeUtils, elementUtils) : null;

        String effectivePrefix = (prefix != null ? prefix : "")
                + (collectionDescriptor != null ? collectionDescriptor.name() + "_" : "");

        Map<String, String> attrNameOverrides = new HashMap<>();
        Map<String, String> attrTableOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = (collectionDescriptor != null)
                ? extractOverrides(collectionDescriptor, attrNameOverrides, attrTableOverrides)
                : Collections.emptyMap();

        List<AttributeDescriptor> embeddedDescriptors = descriptorFactory.createDescriptors(embeddableType);

        for (AttributeDescriptor embeddedDescriptor : embeddedDescriptors) {
            if (embeddedDescriptor.hasAnnotation(Transient.class)) continue;

            String attributeName = embeddedDescriptor.name();

            if (embeddedDescriptor.hasAnnotation(Embedded.class)) {
                String childPrefix = attributeName + ".";
                Map<String, String> childNameOverrides = filterAndRemapOverrides(attrNameOverrides, childPrefix);
                Map<String, String> childTableOverrides = filterAndRemapOverrides(attrTableOverrides, childPrefix);
                Map<String, List<JoinColumn>> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, childPrefix);

                processEmbeddedInternal(embeddedDescriptor, ownerCollectionTable, processedTypes, false, effectivePrefix, childNameOverrides, childTableOverrides, childAssocOverrides);
            } else if (embeddedDescriptor.hasAnnotation(ManyToOne.class) || embeddedDescriptor.hasAnnotation(OneToOne.class)) {
                String childPrefix = attributeName + ".";
                Map<String, List<JoinColumn>> childAssocOverrides =
                        filterAndRemapAssocOverrides(assocOverrides, childPrefix);
                processEmbeddedRelationship(embeddedDescriptor, ownerCollectionTable, childAssocOverrides, effectivePrefix);
            } else {
                ColumnModel column = columnHandler.createFromAttribute(embeddedDescriptor, ownerCollectionTable, attrNameOverrides);
                if (column != null) {
                    String attrName = embeddedDescriptor.name();

                    String overrideName = attrNameOverrides.get(attrName);
                    boolean hasOverrideName = (overrideName != null && !overrideName.isEmpty());
                    Column leafColAnn = embeddedDescriptor.getAnnotation(Column.class);
                    boolean hasExplicitLeafName = (leafColAnn != null && !leafColAnn.name().isEmpty());

                    String targetTable = attrTableOverrides.get(attrName);
                    if (targetTable != null && !targetTable.isEmpty()) {
                        boolean isValidTable = ownerCollectionTable.getTableName().equals(targetTable) ||
                                ownerCollectionTable.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(targetTable));
                        if (isValidTable) {
                            column.setTableName(targetTable);
                        } else {
                            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                                    "AttributeOverride.table '" + targetTable + "' is not a primary/secondary table of " +
                                            ownerCollectionTable.getEntityName() + ". Falling back to " + column.getTableName(),
                                    collectionDescriptor != null ? collectionDescriptor.elementForDiagnostics() : embeddableType);
                        }
                    }

                    if (!(hasOverrideName || hasExplicitLeafName)) {
                        column.setColumnName(effectivePrefix + column.getColumnName());
                    }

                    if (!ownerCollectionTable.hasColumn(column.getTableName(), column.getColumnName())) {
                        ownerCollectionTable.putColumn(column);
                    }
                }
            }
        }
        processedTypes.remove(typeName);
    }

    private void processEmbeddedInternal(
            AttributeDescriptor attribute,
            EntityModel ownerEntity,
            Set<String> processedTypes,
            boolean isPrimaryKey,
            String parentPrefix,
            Map<String, String> nameOverrides,
            Map<String, String> tableOverrides,
            Map<String, List<JoinColumn>> assocOverrides) {

        TypeMirror typeMirror = attribute.type();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        String prefix = parentPrefix + attribute.name() + "_";

        List<AttributeDescriptor> embeddedDescriptors = descriptorFactory.createDescriptors(embeddableType);

        for (AttributeDescriptor embeddedDescriptor : embeddedDescriptors) {
            if (embeddedDescriptor.hasAnnotation(Transient.class)) continue;

            String attrName = embeddedDescriptor.name();

            if (embeddedDescriptor.hasAnnotation(Embedded.class)) {
                String childPrefix = attrName + ".";
                Map<String, String> childNameOverrides = filterAndRemapOverrides(nameOverrides, childPrefix);
                Map<String, String> childTableOverrides = filterAndRemapOverrides(tableOverrides, childPrefix);
                Map<String, List<JoinColumn>> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, childPrefix);

                processEmbeddedInternal(embeddedDescriptor, ownerEntity, processedTypes, isPrimaryKey, prefix, childNameOverrides, childTableOverrides, childAssocOverrides);
            } else if (embeddedDescriptor.hasAnnotation(ManyToOne.class) || embeddedDescriptor.hasAnnotation(OneToOne.class)) {
                String childPrefix = attrName + ".";
                Map<String, List<JoinColumn>> childAssocOverrides =
                        filterAndRemapAssocOverrides(assocOverrides, childPrefix);
                processEmbeddedRelationship(embeddedDescriptor, ownerEntity, childAssocOverrides, prefix);
            } else {
                ColumnModel column = columnHandler.createFromAttribute(embeddedDescriptor, ownerEntity, nameOverrides);
                if (column != null) {
                    String overrideName = nameOverrides.get(attrName);
                    boolean hasOverrideName = (overrideName != null && !overrideName.isEmpty());
                    Column leafColAnn = embeddedDescriptor.getAnnotation(Column.class);
                    boolean hasExplicitLeafName = (leafColAnn != null && !leafColAnn.name().isEmpty());

                    String targetTable = tableOverrides.get(attrName);
                    if (targetTable != null && !targetTable.isEmpty()) {
                        boolean isValidTable = ownerEntity.getTableName().equals(targetTable) ||
                                ownerEntity.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(targetTable));
                        if (isValidTable) {
                            column.setTableName(targetTable);
                        } else {
                            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                                    "AttributeOverride.table '" + targetTable + "' is not a primary/secondary table of " +
                                            ownerEntity.getEntityName() + ". Falling back to " + column.getTableName(),
                                    attribute.elementForDiagnostics());
                        }
                    }

                    if (!(hasOverrideName || hasExplicitLeafName)) {
                        column.setColumnName(prefix + column.getColumnName());
                    }

                    if (isPrimaryKey) {
                        column.setPrimaryKey(true);
                        column.setNullable(false);
                    }

                    if (!ownerEntity.hasColumn(column.getTableName(), column.getColumnName())) {
                        ownerEntity.putColumn(column);
                    }
                }
            }
        }

        processedTypes.remove(typeName);
    }

    private void processEmbeddedRelationship(AttributeDescriptor attribute, EntityModel ownerEntity,
                                             Map<String, List<JoinColumn>> associationOverrides, String prefix) {
        ManyToOne manyToOne = attribute.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = attribute.getAnnotation(OneToOne.class);

        List<JoinColumn> joinColumns = associationOverrides.get(attribute.name());
        if (joinColumns == null) {
            JoinColumns joinColumnsAnno = attribute.getAnnotation(JoinColumns.class);
            if (joinColumnsAnno != null) joinColumns = Arrays.asList(joinColumnsAnno.value());
            else {
                JoinColumn joinColumnAnno = attribute.getAnnotation(JoinColumn.class);
                joinColumns = (joinColumnAnno != null) ? List.of(joinColumnAnno) : Collections.emptyList();
            }
        }

        TypeElement referencedTypeElement = resolveTargetEntityForEmbedded(attribute, manyToOne, oneToOne).orElse(null);
        if (referencedTypeElement == null) return;
        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) return;

        if (joinColumns.isEmpty() && refPkList.size() > 1) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Composite primary key on " + referencedEntity.getEntityName() +
                            " requires explicit @JoinColumns on embedded relationship " +
                            ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics());
            return;
        }

        if (!joinColumns.isEmpty() && refPkList.size() != joinColumns.size()) {
            context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch: expected " + refPkList.size() + " but got " + joinColumns.size()
                            + " on " + ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics()
            );
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c, (a, b) -> a, HashMap::new));

        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNamesInOrder = new ArrayList<>();
        String fkTableName = null;

        MapsId mapsId = attribute.getAnnotation(MapsId.class);
        String mapsIdAttr = mapsId != null && !mapsId.value().isEmpty() ? mapsId.value() : null;

        String explicitFkName = null;
        if (!joinColumns.isEmpty()) {
            for (JoinColumn jc : joinColumns) {
                String fkName = jc.foreignKey().name();
                if (fkName != null && !fkName.isEmpty()) {
                    if (explicitFkName == null) {
                        explicitFkName = fkName;
                    } else if (!explicitFkName.equals(fkName)) {
                        context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "All @JoinColumn.foreignKey names must be identical for composite FK. Found: '" + explicitFkName + "' and '" + fkName + "' on embedded relationship " + attribute.name(),
                                attribute.elementForDiagnostics());
                        return;
                    }
                }
            }

            if (!relationshipHandler.allSameConstraintMode(joinColumns)) {
                context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "All @JoinColumn.foreignKey.value must be identical for composite FK on embedded relationship " + attribute.name(),
                        attribute.elementForDiagnostics());
                return;
            }
        }

        boolean noConstraint = !joinColumns.isEmpty() &&
                joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

        // 1) 검증 단계: FK 컬럼들을 미리 생성하고 검증
        List<ColumnModel> pendingFkColumns = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < (joinColumns.isEmpty() ? refPkList.size() : joinColumns.size()); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            ColumnModel pkCol;
            if (jc != null && !jc.referencedColumnName().isEmpty()) {
                pkCol = refPkMap.get(jc.referencedColumnName());
            } else {
                pkCol = refPkList.get(i);
            }
            if (pkCol == null) {
                errors.add("referencedColumnName not found among parent PKs on " + attribute.name());
                continue;
            }

            String fkName = (jc != null && !jc.name().isEmpty())
                    ? jc.name() // Use explicit name as-is
                    : prefix + attribute.name() + "_" + pkCol.getColumnName(); // Apply prefix only to default-generated name

            boolean colNullable = jc != null ? jc.nullable()
                    : (manyToOne != null ? manyToOne.optional() : oneToOne.optional());

            boolean colUnique = (oneToOne != null && mapsId == null && (joinColumns.isEmpty() ? refPkList.size() == 1 : joinColumns.size() == 1))
                    && (jc != null ? jc.unique() : true);

            boolean makePk = false;
            if (mapsId != null) {
                if (mapsIdAttr == null) makePk = true;
                else {
                    makePk = pkCol.getColumnName().equalsIgnoreCase(mapsIdAttr) || pkCol.getColumnName().endsWith("_" + mapsIdAttr);
                }
            }

            String fkTable = resolveJoinColumnTable(jc, ownerEntity);
            if (fkTableName == null) {
                fkTableName = fkTable;
            } else if (!fkTableName.equals(fkTable)) {
                errors.add("All @JoinColumn annotations in composite key must target the same table for embedded relationship " + attribute.name() + ". Found: '" + fkTableName + "' and '" + fkTable + "'.");
                continue;
            }

            ColumnModel existing = ownerEntity.findColumn(fkTable, fkName);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .tableName(fkTable)
                    .javaType(pkCol.getJavaType())
                    .isPrimaryKey(makePk)
                    .isNullable(!makePk && colNullable)
                    // 컬럼 레벨 unique 제거 - 제약 기반으로만 처리
                    .generationStrategy(GenerationStrategy.NONE)
                    .build();

            if (existing == null) {
                pendingFkColumns.add(fkColumn);
            } else {
                if (!Objects.equals(existing.getJavaType(), pkCol.getJavaType())
                        || (existing.isPrimaryKey() != fkColumn.isPrimaryKey())
                        || (existing.isNullable() != fkColumn.isNullable())) {
                    errors.add("FK column mismatch on " + ownerEntity.getEntityName() + "." + fkName);
                    continue;
                }
            }

            fkColumnNames.add(fkName);
            referencedPkNamesInOrder.add(pkCol.getColumnName());
        }

        // 2) 커밋 단계: 오류가 없을 때만 실제 ownerEntity에 추가
        if (!errors.isEmpty()) {
            errors.forEach(msg -> context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, msg, attribute.elementForDiagnostics()));
            return;
        }
        pendingFkColumns.forEach(ownerEntity::putColumn);

        final String fkTableBase = (fkTableName != null) ? fkTableName : ownerEntity.getTableName();

        // For 1:1 relationships on a single FK, explicitly create a UNIQUE constraint model
        // This ensures physical constraint creation regardless of the DDL generator's strategy.
        if (oneToOne != null && mapsId == null && fkColumnNames.size() == 1) {
            boolean isJoinColumnUnique = joinColumns.isEmpty() || joinColumns.get(0).unique();
            if (isJoinColumnUnique) {
                if (!relationshipHandler.coveredByPkOrUnique(ownerEntity, fkTableBase, fkColumnNames)) {
                    String uqName = context.getNaming().uqName(fkTableBase, fkColumnNames);
                    ownerEntity.getConstraints().putIfAbsent(uqName, ConstraintModel.builder()
                            .name(uqName)
                            .type(ConstraintType.UNIQUE)
                            .tableName(fkTableBase)
                            .columns(new ArrayList<>(fkColumnNames))
                            .build());
                }
            }
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .tableName(fkTableBase)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNamesInOrder)
                .mapsId(mapsId != null)
                .noConstraint(noConstraint)
                .constraintName(explicitFkName != null ? explicitFkName
                        : context.getNaming().fkName(
                        fkTableBase, fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNamesInOrder))
                .cascadeTypes(manyToOne != null ? toCascadeList(manyToOne.cascade()) : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();
        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);

        relationshipHandler.addForeignKeyIndex(ownerEntity, fkColumnNames, fkTableBase);
    }

    private Optional<TypeElement> resolveTargetEntityForEmbedded(AttributeDescriptor attr, ManyToOne m2o, OneToOne o2o) {
        // 1) 명시적 targetEntity 우선 처리(MirroredTypeException 대응)
        try {
            Class<?> c =
                    (m2o != null) ? m2o.targetEntity() :
                            (o2o != null) ? o2o.targetEntity() : void.class;
            if (c != void.class) {
                TypeElement te = elementUtils.getTypeElement(c.getName());
                if (te != null) return Optional.of(te);
            }
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            if (mte.getTypeMirror() instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
                return Optional.of(te);
            }
        }
        // 2) 필드/프로퍼티 타입으로 추론
        if (attr.type() instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
            return Optional.of(te);
        }
        return Optional.empty();
    }

    private String resolveJoinColumnTable(JoinColumn jc, EntityModel owner) {
        String primary = owner.getTableName();
        if (jc == null || jc.table().isEmpty()) return primary;
        String req = jc.table();
        boolean ok = primary.equals(req) ||
                owner.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(req));
        if (!ok) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "JoinColumn.table='" + req + "' is not a primary/secondary table of " +
                            owner.getEntityName() + ". Falling back to '" + primary + "'.");
            return primary;
        }
        return req;
    }

    private Map<String, List<JoinColumn>> extractOverrides(AttributeDescriptor attribute, Map<String, String> nameOverrides, Map<String, String> tableOverrides) {
        AttributeOverrides aos = attribute.getAnnotation(AttributeOverrides.class);
        if (aos != null) {
            for (AttributeOverride ao : aos.value()) {
                if (!ao.column().name().isEmpty()) nameOverrides.put(ao.name(), ao.column().name());
                if (!ao.column().table().isEmpty()) tableOverrides.put(ao.name(), ao.column().table());
            }
        }
        AttributeOverride aoSingle = attribute.getAnnotation(AttributeOverride.class);
        if (aoSingle != null) {
            if (!aoSingle.column().name().isEmpty()) nameOverrides.put(aoSingle.name(), aoSingle.column().name());
            if (!aoSingle.column().table().isEmpty()) tableOverrides.put(aoSingle.name(), aoSingle.column().table());
        }

        Map<String, List<JoinColumn>> assocOverrides = new HashMap<>();
        AssociationOverrides as = attribute.getAnnotation(AssociationOverrides.class);
        if (as != null) {
            for (AssociationOverride a : as.value()) {
                assocOverrides.put(a.name(), Arrays.asList(a.joinColumns()));
            }
        }
        AssociationOverride aSingle = attribute.getAnnotation(AssociationOverride.class);
        if (aSingle != null) {
            assocOverrides.put(aSingle.name(), Arrays.asList(aSingle.joinColumns()));
        }
        return assocOverrides;
    }

    private Map<String, String> filterAndRemapOverrides(Map<String, String> parentOverrides, String prefix) {
        return parentOverrides.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    private Map<String, List<JoinColumn>> filterAndRemapAssocOverrides(Map<String, List<JoinColumn>> parentOverrides, String prefix) {
        return parentOverrides.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    private static List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }
}
