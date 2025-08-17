package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
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
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class ElementCollectionHandler {

    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;

    public ElementCollectionHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
    }

    public void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        // 1. 컬렉션 테이블 이름 결정 및 새 EntityModel 생성
        String defaultTableName = ownerEntity.getTableName() + "_" + field.getSimpleName().toString();
        String tableName = Optional.ofNullable(field.getAnnotation(CollectionTable.class))
                .map(CollectionTable::name)
                .filter(name -> !name.isEmpty())
                .orElse(defaultTableName);

        EntityModel collectionEntity = EntityModel.builder()
                .entityName(tableName) // 테이블 이름을 고유 식별자로 사용
                .tableName(tableName)
                .tableType(EntityModel.TableType.COLLECTION_TABLE)
                .build();

        // 2. 소유자 엔티티를 가리키는 외래 키 컬럼 생성
        Optional<String> ownerPkOpt = context.findPrimaryKeyColumnName(ownerEntity);
        if (ownerPkOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner entity " + ownerEntity.getEntityName() + " must have a primary key for @ElementCollection", field);
            return;
        }
        String ownerPkName = ownerPkOpt.get();
        String ownerPkType = ownerEntity.getColumns().get(ownerPkName).getJavaType();

        // @CollectionTable의 joinColumns 설정 또는 기본값으로 FK 컬럼 이름 결정
        String fkColumnName = Optional.ofNullable(field.getAnnotation(CollectionTable.class))
                .map(CollectionTable::joinColumns)
                .filter(jc -> jc.length > 0)
                .map(jc -> jc[0].name())
                .filter(name -> !name.isEmpty())
                .orElse(ownerEntity.getTableName() + "_" + ownerPkName);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(ownerPkType)
                .isPrimaryKey(true) // 컬렉션 테이블에서는 FK가 PK의 일부가 됨
                .build();
        collectionEntity.getColumns().put(fkColumn.getColumnName(), fkColumn);

        // 3. 컬렉션의 제네릭 타입 분석 (Key, Value)
        TypeMirror fieldType = field.asType();
        if (!(fieldType instanceof DeclaredType declaredType)) return; // 제네릭 타입이 아니면 처리 불가

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
            return; // 분석할 수 없는 컬렉션 타입
        }

        // 4. Map Key 컬럼 처리
        if (isMap && keyType != null) {
            String mapKeyColumnName = Optional.ofNullable(field.getAnnotation(MapKeyColumn.class))
                    .map(MapKeyColumn::name).filter(name -> !name.isEmpty())
                    .orElse(field.getSimpleName() + "_KEY");

            // ColumnHandler를 통해 ColumnModel 생성 (단순화된 버전)
            ColumnModel keyColumn = columnHandler.createFromFieldType(field, keyType, mapKeyColumnName);
            keyColumn.setPrimaryKey(true);
            keyColumn.setMapKey(true);

            TypeMirror finalKeyType = keyType;
            Optional.ofNullable(field.getAnnotation(MapKeyEnumerated.class)).ifPresent(e -> {
                keyColumn.setEnumStringMapping(e.value() == EnumType.STRING);
                if (keyColumn.isEnumStringMapping()) {
                    keyColumn.setEnumValues(ColumnUtils.getEnumConstants(finalKeyType));
                }
            });

            Optional.ofNullable(field.getAnnotation(MapKeyTemporal.class)).ifPresent(t ->
                    keyColumn.setTemporalType(t.value())
            );

            collectionEntity.getColumns().put(keyColumn.getColumnName(), keyColumn);
        }

        // 5. Element (Value) 컬럼 처리
        Element valueElement = context.getTypeUtils().asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // 값이 Embeddable 타입인 경우
            embeddedHandler.processEmbeddableFields((TypeElement) valueElement, collectionEntity.getColumns(), collectionEntity.getRelationships(), new HashSet<>(), null, field);
        } else {
            // 값이 기본 타입인 경우
            String elementColumnName = Optional.ofNullable(field.getAnnotation(Column.class))
                    .map(Column::name).filter(name -> !name.isEmpty())
                    .orElse(field.getSimpleName().toString());

            ColumnModel elementColumn = columnHandler.createFromFieldType(field, valueType, elementColumnName);
            elementColumn.setPrimaryKey(true); // Set의 중복 방지를 위해 PK로 설정
            collectionEntity.getColumns().put(elementColumn.getColumnName(), elementColumn);
        }

        // 6. @OrderColumn 처리 (List인 경우)
        boolean isList = context.isSubtype(declaredType, "java.util.List");
        if (isList) {
            Optional.ofNullable(field.getAnnotation(OrderColumn.class)).ifPresent(orderColumn -> {
                String orderColumnName = orderColumn.name().isEmpty() ? field.getSimpleName() + "_ORDER" : orderColumn.name();
                ColumnModel orderCol = ColumnModel.builder()
                        .columnName(orderColumnName)
                        .javaType("int")
                        .isPrimaryKey(true)
                        .build();
                collectionEntity.getColumns().put(orderCol.getColumnName(), orderCol);
            });
        }

        // 7. 외래 키 관계 모델 생성
        RelationshipModel fkRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)
                .columns(List.of(fkColumn.getColumnName()))
                .referencedTable(ownerEntity.getTableName())
                .referencedColumns(List.of(ownerPkName))
                .constraintName("fk_" + tableName + "_" + fkColumn.getColumnName())
                .build();
        collectionEntity.getRelationships().add(fkRelationship);

        // 8. 완성된 컬렉션 테이블 모델을 스키마에 등록
        context.getSchemaModel().getEntities().putIfAbsent(collectionEntity.getEntityName(), collectionEntity);
    }
}
