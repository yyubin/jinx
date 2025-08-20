package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.*;
import java.util.stream.Collectors;

public class EmbeddedHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;

    public EmbeddedHandler(ProcessingContext context, ColumnHandler columnHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
    }

    public void processEmbedded(VariableElement field, EntityModel ownerEntity, Set<String> processedTypes) {
        TypeMirror typeMirror = field.asType();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        Map<String, String> columnOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> associationOverrides = new HashMap<>();

        AttributeOverrides attrOverrides = field.getAnnotation(AttributeOverrides.class);
        if (attrOverrides != null) {
            for (AttributeOverride override : attrOverrides.value()) {
                columnOverrides.put(override.name(), override.column().name());
            }
        }
        AttributeOverride singleAttrOverride = field.getAnnotation(AttributeOverride.class);
        if (singleAttrOverride != null) {
            columnOverrides.put(singleAttrOverride.name(), singleAttrOverride.column().name());
        }

        AssociationOverrides assocOverrides = field.getAnnotation(AssociationOverrides.class);
        if (assocOverrides != null) {
            for (AssociationOverride ao : assocOverrides.value()) {
                associationOverrides.put(ao.name(), Arrays.asList(ao.joinColumns()));
            }
        }
        AssociationOverride singleAssocOverride = field.getAnnotation(AssociationOverride.class);
        if (singleAssocOverride != null) {
            associationOverrides.put(singleAssocOverride.name(), Arrays.asList(singleAssocOverride.joinColumns()));
        }

        String prefix = "";
        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;

            if (embeddedField.getAnnotation(Embedded.class) != null) {
                processEmbedded(embeddedField, ownerEntity, processedTypes);
            } else if (embeddedField.getAnnotation(ManyToOne.class) != null || embeddedField.getAnnotation(OneToOne.class) != null) {
                processEmbeddedRelationship(embeddedField, ownerEntity, associationOverrides, prefix);
            } else {
                ColumnModel column = columnHandler.createFrom(embeddedField, columnOverrides);
                if (column != null) {
                    String columnName = prefix + column.getColumnName();
                    column.setColumnName(columnName);
                    ownerEntity.getColumns().putIfAbsent(columnName, column);
                }
            }
        }

        processedTypes.remove(typeName);
    }

    private void processEmbeddedRelationship(VariableElement field, EntityModel ownerEntity, Map<String, List<JoinColumn>> associationOverrides, String prefix) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);

        List<JoinColumn> joinColumns = associationOverrides.get(field.getSimpleName().toString());
        if (joinColumns == null) {
            JoinColumns joinColumnsAnno = field.getAnnotation(JoinColumns.class);
            if (joinColumnsAnno != null) joinColumns = Arrays.asList(joinColumnsAnno.value());
            else {
                JoinColumn joinColumnAnno = field.getAnnotation(JoinColumn.class);
                joinColumns = (joinColumnAnno != null) ? List.of(joinColumnAnno) : Collections.emptyList();
            }
        }

        TypeElement referencedTypeElement = (TypeElement) ((DeclaredType) field.asType()).asElement();
        EntityModel referencedEntity = context.getSchemaModel().getEntities()
                .get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
        if (refPkList.isEmpty()) return;

        // 명시적 JoinColumns가 있고, 복합키인데 개수가 맞지 않으면 오류
        if (!joinColumns.isEmpty() && refPkList.size() != joinColumns.size()) {
            context.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "@JoinColumns size mismatch: expected " + refPkList.size() + " but got " + joinColumns.size()
                            + " on " + ownerEntity.getEntityName() + "." + field.getSimpleName()
            );
            return;
        }

        Map<String, ColumnModel> refPkMap = refPkList.stream()
                .collect(Collectors.toMap(ColumnModel::getColumnName, c -> c, (a,b)->a, LinkedHashMap::new));

        List<String> fkColumnNames = new ArrayList<>();
        List<String> referencedPkNamesInOrder = new ArrayList<>();

        MapsId mapsId = field.getAnnotation(MapsId.class);
        String mapsIdAttr = mapsId != null && !mapsId.value().isEmpty() ? mapsId.value() : null;

        // foreignKey 명시가 있으면 우선 사용
        String explicitFkName = null;
        if (!joinColumns.isEmpty() && joinColumns.get(0).foreignKey() != null
                && !joinColumns.get(0).foreignKey().name().isEmpty()) {
            explicitFkName = joinColumns.get(0).foreignKey().name();
        }

        for (int i = 0; i < (joinColumns.isEmpty() ? refPkList.size() : joinColumns.size()); i++) {
            JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

            ColumnModel pkCol;
            if (jc != null && !jc.referencedColumnName().isEmpty()) {
                pkCol = refPkMap.get(jc.referencedColumnName());
            } else {
                pkCol = refPkList.get(i);
            }
            if (pkCol == null) {
                context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "referencedColumnName not found among parent PKs on " + field.getSimpleName());
                return;
            }

            String fkName = prefix + (jc != null && !jc.name().isEmpty()
                    ? jc.name() : field.getSimpleName() + "_" + pkCol.getColumnName());

            boolean colNullable = jc != null ? jc.nullable()
                    : (manyToOne != null ? manyToOne.optional() : oneToOne.optional());

            // 1:1 unique — 단일 FK일 때만 컬럼단위 unique. 복합키는 SQL 생성기에서 복합 유니크로 처리.
            boolean colUnique = (oneToOne != null && mapsId == null && (joinColumns.isEmpty() ? refPkList.size()==1 : joinColumns.size()==1))
                    && (jc != null ? jc.unique() : true);

            // MapsId 부분 매핑: 특정 속성만 PK로 승격
            boolean makePk = false;
            if (mapsId != null) {
                if (mapsIdAttr == null) makePk = true; // 전체 ID 매핑
                else {
                    // pk 컬럼명이 해당 속성(임베디드 경로 포함)에 해당하는지 매칭
                    makePk = pkCol.getColumnName().equalsIgnoreCase(mapsIdAttr) || pkCol.getColumnName().endsWith("_" + mapsIdAttr);
                }
            }

            ColumnModel existing = ownerEntity.getColumns().get(fkName);
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkName)
                    .javaType(pkCol.getJavaType())
                    .isPrimaryKey(makePk)
                    .isNullable(!makePk && colNullable)
                    .isUnique(colUnique)
                    .generationStrategy(GenerationStrategy.NONE)
                    .build();

            if (existing == null) {
                ownerEntity.getColumns().put(fkName, fkColumn);
            } else {
                // 타입/PK/nullable 불일치 검증
                if (!Objects.equals(existing.getJavaType(), pkCol.getJavaType())
                        || (existing.isPrimaryKey() != fkColumn.isPrimaryKey())
                        || (existing.isNullable() != fkColumn.isNullable())) {
                    context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "FK column mismatch on " + ownerEntity.getEntityName() + "." + fkName);
                    return;
                }
            }

            fkColumnNames.add(fkName);
            referencedPkNamesInOrder.add(pkCol.getColumnName());
        }

        RelationshipModel relationship = RelationshipModel.builder()
                .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
                .columns(fkColumnNames)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumns(referencedPkNamesInOrder)
                .mapsId(mapsId != null)
                .constraintName(explicitFkName != null ? explicitFkName
                        : context.getNaming().fkName(
                        ownerEntity.getTableName(), fkColumnNames,
                        referencedEntity.getTableName(), referencedPkNamesInOrder))
                .cascadeTypes(manyToOne != null ? toCascadeList(manyToOne.cascade()) : toCascadeList(oneToOne.cascade()))
                .orphanRemoval(oneToOne != null && oneToOne.orphanRemoval())
                .fetchType(manyToOne != null ? manyToOne.fetch() : oneToOne.fetch())
                .build();
        ownerEntity.getRelationships().put(relationship.getConstraintName(), relationship);
    }

    // FK 컬럼 생성을 위한 헬퍼 메서드
    private void createFkColumn(Map<String, ColumnModel> columns, MapsId mapsId, ManyToOne manyToOne, OneToOne oneToOne, String fkColumnName, ColumnModel referencedPkColumn) {
        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null && (manyToOne != null ? manyToOne.optional() : oneToOne.optional()))
                .isUnique(oneToOne != null && mapsId == null)
                .generationStrategy(GenerationStrategy.NONE)
                .build();
        columns.putIfAbsent(fkColumnName, fkColumn);
    }

    public void processEmbeddableFields(TypeElement embeddableType, EntityModel ownerCollectionTable, Set<String> processedTypes, String prefix, VariableElement collectionField) {
        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
        processedTypes.add(typeName);

        String effectivePrefix = (prefix != null ? prefix : "")
                + (collectionField != null ? collectionField.getSimpleName() + "_" : "");

        // 컬렉션 필드 수준의 Override 수집
        Map<String, String> attrOverrides = new HashMap<>();
        Map<String, List<JoinColumn>> assocOverrides = new HashMap<>();

        if (collectionField != null) {
            AttributeOverrides aos = collectionField.getAnnotation(AttributeOverrides.class);
            if (aos != null) {
                for (AttributeOverride ao : aos.value()) {
                    attrOverrides.put(ao.name(), ao.column().name());
                }
            }
            AttributeOverride aoSingle = collectionField.getAnnotation(AttributeOverride.class);
            if (aoSingle != null) {
                attrOverrides.put(aoSingle.name(), aoSingle.column().name());
            }

            AssociationOverrides as = collectionField.getAnnotation(AssociationOverrides.class);
            if (as != null) {
                for (AssociationOverride a : as.value()) {
                    assocOverrides.put(a.name(), Arrays.asList(a.joinColumns()));
                }
            }
            AssociationOverride aSingle = collectionField.getAnnotation(AssociationOverride.class);
            if (aSingle != null) {
                assocOverrides.put(aSingle.name(), Arrays.asList(aSingle.joinColumns()));
            }
        }

        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;

            if (embeddedField.getAnnotation(Embedded.class) != null) {
                processEmbedded(embeddedField, ownerCollectionTable, processedTypes);
            } else if (embeddedField.getAnnotation(ManyToOne.class) != null || embeddedField.getAnnotation(OneToOne.class) != null) {
                processEmbeddedRelationship(embeddedField, ownerCollectionTable, assocOverrides, effectivePrefix);
            } else {
                Map<String, String> overrides = new HashMap<>();
                String key = embeddedField.getSimpleName().toString();
                if (attrOverrides.containsKey(key)) {
                    overrides.put(key, attrOverrides.get(key));
                }
                ColumnModel column = columnHandler.createFrom(embeddedField, overrides);
                if (column != null) {
                    String columnName = effectivePrefix + column.getColumnName();
                    column.setColumnName(columnName);
                    ownerCollectionTable.getColumns().putIfAbsent(columnName, column);
                }
            }
        }

        processedTypes.remove(typeName);
    }

    private static List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? List.of() : Arrays.stream(arr).toList();
    }
}