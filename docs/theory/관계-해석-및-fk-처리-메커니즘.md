# Jinx 관계 해석 및 FK 처리 메커니즘

이 문서는 Jinx의 관계 메타모델 추출 파이프라인과 외래키(FK) 처리 메커니즘에 대한 상세한 분석을 다룹니다. Jinx는 컴파일 타임에 JPA 관계 어노테이션을 정적으로 분석하여 어느 쪽이 소유자(owning side)인지, 어떤 컬럼들이 FK인지, 조인 전략 등을 확정합니다.

## 목차

1. [관계 메타모델 추출 파이프라인 개요](#관계-메타모델-추출-파이프라인-개요)
2. [RelationshipHandler 아키텍처](#relationshiphandler-아키텍처)
3. [소유자(Owning Side) 결정 로직](#소유자-owning-side-결정-로직)
4. [조인 전략 및 FK 컬럼 매핑](#조인-전략-및-fk-컬럼-매핑)
5. [RelationshipProcessor 구현체들](#relationshipprocessor-구현체들)
6. [@MapsId 처리 메커니즘](#mapsid-처리-메커니즘)
7. [관계별 DDL 생성 전략](#관계별-ddl-생성-전략)

## 관계 메타모델 추출 파이프라인 개요

### 파이프라인 목표
컴파일 타임에 다음 정보를 정적으로 확정:
- **어느 쪽이 오너(owning side)인지**
- **어떤 컬럼들이 FK인지**
- **조인 전략/컬렉션 테이블/조인 테이블 필요 여부**

### 전체 처리 흐름

```java
// EntityHandler.java에서 관계 처리 호출
public void handle(TypeElement type) {
    // ... 엔티티 기본 처리 후

    // 6. 관계 해석
    relationshipHandler.resolveRelationships(type, entity);

    // 7. @MapsId 지연 처리 (모든 관계/컬럼 생성 후)
    relationshipHandler.processMapsIdAttributes(type, entity);
}
```

### 1단계: 관계 후보 수집

`RelationshipHandler.resolveRelationships()`는 엔티티의 AccessType에 따라 필드 또는 프로퍼티를 스캔:

```java
public void resolveRelationships(TypeElement ownerType, EntityModel ownerEntity) {
    // 1) 캐시된 AttributeDescriptor 우선 사용
    List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
    if (descriptors != null && !descriptors.isEmpty()) {
        for (AttributeDescriptor d : descriptors) {
            resolve(d, ownerEntity);
        }
        return;
    }

    // 2) 캐시 미스 시 AccessType 기반 스캔
    AccessType accessType = AccessUtils.determineAccessType(ownerType);
    if (accessType == AccessType.FIELD) {
        scanFieldsForRelationships(ownerType, ownerEntity);
    } else {
        scanPropertiesForRelationships(ownerType, ownerEntity);
    }
}
```

**관계 어노테이션 탐지 대상:**
```java
private boolean hasRelationshipAnnotation(Element element) {
    return element.getAnnotation(ManyToOne.class) != null ||
           element.getAnnotation(OneToOne.class) != null ||
           element.getAnnotation(OneToMany.class) != null ||
           element.getAnnotation(ManyToMany.class) != null;
}
```

### 2단계: 프로세서 체인 적용

각 관계 후보는 우선순위에 따라 정렬된 `RelationshipProcessor` 체인을 통과:

```java
// RelationshipHandler 초기화
this.processors = Arrays.asList(
    new InverseRelationshipProcessor(context, relationshipSupport),    // order: 0
    new ToOneRelationshipProcessor(context),                          // order: 10
    new OneToManyOwningFkProcessor(context, relationshipSupport),     // order: 20
    new OneToManyOwningJoinTableProcessor(context, ..., ...),         // order: 30
    new ManyToManyOwningProcessor(context, ..., ...)                  // order: 40
);
processors.sort(Comparator.comparing(p -> p.order()));
```

## RelationshipHandler 아키텍처

### 핵심 컴포넌트

1. **RelationshipHandler**: 메인 조정자
2. **RelationshipProcessor**: 각 관계 유형별 처리 인터페이스
3. **RelationshipSupport**: 공통 유틸리티 및 타겟 엔티티 해석
4. **RelationshipJoinSupport**: 조인 테이블 관련 지원

### 처리자 선택 메커니즘

```java
public void resolve(AttributeDescriptor descriptor, EntityModel entityModel) {
    boolean handled = false;
    for (RelationshipProcessor p : processors) {
        if (p.supports(descriptor)) {
            p.process(descriptor, entityModel);
            handled = true;
            break;
        }
    }
    if (!handled && hasRelationshipAnnotation(descriptor)) {
        // 에러 처리: 어떤 프로세서도 처리할 수 없는 관계
    }
}
```

## 소유자(Owning Side) 결정 로직

### 기본 원칙

1. **mappedBy 있으면 inverse side** (DDL 생성 안함)
2. **mappedBy 없으면 owning side** (DDL 생성)
3. **@ManyToMany는 항상 하나만 owning** (mappedBy 없는 쪽)
4. **@OneToOne은 둘 다 가능**하지만 명시적 @JoinColumn 있는 쪽이 owning

### 구체적 구현

#### InverseRelationshipProcessor (order: 0 - 최우선)
```java
public boolean supports(AttributeDescriptor descriptor) {
    OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
    if (oneToMany != null && !oneToMany.mappedBy().isEmpty()) {
        return true; // inverse side
    }

    ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
    if (manyToMany != null && !manyToMany.mappedBy().isEmpty()) {
        return true; // inverse side
    }

    OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
    if (oneToOne != null && !oneToOne.mappedBy().isEmpty()) {
        return true; // inverse side
    }

    return false;
}
```

#### ToOneRelationshipProcessor (order: 10)
```java
public boolean supports(AttributeDescriptor descriptor) {
    ManyToOne manyToOne = descriptor.getAnnotation(ManyToOne.class);
    OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);

    // ManyToOne은 항상 owning side (mappedBy 속성 자체가 없음)
    if (manyToOne != null) {
        return true;
    }

    // OneToOne은 mappedBy가 없는 경우만 (owning side)
    if (oneToOne != null && oneToOne.mappedBy().isEmpty()) {
        return true;
    }

    return false;
}
```

### mappedBy 순환 참조 방지

```java
private AttributeDescriptor findMappedByAttribute(String ownerEntityName, String targetEntityName, String mappedByAttributeName) {
    // 무한 재귀 검사
    if (context.isMappedByVisited(targetEntityName, mappedByAttributeName)) {
        context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Detected cyclic mappedBy reference: " + ownerEntityName + " -> " +
                        targetEntityName + "." + mappedByAttributeName + ". Breaking cycle to prevent infinite recursion.");
        return null;
    }

    context.markMappedByVisited(targetEntityName, mappedByAttributeName);
    try {
        // mappedBy 속성 찾기 로직
    } finally {
        context.unmarkMappedByVisited(targetEntityName, mappedByAttributeName);
    }
}
```

## 조인 전략 및 FK 컬럼 매핑

### 조인 전략 확정 규칙

1. **단방향 @ManyToOne / 양방향 @OneToMany → FK는 Many 쪽 테이블**
2. **양방향 @OneToOne → FK는 owning 쪽 테이블** (보통 unique FK)
3. **@ManyToMany → 조인 테이블 생성**
4. **@ElementCollection → 컬렉션 테이블 생성**

### FK 컬럼 매핑 전략

#### 1. ToOne 관계 (ManyToOne, OneToOne)

```java
// ToOneRelationshipProcessor.process()에서
for (int i = 0; i < refPkList.size(); i++) {
    JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);

    String referencedPkName = (jc != null && !jc.referencedColumnName().isEmpty())
            ? jc.referencedColumnName() : refPkList.get(i).getColumnName();

    // FK 컬럼명 생성: 속성명 + 참조 PK명
    String fkColumnName = (jc != null && !jc.name().isEmpty())
            ? jc.name()
            : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);

    // nullable 결정
    boolean associationOptional = (manyToOne != null) ? manyToOne.optional() : oneToOne.optional();
    boolean columnNullableFromAnno = (jc != null) ? jc.nullable() : associationOptional;
    boolean isNullable = associationOptional && columnNullableFromAnno;

    // FK 컬럼 생성
    ColumnModel fkColumn = ColumnModel.builder()
            .columnName(fkColumnName)
            .tableName(tableNameForFk)
            .javaType(referencedPkColumn.getJavaType())
            .isPrimaryKey(false) // PK 승격은 MapsId 후처리에서
            .isNullable(isNullable)
            .build();
}
```

#### 2. OneToMany FK 전략

`OneToManyOwningFkProcessor`는 FK 전략을 사용하는 OneToMany 관계를 처리:

```java
public boolean supports(AttributeDescriptor descriptor) {
    OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
    if (oneToMany == null || !oneToMany.mappedBy().isEmpty()) {
        return false; // inverse side는 처리 안함
    }

    // OneToMany FK 전략: 반드시 JoinColumn이 있어야 하고, JoinTable은 없어야 함
    JoinTable jt = descriptor.getAnnotation(JoinTable.class);
    JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
    JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
    boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);

    return hasJoinColumn && jt == null;
}
```

#### 3. ManyToMany 조인 테이블 전략

```java
public boolean supports(AttributeDescriptor descriptor) {
    ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
    return manyToMany != null && manyToMany.mappedBy().isEmpty(); // owning side만
}
```

### 컬럼 매핑 해석 순서

1. **@JoinColumn(s) 명시적 설정 우선**
2. **명시 없으면 네이밍 규칙으로 디폴트 생성**
3. **타입 정합성 검증** (참조 PK와 FK 타입 일치)
4. **제약조건 생성** (FK 제약, unique 제약, 인덱스)

## RelationshipProcessor 구현체들

### 1. InverseRelationshipProcessor (order: 0)

**역할**: mappedBy가 있는 inverse side 관계 처리 (DDL 생성 안함)

```java
public void process(AttributeDescriptor descriptor, EntityModel ownerEntity) {
    // Inverse side: DDL artifacts 생성하지 않음
    // 단지 논리적 관계 추적 및 검증만 수행
    String mappedBy = annotation.mappedBy();

    // 타겟 엔티티의 mappedBy 속성 존재 검증
    AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntityName, targetEntityName, mappedBy);
    if (mappedByAttr == null) {
        context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Cannot find mappedBy attribute '" + mappedBy + "' on target entity");
    }
}
```

### 2. ToOneRelationshipProcessor (order: 10)

**역할**: @ManyToOne 및 owning side @OneToOne 처리

**주요 기능:**
- FK 컬럼 생성
- 복합키 지원
- @MapsId 준비 (실제 PK 승격은 후처리에서)
- unique 제약 추가 (@OneToOne의 경우)
- FK 인덱스 자동 생성

### 3. OneToManyOwningFkProcessor (order: 20)

**역할**: FK 전략을 사용하는 OneToMany owning side 처리

**식별 조건:**
- `@OneToMany`이고 `mappedBy` 없음 (owning side)
- `@JoinColumn` 있음
- `@JoinTable` 없음

### 4. OneToManyOwningJoinTableProcessor (order: 30)

**역할**: 조인 테이블 전략을 사용하는 OneToMany owning side 처리

**식별 조건:**
- `@OneToMany`이고 `mappedBy` 없음
- `@JoinTable` 있거나, `@JoinColumn` 없음 (디폴트로 조인 테이블 사용)

### 5. ManyToManyOwningProcessor (order: 40)

**역할**: ManyToMany owning side 처리 (항상 조인 테이블 사용)

**주요 기능:**
- 조인 테이블 생성
- 양쪽 엔티티 PK를 참조하는 FK 컬럼들 생성
- 복합 PK 지원

## @MapsId 처리 메커니즘

### 처리 시점 및 이유

`@MapsId`는 모든 관계와 컬럼이 생성된 후 별도 패스에서 처리됩니다:

```java
// EntityHandler.java
public void handle(TypeElement type) {
    // ... 일반 관계 처리 후

    // 7. @MapsId 지연 처리 패스
    relationshipHandler.processMapsIdAttributes(type, entity);
}
```

**지연 처리 이유:**
1. FK 컬럼이 먼저 생성되어야 PK로 승격 가능
2. 복합키 구조 파악이 필요
3. 중복 PK 컬럼 제거 로직 실행

### @MapsId 처리 로직

#### 1. 전체 PK 공유 (@MapsId without value)

```java
private void processFullPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity,
                                         RelationshipModel relationship, List<String> fkColumns,
                                         List<ColumnModel> ownerPkCols, String keyPath) {
    if (fkColumns.size() != ownerPkCols.size()) {
        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "@MapsId without value must map all PK columns. expected=" + ownerPkCols.size()
                + ", found=" + fkColumns.size());
        return;
    }

    // PK 승격 및 매핑 기록
    ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
    recordMapsIdBindings(relationship, fkColumns, ownerPkColumnNames, keyPath);
}
```

#### 2. 부분 PK 매핑 (@MapsId("keyPath"))

```java
private void processPartialPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity,
                                            RelationshipModel relationship, List<String> fkColumns,
                                            List<ColumnModel> ownerPkCols, String keyPath) {
    // @EmbeddedId에서 특정 속성에 해당하는 컬럼들 찾기
    List<String> ownerPkAttrColumns = findPkColumnsForAttribute(ownerEntity, keyPath, descriptor);

    if (fkColumns.size() != ownerPkAttrColumns.size()) {
        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "@MapsId(\"" + keyPath + "\") column count mismatch. expected=" + ownerPkAttrColumns.size()
                + ", found=" + fkColumns.size());
        return;
    }

    ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
    recordMapsIdBindings(relationship, fkColumns, ownerPkAttrColumns, keyPath);
}
```

#### 3. PK 승격 및 중복 제거

```java
private void ensureAllArePrimaryKeys(EntityModel ownerEntity, String tableName, List<String> columnNames, AttributeDescriptor descriptor) {
    for (String columnName : columnNames) {
        ColumnModel column = ownerEntity.findColumn(tableName, columnName);

        if (!column.isPrimaryKey()) {
            column.setPrimaryKey(true);    // PK로 승격
        }

        if (column.isNullable()) {
            column.setNullable(false);     // PK는 NOT NULL
        }

        // 중복된 임베디드 PK 컬럼 제거
        removeDuplicateEmbeddedPkColumns(ownerEntity, columnName, descriptor);
    }

    refreshPrimaryKeyConstraint(ownerEntity, tableName);
}
```

### 중복 임베디드 PK 컬럼 제거

@MapsId로 FK 컬럼이 PK가 되면, 기존 @EmbeddedId로 생성된 중복 PK 컬럼을 제거:

```java
private void removeDuplicateEmbeddedPkColumns(EntityModel ownerEntity, String fkColumnName, AttributeDescriptor descriptor) {
    MapsId mapsId = descriptor.getAnnotation(MapsId.class);
    String keyPath = mapsId.value();

    // 중복될 수 있는 임베디드 PK 컬럼명들
    List<String> possibleEmbeddedPkColumns = List.of(
        "id_" + keyPath,           // "id_customerId"
        "id." + keyPath,          // "id.customerId"
        keyPath                   // "customerId"
    );

    for (String embeddedPkColumn : possibleEmbeddedPkColumns) {
        if (!embeddedPkColumn.equals(fkColumnName)) {
            // FK 컬럼과 다른 이름의 임베디드 PK 컬럼 제거
            ownerEntity.getColumns().entrySet()
                .removeIf(entry -> embeddedPkColumn.equals(entry.getValue().getColumnName())
                                && entry.getValue().isPrimaryKey());
        }
    }
}
```

## 관계별 DDL 생성 전략

### 1. @ManyToOne 및 owning @OneToOne

**생성되는 DDL 요소:**
- **FK 컬럼**: 소유 엔티티 테이블에 생성
- **FK 제약조건**: `FOREIGN KEY (fk_col) REFERENCES target_table(pk_col)`
- **FK 인덱스**: 성능을 위한 자동 인덱스
- **UNIQUE 제약**: @OneToOne인 경우 (선택적)

```sql
-- 예시: @ManyToOne User user;
ALTER TABLE order_table ADD COLUMN user_id BIGINT;
ALTER TABLE order_table ADD CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES user_table(id);
CREATE INDEX idx_order_user_id ON order_table(user_id);

-- 예시: @OneToOne Profile profile;
ALTER TABLE user_table ADD COLUMN profile_id BIGINT;
ALTER TABLE user_table ADD CONSTRAINT fk_user_profile FOREIGN KEY (profile_id) REFERENCES profile_table(id);
ALTER TABLE user_table ADD CONSTRAINT uq_user_profile_id UNIQUE (profile_id);
```

### 2. @OneToMany with FK Strategy

**생성되는 DDL 요소:**
- **FK 컬럼**: Many 쪽 테이블에 생성
- **FK 제약조건**: Many 쪽에서 One 쪽으로

```sql
-- 예시: @OneToMany @JoinColumn(name="parent_id") List<Child> children;
ALTER TABLE child_table ADD COLUMN parent_id BIGINT;
ALTER TABLE child_table ADD CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent_table(id);
```

### 3. @ManyToMany

**생성되는 DDL 요소:**
- **조인 테이블**: 별도 테이블 생성
- **두 개의 FK 컬럼**: 각각 양쪽 엔티티 참조
- **복합 PK**: 보통 두 FK 컬럼의 조합
- **두 개의 FK 제약조건**

```sql
-- 예시: @ManyToMany List<Role> roles;
CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
ALTER TABLE user_role ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_table(id);
ALTER TABLE user_role ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role_table(id);
```

### 4. @OneToMany with Join Table Strategy

**생성되는 DDL 요소:**
- **조인 테이블**: @ManyToMany와 유사하지만 일대다 관계
- **FK 제약조건들**
- **적절한 인덱스**

### 5. 제약조건 명명 규칙

Jinx의 `Naming` 인터페이스를 통한 일관된 명명:

```java
// FK 제약 이름
String fkName = context.getNaming().fkName(
    sourceTable, sourceColumns,    // FK가 있는 테이블과 컬럼들
    targetTable, targetColumns     // 참조하는 테이블과 컬럼들
);

// UNIQUE 제약 이름
String uqName = context.getNaming().uqName(tableName, columnNames);

// 인덱스 이름
String indexName = context.getNaming().indexName(tableName, columnNames);
```

### 6. @ForeignKey(NO_CONSTRAINT) 지원

FK 제약조건 생성을 비활성화하는 경우:

```java
boolean noConstraint = !joinColumns.isEmpty() &&
        joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT;

if (!noConstraint) {
    // FK 제약조건 생성
    RelationshipModel relationship = RelationshipModel.builder()
        .constraintName(relationConstraintName)
        .noConstraint(false)
        .build();
}
```

---

Jinx의 관계 해석 및 FK 처리 메커니즘은 다음과 같은 특징을 가집니다.

### 핵심 장점

1. **명확한 소유자 결정**: mappedBy 기반의 체계적인 owning/inverse side 구분
2. **우선순위 기반 처리**: RelationshipProcessor 체인을 통한 명확한 처리 순서
3. **복합키 완전 지원**: @EmbeddedId와 복합 FK의 완전한 매핑
4. **@MapsId 정교한 처리**: PK 공유 관계의 안전한 처리 및 중복 제거
5. **성능 최적화**: FK 인덱스 자동 생성
6. **순환 참조 방지**: mappedBy 체인의 무한 재귀 방지

### 설계 원칙

1. **단계별 처리**: 관계 생성 → @MapsId 후처리의 명확한 분리
2. **검증 중심**: 타입 정합성, 컬럼 수 일치 등 철저한 검증
3. **에러 친화적**: 상세한 에러 메시지와 위치 정보 제공
4. **확장 가능**: RelationshipProcessor 인터페이스를 통한 새로운 관계 유형 추가 용이

이러한 설계를 통해 Jinx는 복잡한 JPA 관계 구조도 정확하게 분석하여 올바른 DDL을 생성할 수 있습니다.