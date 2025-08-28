package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.util.ColumnUtils;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class ElementCollectionHandler {

    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final Types typeUtils;
    private final Elements elementUtils;

    public ElementCollectionHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
    }

    public void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        // Delegate to AttributeDescriptor-based method
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processElementCollection(fieldDescriptor, ownerEntity);
    }

    // Main AttributeDescriptor-based method for @ElementCollection processing
    public void processElementCollection(AttributeDescriptor attribute, EntityModel ownerEntity) {
        // 1. 컬렉션 테이블 이름 결정 및 새 EntityModel 생성
        String defaultTableName = ownerEntity.getTableName() + "_" + attribute.name();
        CollectionTable collectionTable = attribute.getAnnotation(CollectionTable.class);
        String tableName = (collectionTable != null && !collectionTable.name().isEmpty())
                ? collectionTable.name()
                : defaultTableName;

        EntityModel collectionEntity = EntityModel.builder()
                .entityName(tableName) // 테이블 이름을 고유 식별자로 사용
                .tableName(tableName)
                .tableType(EntityModel.TableType.COLLECTION_TABLE)
                .build();

        // 2. 컬렉션의 제네릭 타입 분석 (Map vs Collection) - FK 생성 전 검증
        TypeMirror attributeType = attribute.type();
        if (!(attributeType instanceof DeclaredType declaredType)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot determine collection type for @ElementCollection",
                    attribute.elementForDiagnostics());
            return;
        }

        boolean isMap = context.isSubtype(declaredType, "java.util.Map");
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        TypeMirror keyType = null;
        TypeMirror valueType = null;

        if (isMap && typeArguments.size() == 2) {
            keyType = typeArguments.get(0);
            valueType = typeArguments.get(1);
        } else if (!isMap && typeArguments.size() == 1) {
            valueType = typeArguments.get(0);
        } else {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot determine collection element type for @ElementCollection",
                    attribute.elementForDiagnostics());
            return;
        }

        // 3. 소유자 엔티티의 PK 정보 수집
        List<ColumnModel> ownerPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner entity " + ownerEntity.getEntityName() + " must have a primary key for @ElementCollection",
                    attribute.elementForDiagnostics());
            return;
        }

        // 4. @CollectionTable의 joinColumns 설정 또는 기본값으로 FK 컬럼 생성
        JoinColumn[] joinColumns = (collectionTable != null) ? collectionTable.joinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPkColumns.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@CollectionTable joinColumns size mismatch: expected " + ownerPkColumns.size() 
                    + " but got " + joinColumns.length + " on " + ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics());
            return;
        }

        // FK 컬럼들 생성 (referencedColumnName 우선 매핑)
        java.util.List<String> fkColumnNames = new java.util.ArrayList<>();
        java.util.Map<String, ColumnModel> ownerPkByName = new java.util.HashMap<>();
        for (ColumnModel pk : ownerPkColumns) ownerPkByName.put(pk.getColumnName(), pk);
        for (int i = 0; i < ownerPkColumns.size(); i++) {
            JoinColumn jc = (joinColumns.length > 0) ? joinColumns[i] : null;
            ColumnModel ownerPkCol =
                (jc != null && !jc.referencedColumnName().isEmpty())
                    ? java.util.Objects.requireNonNull(ownerPkByName.get(jc.referencedColumnName()),
                        "referencedColumnName not found in owner PKs: " + jc.referencedColumnName())
                    : ownerPkColumns.get(i);
            String fkColumnName = (jc != null && !jc.name().isEmpty())
                ? jc.name()
                : ownerEntity.getTableName() + "_" + ownerPkCol.getColumnName();
            ColumnModel fkColumn = ColumnModel.builder()
                    .columnName(fkColumnName)
                    .tableName(tableName)
                    .javaType(ownerPkCol.getJavaType())
                    .isPrimaryKey(true) // 컬렉션 테이블에서는 FK가 PK의 일부가 됨
                    .isNullable(false)
                    .build();
            collectionEntity.putColumn(fkColumn);
            fkColumnNames.add(fkColumnName);
        }

        // 5. Map Key 컬럼 처리
        if (isMap && keyType != null) {
            MapKeyColumn mapKeyColumn = attribute.getAnnotation(MapKeyColumn.class);
            String mapKeyColumnName = (mapKeyColumn != null && !mapKeyColumn.name().isEmpty())
                    ? mapKeyColumn.name()
                    : attribute.name() + "_KEY";

            ColumnModel keyColumn = createColumnFromType(keyType, mapKeyColumnName, tableName);
            if (keyColumn != null) {
                keyColumn.setPrimaryKey(true);
                keyColumn.setMapKey(true);
                keyColumn.setNullable(false);

                // Map Key 특수 어노테이션 처리
                MapKeyEnumerated mapKeyEnumerated = attribute.getAnnotation(MapKeyEnumerated.class);
                if (mapKeyEnumerated != null) {
                    keyColumn.setEnumStringMapping(mapKeyEnumerated.value() == EnumType.STRING);
                    if (keyColumn.isEnumStringMapping()) {
                        keyColumn.setEnumValues(ColumnUtils.getEnumConstants(keyType));
                    }
                }

                MapKeyTemporal mapKeyTemporal = attribute.getAnnotation(MapKeyTemporal.class);
                if (mapKeyTemporal != null) {
                    keyColumn.setTemporalType(mapKeyTemporal.value());
                }

                collectionEntity.putColumn(keyColumn);
            }
        }

        // 6. Element (Value) 컬럼 처리
        boolean isList = context.isSubtype(declaredType, "java.util.List");
        
        Element valueElement = typeUtils.asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // 값이 Embeddable 타입인 경우
            embeddedHandler.processEmbeddableFields((TypeElement) valueElement, collectionEntity, new HashSet<>(), null, null);
            // TODO: Embeddable 필드들의 PK 승격 처리 필요 (Set의 경우)
        } else {
            // 값이 기본 타입인 경우
            Column columnAnnotation = attribute.getAnnotation(Column.class);
            String elementColumnName = (columnAnnotation != null && !columnAnnotation.name().isEmpty())
                    ? columnAnnotation.name()
                    : attribute.name();

            ColumnModel elementColumn = createColumnFromType(valueType, elementColumnName, tableName);
            if (elementColumn != null) {
                boolean isSetPk = !isList && !isMap; // Set/Collection의 값은 PK에 포함
                elementColumn.setPrimaryKey(isSetPk);
                if (isSetPk) elementColumn.setNullable(false);
                collectionEntity.putColumn(elementColumn);
            }
        }

        // 7. @OrderColumn 처리 (List인 경우)
        if (isList) {
            OrderColumn orderColumn = attribute.getAnnotation(OrderColumn.class);
            if (orderColumn != null) {
                String orderColumnName = orderColumn.name().isEmpty() 
                    ? attribute.name() + "_ORDER" 
                    : orderColumn.name();
                ColumnModel orderCol = ColumnModel.builder()
                        .columnName(orderColumnName)
                        .tableName(tableName)
                        .javaType("java.lang.Integer")
                        .isPrimaryKey(true)            // (owner PK + order) = PK
                        .isNullable(false)             // PK는 NULL 불가
                        .build();
                collectionEntity.putColumn(orderCol);
            }
        }

        // 8. 외래 키 관계 모델 생성 (위 루프에서 확정한 fkColumnNames 사용)

        List<String> ownerPkNames = ownerPkColumns.stream().map(ColumnModel::getColumnName).toList();

        RelationshipModel fkRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)
                .tableName(tableName)
                .columns(fkColumnNames)
                .referencedTable(ownerEntity.getTableName())
                .referencedColumns(ownerPkNames)
                .constraintName(context.getNaming().fkName(tableName, fkColumnNames, ownerEntity.getTableName(), ownerPkNames))
                .build();
        collectionEntity.getRelationships().put(fkRelationship.getConstraintName(), fkRelationship);

        // 9. 완성된 컬렉션 테이블 모델을 스키마에 등록
        context.getSchemaModel().getEntities().putIfAbsent(collectionEntity.getEntityName(), collectionEntity);
    }

    /**
     * TypeMirror로부터 ColumnModel을 생성하는 헬퍼 메소드
     */
    private ColumnModel createColumnFromType(TypeMirror type, String columnName, String tableName) {
        String javaTypeName = type.toString();
        
        return ColumnModel.builder()
                .columnName(columnName)
                .tableName(tableName)
                .javaType(javaTypeName)
                .isNullable(true)
                .build();
    }

}
