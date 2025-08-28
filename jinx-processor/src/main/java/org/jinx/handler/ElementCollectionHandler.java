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

    /**
     * Constructs an ElementCollectionHandler with the provided processing context and helper handlers.
     *
     * Initializes internal utilities (TypeUtils and ElementUtils) from the given ProcessingContext.
     */
    public ElementCollectionHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.typeUtils = context.getTypeUtils();
        this.elementUtils = context.getElementUtils();
    }

    /**
     * Processes an @ElementCollection declared on a field by wrapping the field into a
     * FieldAttributeDescriptor and delegating to {@link #processElementCollection(AttributeDescriptor, EntityModel)}.
     *
     * @param field the field element annotated with @ElementCollection
     * @param ownerEntity the owning entity model that will contain the collection table
     */
    public void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        // Delegate to AttributeDescriptor-based method
        AttributeDescriptor fieldDescriptor = new FieldAttributeDescriptor(field, typeUtils, elementUtils);
        processElementCollection(fieldDescriptor, ownerEntity);
    }

    /**
     * Processes an @ElementCollection attribute on an entity and builds a corresponding collection table model.
     *
     * <p>Creates a collection EntityModel (table) for the attribute, adds foreign-key columns that reference
     * the owner's primary key columns, determines element (and optional map key) types, creates value/key/order
     * columns (including support for embeddable value types and Map key/temporal/enum handling), builds the
     * foreign-key relationship back to the owner, and registers the collection table in the schema.</p>
     *
     * <p>If required validations fail (for example: owner has no primary key, generic element type cannot be
     * determined, or @CollectionTable.joinColumns length mismatches owner PK count), a diagnostic error is
     * emitted and the method returns without registering the collection table.</p>
     *
     * @param attribute the attribute descriptor representing the @ElementCollection field or property; used to
     *                  read annotations, the declared generic type, and diagnostic element information
     * @param ownerEntity the owning entity model into which the collection table will reference its primary key(s)
     */
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

        // 2. 소유자 엔티티의 PK 정보 수집
        List<ColumnModel> ownerPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner entity " + ownerEntity.getEntityName() + " must have a primary key for @ElementCollection",
                    attribute.elementForDiagnostics());
            return;
        }

        // 3. @CollectionTable의 joinColumns 설정 또는 기본값으로 FK 컬럼 생성
        JoinColumn[] joinColumns = (collectionTable != null) ? collectionTable.joinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPkColumns.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@CollectionTable joinColumns size mismatch: expected " + ownerPkColumns.size() 
                    + " but got " + joinColumns.length + " on " + ownerEntity.getEntityName() + "." + attribute.name(),
                    attribute.elementForDiagnostics());
            return;
        }

        // FK 컬럼들 생성
        for (int i = 0; i < ownerPkColumns.size(); i++) {
            ColumnModel ownerPkCol = ownerPkColumns.get(i);
            JoinColumn jc = (joinColumns.length > 0) ? joinColumns[i] : null;

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
        }

        // 4. 컬렉션의 제네릭 타입 분석 (Map vs Collection)
        TypeMirror attributeType = attribute.type();
        if (!(attributeType instanceof DeclaredType declaredType)) return;

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
        Element valueElement = typeUtils.asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // 값이 Embeddable 타입인 경우
            embeddedHandler.processEmbeddableFields((TypeElement) valueElement, collectionEntity, new HashSet<>(), null, null);
        } else {
            // 값이 기본 타입인 경우
            Column columnAnnotation = attribute.getAnnotation(Column.class);
            String elementColumnName = (columnAnnotation != null && !columnAnnotation.name().isEmpty())
                    ? columnAnnotation.name()
                    : attribute.name();

            ColumnModel elementColumn = createColumnFromType(valueType, elementColumnName, tableName);
            if (elementColumn != null) {
                elementColumn.setPrimaryKey(true); // Set의 중복 방지를 위해 PK로 설정
                collectionEntity.putColumn(elementColumn);
            }
        }

        // 7. @OrderColumn 처리 (List인 경우)
        boolean isList = context.isSubtype(declaredType, "java.util.List");
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
                        .isPrimaryKey(true)
                        .isNullable(orderColumn.nullable())
                        .build();
                collectionEntity.putColumn(orderCol);
            }
        }

        // 8. 외래 키 관계 모델 생성
        List<String> fkColumnNames = ownerPkColumns.stream().map(pk -> {
            for (int i = 0; i < ownerPkColumns.size(); i++) {
                if (ownerPkColumns.get(i).equals(pk)) {
                    JoinColumn jc = (joinColumns.length > 0) ? joinColumns[i] : null;
                    return (jc != null && !jc.name().isEmpty()) 
                        ? jc.name()
                        : ownerEntity.getTableName() + "_" + pk.getColumnName();
                }
            }
            return ownerEntity.getTableName() + "_" + pk.getColumnName();
        }).toList();

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
     * Create a ColumnModel for the given type, column name, and table.
     *
     * The returned ColumnModel uses the type's string representation (type.toString())
     * as its javaType and isNullable is set to true.
     *
     * @param type the TypeMirror representing the column's Java type; its {@code toString()}
     *             value is stored in ColumnModel.javaType
     * @param columnName the column name to set on the model
     * @param tableName the table name to set on the model
     * @return a ColumnModel with javaType = {@code type.toString()}, the provided names, and nullable = true
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
