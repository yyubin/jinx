package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.AttributeDescriptorFactory;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.handler.relationship.RelationshipSupport;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;
import java.util.stream.Collectors;

public class EmbeddedHandler {
    
    /**
     * Holds information from @AssociationOverride
     */
    public static class AssociationOverrideInfo {
        private final List<JoinColumn> joinColumns;
        private final JoinTable joinTable;
        
        public AssociationOverrideInfo(List<JoinColumn> joinColumns, JoinTable joinTable) {
            this.joinColumns = joinColumns;
            this.joinTable = joinTable;
        }
        
        public List<JoinColumn> getJoinColumns() { return joinColumns; }
        public JoinTable getJoinTable() { return joinTable; }
        public boolean hasJoinTable() { return joinTable != null; }
    }
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final RelationshipHandler relationshipHandler;
    private final RelationshipSupport support;
    private final AttributeDescriptorFactory descriptorFactory;
    private final Types typeUtils;
    private final Elements elementUtils;

    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler, RelationshipHandler relationshipHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.relationshipHandler = relationshipHandler;
        this.descriptorFactory = context.getAttributeDescriptorFactory();
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
        this.support = new RelationshipSupport(context);
    }

    public void processEmbedded(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, Column> columnOverrides = new HashMap<>();
        Map<String, AssociationOverrideInfo> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides, columnOverrides);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, false, "", "", nameOverrides, tableOverrides, columnOverrides, assocOverrides);
    }

    public void processEmbeddedId(AttributeDescriptor attribute, EntityModel ownerEntity, Set<String> processedTypes) {
        Map<String, String> nameOverrides = new HashMap<>();
        Map<String, String> tableOverrides = new HashMap<>();
        Map<String, Column> columnOverrides = new HashMap<>();
        Map<String, AssociationOverrideInfo> assocOverrides = extractOverrides(attribute, nameOverrides, tableOverrides, columnOverrides);

        List<ColumnModel> pksBefore = context.findAllPrimaryKeyColumns(ownerEntity);

        processEmbeddedInternal(attribute, ownerEntity, processedTypes, true, "", attribute.name(), nameOverrides, tableOverrides, columnOverrides, assocOverrides);

        List<ColumnModel> pksAfter = context.findAllPrimaryKeyColumns(ownerEntity);
        List<String> newPkColumnNames = pksAfter.stream()
            .filter(c -> !pksBefore.contains(c))
            .map(ColumnModel::getColumnName)
            .toList();

        if (!newPkColumnNames.isEmpty()) {
            context.registerPkAttributeColumns(ownerEntity.getFqcn(), attribute.name(), newPkColumnNames);
        }
    }

    private void processEmbeddedInternal(
            AttributeDescriptor attribute,
            EntityModel ownerEntity,
            Set<String> processedTypes,
            boolean isPrimaryKey,
            String parentColumnPrefix,
            String parentAttributePath,
            Map<String, String> nameOverrides,
            Map<String, String> tableOverrides,
            Map<String, Column> columnOverrides,
            Map<String, AssociationOverrideInfo> assocOverrides) {

        TypeMirror typeMirror = attribute.type();
        if (!(typeMirror instanceof DeclaredType)) return;
        
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        String columnPrefix = parentColumnPrefix.isEmpty() 
                ? attribute.name()
                : parentColumnPrefix + "_" + attribute.name();

        // Use cached descriptors in production, factory in tests
        List<AttributeDescriptor> embeddedDescriptors;
        TypeElement registryType = context.getEmbeddableElement(typeName);
        
        if (registryType != null) {
            // Production path: use cached descriptors for consistent results
            embeddedDescriptors = context.getCachedDescriptors(registryType);
        } else {
            // Test/fallback path: use factory directly
            embeddedDescriptors = descriptorFactory.createDescriptors(embeddableType);
        }

        for (AttributeDescriptor embeddedDescriptor : embeddedDescriptors) {
            if (embeddedDescriptor.hasAnnotation(Transient.class)) continue;

            String currentAttributeName = embeddedDescriptor.name();
            String currentAttributePath = parentAttributePath.isEmpty() ? currentAttributeName : parentAttributePath + "." + currentAttributeName;

            if (embeddedDescriptor.hasAnnotation(Embedded.class)) {
                Map<String, String> childNameOverrides = filterAndRemapOverrides(nameOverrides, currentAttributeName + ".");
                Map<String, String> childTableOverrides = filterAndRemapOverrides(tableOverrides, currentAttributeName + ".");
                Map<String, Column> childColumnOverrides = filterAndRemapColumnOverrides(columnOverrides, currentAttributeName + ".");
                Map<String, AssociationOverrideInfo> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, currentAttributeName + ".");

                processEmbeddedInternal(embeddedDescriptor, ownerEntity, processedTypes, isPrimaryKey, columnPrefix, currentAttributePath, childNameOverrides, childTableOverrides, childColumnOverrides, childAssocOverrides);
            } else if (embeddedDescriptor.hasAnnotation(ManyToOne.class) || embeddedDescriptor.hasAnnotation(OneToOne.class)) {
                Map<String, AssociationOverrideInfo> childAssocOverrides = filterAndRemapAssocOverrides(assocOverrides, currentAttributeName + ".");
                processEmbeddedRelationship(embeddedDescriptor, ownerEntity, childAssocOverrides, columnPrefix);
            } else {
                // Use empty map for overrides - name/table overrides are applied later
                ColumnModel column = columnHandler.createFromAttribute(embeddedDescriptor, ownerEntity, Collections.emptyMap());
                if (column != null) {
                    String overrideName = nameOverrides.get(currentAttributeName);
                    boolean hasOverrideName = (overrideName != null && !overrideName.isEmpty());
                    Column leafColAnn = embeddedDescriptor.getAnnotation(Column.class);
                    boolean hasExplicitLeafName = (leafColAnn != null && !leafColAnn.name().isEmpty());

                    // Apply @Column overrides from @AttributeOverride
                    Column columnOverride = columnOverrides.get(currentAttributeName);
                    if (columnOverride != null) {
                        applyColumnOverrides(column, columnOverride, ownerEntity, attribute);
                    }

                    String targetTable = tableOverrides.get(currentAttributeName);
                    if (targetTable != null && !targetTable.isEmpty()) {
                        boolean isValidTable = ownerEntity.getTableName().equals(targetTable) ||
                                ownerEntity.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(targetTable));
                        if (isValidTable) {
                            column.setTableName(targetTable);
                        } else {
                            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                    "@AssociationOverride(joinTable=...) is not supported in embedded relationships: " + attribute.name(),
                                    attribute.elementForDiagnostics());
                            ownerEntity.setValid(false);
                            return;
                        }
                    }

                    if (hasOverrideName) {
                        // 1. 최우선: AttributeOverride가 있으면 접두사 없이 해당 이름 사용
                        column.setColumnName(overrideName);
                    }
                    else if (hasExplicitLeafName) {
                        // 2. 차선: 명시적 @Column 이름이 있으면 접두사 없이 해당 이름 사용
                        // createFromAttribute에서 이미 설정되었으므로 별도 작업 불필요. 이 블록은 로직의 명확성을 위해 존재.
                    }
                    else {
                        // 3. 기본: 접두사와 컬럼명을 조합하여 이름 설정
                        column.setColumnName(columnPrefix + "_" + column.getColumnName());
                    }

                    if (isPrimaryKey) {
                        column.setPrimaryKey(true);
                        column.setNullable(false);
                        context.registerPkAttributeColumns(ownerEntity.getFqcn(), currentAttributePath, List.of(column.getColumnName()));
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
                                             Map<String, AssociationOverrideInfo> associationOverrides, String prefix) {
        ManyToOne manyToOne = attribute.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = attribute.getAnnotation(OneToOne.class);

        AssociationOverrideInfo overrideInfo = associationOverrides.get(attribute.name());
        List<JoinColumn> joinColumns = null;
        JoinTable joinTable = null;
        
        if (overrideInfo != null) {
            joinColumns = overrideInfo.getJoinColumns();
            joinTable = overrideInfo.getJoinTable();
        }
        
        // If no override, get from attribute annotations
        if (joinColumns == null && joinTable == null) {
            JoinColumns joinColumnsAnno = attribute.getAnnotation(JoinColumns.class);
            if (joinColumnsAnno != null) {
                joinColumns = Arrays.asList(joinColumnsAnno.value());
            } else {
                JoinColumn joinColumnAnno = attribute.getAnnotation(JoinColumn.class);
                joinColumns = (joinColumnAnno != null) ? List.of(joinColumnAnno) : Collections.emptyList();
            }
            
            // Check for JoinTable annotation
            joinTable = attribute.getAnnotation(JoinTable.class);
        }
        
        // Ensure joinColumns is not null
        if (joinColumns == null) {
            joinColumns = Collections.emptyList();
        }
        
        // Handle JoinTable case
        if (joinTable != null) {
            // TODO: Implement JoinTable-based relationship processing for embedded contexts
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "JoinTable support in embedded relationships is not yet fully implemented for attribute: " + attribute.name(),
                    attribute.elementForDiagnostics());
            return;
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

            if (!support.allSameConstraintMode(joinColumns)) {
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
                    : prefix + "_" + attribute.name() + "_" + pkCol.getColumnName(); // Apply prefix with proper separator

            boolean colNullable = jc != null ? jc.nullable()
                    : (manyToOne != null ? manyToOne.optional() : oneToOne.optional());

            boolean colUnique = (oneToOne != null && mapsId == null && (joinColumns.isEmpty() ? refPkList.size() == 1 : joinColumns.size() == 1))
                    && (jc != null ? jc.unique() : true);

            // @MapsId PK 승격은 지연 단계(RelationshipHandler)에서 처리
            boolean makePk = false;

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
                    .isPrimaryKey(false)
                    .isNullable(colNullable)
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
                if (!support.coveredByPkOrUnique(ownerEntity, fkTableBase, fkColumnNames)) {
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

        support.addForeignKeyIndex(ownerEntity, fkColumnNames, fkTableBase);
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

    private Map<String, AssociationOverrideInfo> extractOverrides(AttributeDescriptor attribute, Map<String, String> nameOverrides, Map<String, String> tableOverrides, Map<String, Column> columnOverrides) {
        AttributeOverrides aos = attribute.getAnnotation(AttributeOverrides.class);
        if (aos != null) {
            for (AttributeOverride ao : aos.value()) {
                if (!ao.column().name().isEmpty()) nameOverrides.put(ao.name(), ao.column().name());
                if (!ao.column().table().isEmpty()) tableOverrides.put(ao.name(), ao.column().table());
                columnOverrides.put(ao.name(), ao.column());
            }
        }
        AttributeOverride aoSingle = attribute.getAnnotation(AttributeOverride.class);
        if (aoSingle != null) {
            if (!aoSingle.column().name().isEmpty()) nameOverrides.put(aoSingle.name(), aoSingle.column().name());
            if (!aoSingle.column().table().isEmpty()) tableOverrides.put(aoSingle.name(), aoSingle.column().table());
            columnOverrides.put(aoSingle.name(), aoSingle.column());
        }

        Map<String, AssociationOverrideInfo> assocOverrides = new HashMap<>();
        AssociationOverrides as = attribute.getAnnotation(AssociationOverrides.class);
        if (as != null) {
            for (AssociationOverride a : as.value()) {
                List<JoinColumn> joinColumns = a.joinColumns() != null && a.joinColumns().length > 0 
                        ? Arrays.asList(a.joinColumns()) : null;
                JoinTable joinTable = a.joinTable() != null && !a.joinTable().name().isEmpty() 
                        ? a.joinTable() : null;
                assocOverrides.put(a.name(), new AssociationOverrideInfo(joinColumns, joinTable));
            }
        }
        AssociationOverride aSingle = attribute.getAnnotation(AssociationOverride.class);
        if (aSingle != null) {
            List<JoinColumn> joinColumns = aSingle.joinColumns() != null && aSingle.joinColumns().length > 0 
                    ? Arrays.asList(aSingle.joinColumns()) : null;
            JoinTable joinTable = aSingle.joinTable() != null && !aSingle.joinTable().name().isEmpty() 
                    ? aSingle.joinTable() : null;
            assocOverrides.put(aSingle.name(), new AssociationOverrideInfo(joinColumns, joinTable));
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

    private Map<String, AssociationOverrideInfo> filterAndRemapAssocOverrides(Map<String, AssociationOverrideInfo> parentOverrides, String prefixWithDot) {
        Map<String, AssociationOverrideInfo> out = new HashMap<>();

        String dotted = prefixWithDot;
        String exact = dotted.endsWith(".") ? dotted.substring(0, dotted.length() - 1) : dotted;

        // (A) 자식 레벨 정확 일치: "country"
        if (parentOverrides.containsKey(exact)) {
            out.put(exact, parentOverrides.get(exact));
        }

        // (B) 하위 경로 매칭: "country.*" → 접두사 제거해 자식 기준 키로 재매핑
        for (Map.Entry<String, AssociationOverrideInfo> e : parentOverrides.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(dotted)) {
                out.put(k.substring(dotted.length()), e.getValue());
            }
        }
        return out;
    }

    private static List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }

    private Map<String, Column> filterAndRemapColumnOverrides(Map<String, Column> parentOverrides, String prefix) {
        return parentOverrides.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    /**
     * Apply @Column attributes from @AttributeOverride to the ColumnModel
     */
    private void applyColumnOverrides(ColumnModel column, Column columnOverride, EntityModel ownerEntity, AttributeDescriptor attribute) {
        // Apply nullable override
        if (columnOverride.nullable() != true) { // JPA default is true
            column.setNullable(columnOverride.nullable());
        }

        // Apply unique override  
        if (columnOverride.unique()) {
            column.setUnique(columnOverride.unique());
        }

        // Apply length override
        if (columnOverride.length() != 255) { // JPA default is 255
            column.setLength(columnOverride.length());
        }

        // Apply precision override
        if (columnOverride.precision() != 0) { // JPA default is 0
            column.setPrecision(columnOverride.precision());
        }

        // Apply scale override
        if (columnOverride.scale() != 0) { // JPA default is 0
            column.setScale(columnOverride.scale());
        }

        // Apply columnDefinition override
        if (!columnOverride.columnDefinition().isEmpty()) {
            column.setColumnDefinition(columnOverride.columnDefinition());
        }

        // TODO: Apply insertable/updatable overrides when ColumnModel supports them
        // if (!columnOverride.insertable()) { // JPA default is true
        //     column.setInsertable(columnOverride.insertable());
        // }
        // 
        // if (!columnOverride.updatable()) { // JPA default is true
        //     column.setUpdatable(columnOverride.updatable());
        // }
    }
}
