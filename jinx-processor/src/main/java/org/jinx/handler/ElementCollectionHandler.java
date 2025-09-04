package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.EntityModel;
import org.jinx.model.IndexModel;
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
import java.util.Arrays;
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
        private final List<IndexModel> pendingIndexes = new ArrayList<>();
        private final List<ConstraintModel> pendingConstraints = new ArrayList<>();
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
        
        public void addIndex(IndexModel index) {
            pendingIndexes.add(index);
        }
        
        public void addConstraint(ConstraintModel constraint) {
            pendingConstraints.add(constraint);
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
            validateIndexAndConstraintDuplicates();
            validateIndexAndConstraintColumnsExist();
        }

        /**
         * 인덱스/제약조건 중복 검증 - 이름 중복과 의미 중복 모두 체크
         */
        private void validateIndexAndConstraintDuplicates() {
            // 이름 중복 검증 (대소문자 무시)
            Set<String> names = new HashSet<>();
            
            // 기존 인덱스/제약조건 이름 수집
            if (collectionEntity != null) {
                if (collectionEntity.getIndexes() != null) {
                    for (IndexModel existing : collectionEntity.getIndexes().values()) {
                        if (existing.getIndexName() != null) {
                            names.add(existing.getIndexName().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
                if (collectionEntity.getConstraints() != null) {
                    for (ConstraintModel existing : collectionEntity.getConstraints().values()) {
                        if (existing.getName() != null) {
                            names.add(existing.getName().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            
            // 대기 중인 인덱스 이름 중복 검증
            for (IndexModel ix : pendingIndexes) {
                String k = ix.getIndexName().toLowerCase(java.util.Locale.ROOT);
                if (!names.add(k)) {
                    addError("Duplicate index name on collection table: " + ix.getIndexName());
                }
            }
            
            // 대기 중인 제약조건 이름 중복 검증
            for (ConstraintModel c : pendingConstraints) {
                String k = c.getName().toLowerCase(java.util.Locale.ROOT);
                if (!names.add(k)) {
                    addError("Duplicate constraint name on collection table: " + c.getName());
                }
            }
            
            // 의미 중복 검증 (동일 테이블+컬럼셋+유형)
            Set<String> shapes = new HashSet<>();
            
            // 기존 인덱스/제약조건 의미 중복 수집
            if (collectionEntity != null) {
                if (collectionEntity.getIndexes() != null) {
                    for (IndexModel existing : collectionEntity.getIndexes().values()) {
                        String shape = "IX|" + existing.getTableName().toLowerCase() + "|" + 
                                      String.join(",", existing.getColumnNames()).toLowerCase() + "|" + existing.isUnique();
                        shapes.add(shape);
                    }
                }
                if (collectionEntity.getConstraints() != null) {
                    for (ConstraintModel existing : collectionEntity.getConstraints().values()) {
                        String shape = existing.getType() + "|" + existing.getTableName().toLowerCase() + "|" + 
                                      String.join(",", existing.getColumns()).toLowerCase();
                        shapes.add(shape);
                    }
                }
            }
            
            // 대기 중인 인덱스 의미 중복 검증
            for (IndexModel ix : pendingIndexes) {
                String shape = "IX|" + ix.getTableName().toLowerCase() + "|" + 
                              String.join(",", ix.getColumnNames()).toLowerCase() + "|" + ix.isUnique();
                if (!shapes.add(shape)) {
                    addError("Duplicate index definition: " + ix.getIndexName());
                }
            }
            
            // 대기 중인 제약조건 의미 중복 검증
            for (ConstraintModel c : pendingConstraints) {
                String shape = c.getType() + "|" + c.getTableName().toLowerCase() + "|" + 
                              String.join(",", c.getColumns()).toLowerCase();
                if (!shapes.add(shape)) {
                    addError("Duplicate constraint definition: " + c.getName());
                }
            }
        }

        /**
         * 인덱스/제약조건이 참조하는 컬럼 존재성 검증
         */
        private void validateIndexAndConstraintColumnsExist() {
            Set<String> cols = new HashSet<>();
            
            // 대기 중인 컬럼들 수집
            for (ColumnModel cm : pendingColumns) {
                if (cm.getColumnName() != null) {
                    cols.add(cm.getColumnName().toLowerCase(java.util.Locale.ROOT));
                }
            }
            
            // 기존 컬럼들도 포함 (임베디드 선반영 등)
            if (collectionEntity != null && collectionEntity.getColumns() != null) {
                for (ColumnModel cm : collectionEntity.getColumns().values()) {
                    if (cm.getColumnName() != null) {
                        cols.add(cm.getColumnName().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            
            // 인덱스가 참조하는 컬럼 검증
            for (IndexModel ix : pendingIndexes) {
                for (String colName : ix.getColumnNames()) {
                    if (!cols.contains(colName.toLowerCase(java.util.Locale.ROOT))) {
                        addError("Index '" + ix.getIndexName() + "' refers to unknown column: " + colName);
                    }
                }
            }
            
            // 제약조건이 참조하는 컬럼 검증
            for (ConstraintModel c : pendingConstraints) {
                for (String colName : c.getColumns()) {
                    if (!cols.contains(colName.toLowerCase(java.util.Locale.ROOT))) {
                        addError("Constraint '" + c.getName() + "' refers to unknown column: " + colName);
                    }
                }
            }
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
            // 컬럼 → 인덱스 → 제약 → 관계 순으로 커밋
            
            // 모든 컬럼을 일괄 추가
            for (ColumnModel column : pendingColumns) {
                collectionEntity.putColumn(column);
            }
            
            // 모든 인덱스를 일괄 추가
            for (IndexModel ix : pendingIndexes) {
                collectionEntity.getIndexes().put(ix.getIndexName(), ix);
            }
            
            // 모든 제약조건을 일괄 추가
            for (ConstraintModel c : pendingConstraints) {
                collectionEntity.getConstraints().put(c.getName(), c);
            }

            // 모든 관계를 일괄 추가
            for (RelationshipModel relationship : pendingRelationships) {
                collectionEntity.getRelationships().put(relationship.getConstraintName(), relationship);
            }
            
            // 커밋 완료 플래그 설정
            committed = true;
        }
    }

    /**
     * TypeMirror 기반의 synthetic AttributeDescriptor 구현
     * @Embeddable 값 타입을 EmbeddedHandler에 전달할 때 사용
     */
    private static class SyntheticTypeAttributeDescriptor implements AttributeDescriptor {
        private final String name;
        private final TypeMirror type;
        private final Element elementForDiagnostics;

        SyntheticTypeAttributeDescriptor(String name, TypeMirror type, Element elementForDiagnostics) {
            this.name = name;
            this.type = type;
            this.elementForDiagnostics = elementForDiagnostics;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TypeMirror type() {
            return type;
        }

        @Override
        public boolean isCollection() {
            return false;
        }

        @Override
        public java.util.Optional<DeclaredType> genericArg(int idx) {
            return java.util.Optional.empty();
        }

        @Override
        public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationClass) {
            return null;
        }

        @Override
        public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> annotationClass) {
            return false;
        }

        @Override
        public Element elementForDiagnostics() {
            return elementForDiagnostics;
        }

        @Override
        public AccessKind accessKind() {
            return AccessKind.FIELD;
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

        // 1-1. @CollectionTable의 indexes/uniqueConstraints 반영
        if (collectionTable != null) {
            var adapter = new org.jinx.handler.builtins.CollectionTableAdapter(collectionTable, context, tableName);
            
            for (var ix : adapter.getIndexes()) {
                result.addIndex(ix);
            }
            for (var c : adapter.getConstraints()) {
                result.addConstraint(c);
            }
        }

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
                : context.getNaming().foreignKeyColumnName(ownerEntity.getTableName(), ownerPkCol.getColumnName());
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
        if (isMap) {
            // @MapKeyClass나 @MapKey에 의해 키 타입이 재정의될 수 있음
            TypeMirror actualKeyType = resolveMapKeyType(attribute, keyType);
            
            if (actualKeyType != null) {
                // 키 타입이 엔티티인지 확인
                Element keyElement = typeUtils.asElement(actualKeyType);
                boolean isEntityKey = keyElement != null && keyElement.getAnnotation(Entity.class) != null;
                
                if (isEntityKey) {
                    // @MapKeyJoinColumn(s)로 엔티티 키 처리
                    processMapKeyJoinColumns(attribute, actualKeyType, tableName, result);
                } else {
                    // 기본 타입 키 처리 (@MapKeyColumn)
                    MapKeyColumn mapKeyColumn = attribute.getAnnotation(MapKeyColumn.class);
                    String mapKeyColumnName = (mapKeyColumn != null && !mapKeyColumn.name().isEmpty())
                            ? mapKeyColumn.name()
                            : attribute.name() + "_KEY";

                    ColumnModel keyColumn = createColumnFromType(actualKeyType, mapKeyColumnName, tableName);
                    if (keyColumn != null) {
                        keyColumn.setPrimaryKey(true);
                        keyColumn.setMapKey(true);
                        keyColumn.setNullable(false);

                        // @MapKeyColumn 메타데이터 반영 (PK 규칙 우선)
                        if (mapKeyColumn != null) {
                            keyColumn.setNullable(false); // PK는 항상 NOT NULL (nullable 요청 무시)
                            if (mapKeyColumn.length() != 255) { // 기본값이 아닌 경우만
                                keyColumn.setLength(mapKeyColumn.length());
                            }
                            if (mapKeyColumn.precision() != 0) {
                                keyColumn.setPrecision(mapKeyColumn.precision());
                            }
                            if (mapKeyColumn.scale() != 0) {
                                keyColumn.setScale(mapKeyColumn.scale());
                            }
                            if (!mapKeyColumn.columnDefinition().isEmpty()) {
                                keyColumn.setSqlTypeOverride(mapKeyColumn.columnDefinition());
                            }
                        }

                        // Map Key 특수 어노테이션 처리
                        processMapKeyAnnotations(attribute, keyColumn, actualKeyType);

                        result.addColumn(keyColumn); // 즉시 putColumn 대신 검증 결과에 추가
                    }
                }
            }
        }

        // 6. Element (Value) 컬럼 처리 - 메모리상에서만 생성
        boolean isList = context.isSubtype(declaredType, "java.util.List");
        
        Element valueElement = typeUtils.asElement(valueType);
        if (valueElement != null && valueElement.getAnnotation(Embeddable.class) != null) {
            // 값이 Embeddable 타입인 경우 - 현재는 임시 EntityModel에 직접 추가
            // TODO: EmbeddedHandler도 2단계 패턴으로 변경 후 검증 결과 통합 필요
            AttributeDescriptor valueAttribute = new SyntheticTypeAttributeDescriptor(
                attribute.name() + "_value",   // 적절한 진단용/로깅용 이름
                valueType,                     // DeclaredType 등 실제 값 타입
                attribute.elementForDiagnostics()
            );
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
     * Map 키가 엔티티인 경우 @MapKeyJoinColumn(s) 처리
     */
    private void processMapKeyJoinColumns(AttributeDescriptor attribute, TypeMirror keyType, 
                                        String tableName, ValidationResult result) {
        // 키 엔티티의 PK 컬럼들 조회
        Element keyElement = typeUtils.asElement(keyType);
        if (keyElement == null || !(keyElement instanceof TypeElement keyTypeElement)) {
            result.addError("Cannot resolve key entity type for @MapKeyJoinColumn");
            return;
        }

        // 키 엔티티의 EntityModel을 스키마에서 찾아서 PK 컬럼들 수집 (FQCN 사용)
        String keyFqcn = keyTypeElement.getQualifiedName().toString();
        EntityModel keyEntity = context.getSchemaModel().getEntities().get(keyFqcn);
        if (keyEntity == null) {
            result.addError("Key entity " + keyFqcn + " not found in schema for @MapKeyJoinColumn");
            return;
        }

        List<ColumnModel> keyPkColumns = context.findAllPrimaryKeyColumns(keyEntity);
        if (keyPkColumns.isEmpty()) {
            result.addError("Key entity " + keyTypeElement.getSimpleName() + " has no primary key for @MapKeyJoinColumn");
            return;
        }

        // @MapKeyJoinColumn(s) 어노테이션 처리
        MapKeyJoinColumn[] mapKeyJoinColumns = getMapKeyJoinColumns(attribute);
        
        if (mapKeyJoinColumns.length > 0 && mapKeyJoinColumns.length != keyPkColumns.size()) {
            result.addError("@MapKeyJoinColumn size mismatch: expected " + keyPkColumns.size() 
                + " but got " + mapKeyJoinColumns.length + " for key entity " + keyTypeElement.getSimpleName());
            return;
        }

        // 키 FK 컬럼들 생성 (이름 목록과 동시에 수집)
        Map<String, ColumnModel> keyPkByName = new HashMap<>();
        for (ColumnModel pk : keyPkColumns) {
            keyPkByName.put(pk.getColumnName(), pk);
        }

        List<String> keyFkColumnNames = new ArrayList<>();
        Set<String> usedReferencedColumns = new HashSet<>();
        for (int i = 0; i < keyPkColumns.size(); i++) {
            MapKeyJoinColumn mkjc = (mapKeyJoinColumns.length > 0) ? mapKeyJoinColumns[i] : null;
            ColumnModel keyPkCol;
            
            if (mkjc != null && !mkjc.referencedColumnName().isEmpty()) {
                keyPkCol = keyPkByName.get(mkjc.referencedColumnName());
                if (keyPkCol == null) {
                    result.addError("MapKeyJoinColumn.referencedColumnName not found in key entity PKs: " + mkjc.referencedColumnName());
                    return;
                }
                // 중복 referencedColumnName 검출
                if (!usedReferencedColumns.add(keyPkCol.getColumnName())) {
                    result.addError("Duplicate MapKeyJoinColumn.referencedColumnName: " + keyPkCol.getColumnName());
                    return;
                }
            } else {
                keyPkCol = keyPkColumns.get(i);
                // 기본 매핑에서도 중복 방지
                if (!usedReferencedColumns.add(keyPkCol.getColumnName())) {
                    result.addError("Duplicate key PK column reference: " + keyPkCol.getColumnName());
                    return;
                }
            }

            String keyFkColumnName = (mkjc != null && !mkjc.name().isEmpty())
                ? mkjc.name()
                : context.getNaming().foreignKeyColumnName(attribute.name() + "_KEY", keyPkCol.getColumnName());

            ColumnModel keyFkColumn = ColumnModel.builder()
                    .columnName(keyFkColumnName)
                    .tableName(tableName)
                    .javaType(keyPkCol.getJavaType())
                    .isPrimaryKey(true)  // Map 키 FK는 컬렉션 테이블 PK의 일부
                    .isNullable(false)
                    .isMapKey(true)
                    .build();
            
            result.addColumn(keyFkColumn);
            keyFkColumnNames.add(keyFkColumnName);  // 생성한 이름을 즉시 리스트에 추가
        }

        List<String> keyPkColumnNames = keyPkColumns.stream().map(ColumnModel::getColumnName).toList();
        RelationshipModel keyRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)  // Map key FK도 ElementCollection의 일부
                .tableName(tableName)
                .columns(keyFkColumnNames)
                .referencedTable(keyEntity.getTableName())
                .referencedColumns(keyPkColumnNames)
                .constraintName(context.getNaming().fkName(tableName, keyFkColumnNames, 
                    keyEntity.getTableName(), keyPkColumnNames))
                .build();
        
        result.addRelationship(keyRelationship);
    }

    /**
     * @MapKeyClass, @MapKey 어노테이션을 고려하여 실제 Map 키 타입을 결정
     */
    private TypeMirror resolveMapKeyType(AttributeDescriptor attribute, TypeMirror defaultKeyType) {
        // 1) @MapKeyClass 우선순위가 높음
        MapKeyClass mapKeyClass = attribute.getAnnotation(MapKeyClass.class);
        if (mapKeyClass != null) {
            TypeElement keyClassElement = classValToTypeElement(() -> mapKeyClass.value());
            if (keyClassElement != null) {
                return keyClassElement.asType();
            }
        }
        
        // 2) @MapKey는 특별한 처리 (키 필드명 지정)
        MapKey mapKey = attribute.getAnnotation(MapKey.class);
        if (mapKey != null && !mapKey.name().isEmpty()) {
            // @MapKey(name="fieldName")은 값 엔티티의 특정 필드를 키로 사용
            // 여기서는 타입 정보만 필요하므로 defaultKeyType 반환
            // 실제 컬럼명은 processMapKeyAnnotations에서 처리
            return defaultKeyType;
        }
        
        // 3) 기본값 사용 (제네릭 타입 인자)
        return defaultKeyType;
    }
    
    /**
     * Map Key 관련 어노테이션들을 처리하여 ColumnModel 설정
     */
    private void processMapKeyAnnotations(AttributeDescriptor attribute, ColumnModel keyColumn, TypeMirror keyType) {
        // @MapKey 처리 - 키 필드명 지정
        MapKey mapKey = attribute.getAnnotation(MapKey.class);
        if (mapKey != null && !mapKey.name().isEmpty()) {
            // @MapKey(name="fieldName")의 경우 컬럼명을 해당 필드명으로 재설정
            keyColumn.setColumnName(mapKey.name());
        }
        
        // @MapKeyEnumerated 처리
        MapKeyEnumerated mapKeyEnumerated = attribute.getAnnotation(MapKeyEnumerated.class);
        if (mapKeyEnumerated != null) {
            keyColumn.setEnumStringMapping(mapKeyEnumerated.value() == EnumType.STRING);
            if (keyColumn.isEnumStringMapping()) {
                keyColumn.setEnumValues(ColumnUtils.getEnumConstants(keyType));
            }
        }

        // @MapKeyTemporal 처리
        MapKeyTemporal mapKeyTemporal = attribute.getAnnotation(MapKeyTemporal.class);
        if (mapKeyTemporal != null) {
            keyColumn.setTemporalType(mapKeyTemporal.value());
        }
    }

    /**
     * @MapKeyJoinColumn 또는 @MapKeyJoinColumns 어노테이션에서 배열 추출
     */
    private MapKeyJoinColumn[] getMapKeyJoinColumns(AttributeDescriptor attribute) {
        MapKeyJoinColumns mapKeyJoinColumns = attribute.getAnnotation(MapKeyJoinColumns.class);
        if (mapKeyJoinColumns != null) {
            return mapKeyJoinColumns.value();
        }
        
        MapKeyJoinColumn mapKeyJoinColumn = attribute.getAnnotation(MapKeyJoinColumn.class);
        if (mapKeyJoinColumn != null) {
            return new MapKeyJoinColumn[]{mapKeyJoinColumn};
        }
        
        return new MapKeyJoinColumn[0];
    }
    
    /**
     * 클래스값(annotation)에서 TypeElement를 안전하게 추출합니다.
     * APT 환경에서 MirroredTypeException을 적절히 처리합니다.
     */
    private TypeElement classValToTypeElement(java.util.function.Supplier<Class<?>> getter) {
        try {
            Class<?> clz = getter.get();
            if (clz == void.class) return null;
            return context.getElementUtils().getTypeElement(clz.getName());
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            TypeMirror typeMirror = mte.getTypeMirror();
            if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
                return (TypeElement) declaredType.asElement();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
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
