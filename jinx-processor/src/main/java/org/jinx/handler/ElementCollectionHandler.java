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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ElementCollectionHandler {

    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final Types typeUtils;
    private final Elements elementUtils;

    /**
     * 2단계 검증→커밋 패턴을 위한 검증 결과를 담는 내부 클래스
     */
    private static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<ColumnModel> pendingColumns = new ArrayList<>();
        private final List<RelationshipModel> pendingRelationships = new ArrayList<>();
        private EntityModel collectionEntity;
        private boolean committed = false; // 재진입 방지 플래그

        public void addError(String error) {
            errors.add(error);
        }

        public void addColumn(ColumnModel column) {
            pendingColumns.add(column);
        }

        public void addRelationship(RelationshipModel relationship) {
            pendingRelationships.add(relationship);
        }

        public void setCollectionEntity(EntityModel entity) {
            this.collectionEntity = entity;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean isCommitted() {
            return committed;
        }

        /**
         * 컬럼명 중복 검증 - FK, Map Key, Value, Order 컬럼 간 충돌 체크 + 기존 컬럼과의 충돌
         */
        private void validateColumnNameDuplicates() {
            Set<String> columnNames = new HashSet<>();
            
            // 이미 collectionEntity에 존재하는 컬럼과의 충돌도 포함 (Embeddable 경로 등)
            if (collectionEntity != null && collectionEntity.getColumns() != null) {
                for (ColumnModel existing : collectionEntity.getColumns().values()) {
                    if (existing.getColumnName() != null) {
                        String normalizedName = existing.getColumnName().trim().toLowerCase(java.util.Locale.ROOT);
                        columnNames.add(normalizedName);
                    }
                }
            }
            
            // 대기 중인 컬럼들 검증
            for (ColumnModel column : pendingColumns) {
                String columnName = column.getColumnName();
                if (columnName == null || columnName.isBlank()) {
                    addError("Column name cannot be null/blank in collection table: " + 
                            (collectionEntity != null ? collectionEntity.getTableName() : "unknown"));
                    continue;
                }
                
                String normalizedName = columnName.trim().toLowerCase(java.util.Locale.ROOT);
                if (!columnNames.add(normalizedName)) {
                    addError("Duplicate column name in collection table: " + columnName);
                }
            }
        }

        /**
         * 제약명 중복 검증 - 관계(FK) 제약명 충돌 체크 + 기존 관계와의 충돌
         */
        private void validateConstraintNameDuplicates() {
            Set<String> constraintNames = new HashSet<>();
            
            // 이미 collectionEntity에 존재하는 관계와의 충돌도 포함 (Embeddable/다른 경로 등)
            if (collectionEntity != null && collectionEntity.getRelationships() != null) {
                for (RelationshipModel existing : collectionEntity.getRelationships().values()) {
                    if (existing.getConstraintName() != null && !existing.getConstraintName().trim().isEmpty()) {
                        String normalizedName = existing.getConstraintName().trim().toLowerCase(java.util.Locale.ROOT);
                        constraintNames.add(normalizedName);
                    }
                }
            }
            
            // 대기 중인 관계들 검증
            for (RelationshipModel relationship : pendingRelationships) {
                String constraintName = relationship.getConstraintName();
                if (constraintName == null || constraintName.trim().isEmpty()) {
                    addError("Constraint name cannot be null or empty for relationship: " + relationship.getTableName());
                    continue;
                }
                
                // 대소문자 구분 없는 중복 검증 (DB는 보통 대소문자 구분 안 함)
                String normalizedName = constraintName.trim().toLowerCase(java.util.Locale.ROOT);
                if (!constraintNames.add(normalizedName)) {
                    addError("Duplicate constraint name in collection table: " + constraintName);
                }
            }
        }

        /**
         * 최종 중복 검증 수행 (validate 단계 말미에서 호출)
         */
        public void performFinalValidation() {
            validateColumnNameDuplicates();
            validateConstraintNameDuplicates();
        }

        /**
         * 순수 커밋 - 예외 없이 모델에 반영만 수행 (재진입 방지)
         */
        public void commitToModel() {
            // 재진입 방지: 이미 커밋된 경우 무시
            if (committed) {
                return;
            }
            
            // 검증 완료 상태에서만 호출되므로 예외 없음
            // 모든 컬럼을 일괄 추가
            for (ColumnModel column : pendingColumns) {
                collectionEntity.putColumn(column);
            }

            // 모든 관계를 일괄 추가
            for (RelationshipModel relationship : pendingRelationships) {
                collectionEntity.getRelationships().put(relationship.getConstraintName(), relationship);
            }
            
            // 커밋 완료 플래그 설정
            committed = true;
        }
    }

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
        // ========== 1단계: 검증 (메모리 상에서만 생성) ==========
        ValidationResult validation = validateElementCollection(attribute, ownerEntity);

        // ========== 2단계: 커밋 (오류 없을 경우만) ==========
        if (validation.hasErrors()) {
            // 모든 검증 오류를 APT 메시지로 출력
            for (String error : validation.getErrors()) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR, error, attribute.elementForDiagnostics());
            }
            return; // 부분 생성 없이 완전 롤백
        }

        // 검증 성공: 모델에 일괄 커밋 (예외 없음, 순수 커밋)
        validation.commitToModel();
        
        // 완성된 컬렉션 테이블 모델을 스키마에 등록
        context.getSchemaModel().getEntities().putIfAbsent(
            validation.collectionEntity.getEntityName(), 
            validation.collectionEntity
        );
    }

    /**
     * 1단계: 검증 단계 - 모든 컬렉션 구성요소를 메모리상에서만 생성하고 검증
     */
    private ValidationResult validateElementCollection(AttributeDescriptor attribute, EntityModel ownerEntity) {
        ValidationResult result = new ValidationResult();

        // 0. 기본 값 null/blank 검증 - NPE 방어
        if (ownerEntity.getTableName() == null || ownerEntity.getTableName().isBlank()) {
            result.addError("Owner entity has no tableName; cannot derive collection table name for @ElementCollection on " + attribute.name());
            return result;
        }
        if (attribute.name() == null || attribute.name().isBlank()) {
            result.addError("Attribute name is blank for @ElementCollection");
            return result;
        }

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
        result.setCollectionEntity(collectionEntity);

        // 2. 컬렉션의 제네릭 타입 분석 (Map vs Collection) - FK 생성 전 검증
        TypeMirror attributeType = attribute.type();
        if (!(attributeType instanceof DeclaredType declaredType)) {
            result.addError("Cannot determine collection type for @ElementCollection");
            return result;
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
            result.addError("Cannot determine collection element type for @ElementCollection");
            return result;
        }

        // 3. 소유자 엔티티의 PK 정보 수집
        List<ColumnModel> ownerPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkColumns.isEmpty()) {
            result.addError("Owner entity " + ownerEntity.getEntityName() + " must have a primary key for @ElementCollection");
            return result;
        }

        // 4. @CollectionTable의 joinColumns 설정 또는 기본값으로 FK 컬럼 생성
        JoinColumn[] joinColumns = (collectionTable != null) ? collectionTable.joinColumns() : new JoinColumn[0];

        if (joinColumns.length > 0 && joinColumns.length != ownerPkColumns.size()) {
            result.addError("@CollectionTable joinColumns size mismatch: expected " + ownerPkColumns.size() 
                + " but got " + joinColumns.length + " on " + ownerEntity.getEntityName() + "." + attribute.name());
            return result;
        }

        // FK 컬럼들 생성 (referencedColumnName 우선 매핑) - 메모리상에서만 생성
        java.util.List<String> fkColumnNames = new java.util.ArrayList<>();
        java.util.Map<String, ColumnModel> ownerPkByName = new java.util.HashMap<>();
        for (ColumnModel pk : ownerPkColumns) ownerPkByName.put(pk.getColumnName(), pk);
        for (int i = 0; i < ownerPkColumns.size(); i++) {
            JoinColumn jc = (joinColumns.length > 0) ? joinColumns[i] : null;
            ColumnModel ownerPkCol;
            if (jc != null && !jc.referencedColumnName().isEmpty()) {
                ownerPkCol = ownerPkByName.get(jc.referencedColumnName());
                if (ownerPkCol == null) {
                    result.addError("referencedColumnName not found in owner PKs: " + jc.referencedColumnName());
                    return result;
                }
            } else {
                ownerPkCol = ownerPkColumns.get(i);
            }
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
            result.addColumn(fkColumn); // 즉시 putColumn 대신 검증 결과에 추가
            fkColumnNames.add(fkColumnName);
        }

        // 5. Map Key 컬럼 처리 - 메모리상에서만 생성
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

                result.addColumn(keyColumn); // 즉시 putColumn 대신 검증 결과에 추가
            }
        }

        // 6. Element (Value) 컬럼 처리 - 메모리상에서만 생성
        boolean isList = context.isSubtype(declaredType, "java.util.List");
        
        Element valueElement = typeUtils.asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // 값이 Embeddable 타입인 경우 - 현재는 임시 EntityModel에 직접 추가
            // TODO: EmbeddedHandler도 2단계 패턴으로 변경 후 검증 결과 통합 필요
            AttributeDescriptor valueAttribute = new FieldAttributeDescriptor((VariableElement) valueElement, typeUtils, elementUtils);
            embeddedHandler.processEmbedded(valueAttribute, collectionEntity, new HashSet<>());
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
                result.addColumn(elementColumn); // 즉시 putColumn 대신 검증 결과에 추가
            }
        }

        // 7. @OrderColumn 처리 (List인 경우) - 메모리상에서만 생성
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
                result.addColumn(orderCol); // 즉시 putColumn 대신 검증 결과에 추가
            }
        }

        // 8. 외래 키 관계 모델 생성 - 메모리상에서만 생성
        List<String> ownerPkNames = ownerPkColumns.stream().map(ColumnModel::getColumnName).toList();

        RelationshipModel fkRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)
                .tableName(tableName)
                .columns(fkColumnNames)
                .referencedTable(ownerEntity.getTableName())
                .referencedColumns(ownerPkNames)
                .constraintName(context.getNaming().fkName(tableName, fkColumnNames, ownerEntity.getTableName(), ownerPkNames))
                .build();
        result.addRelationship(fkRelationship);

        // 9. 최종 중복 검증 (임베디드로 선반영된 컬럼/관계 포함)
        result.performFinalValidation();
        
        return result;
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
