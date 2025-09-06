# jinx-processor 관계 프로세서 심화 분석

## 개요

jinx-processor의 관계 처리는 `org.jinx.handler.relationship` 패키지에서 **Chain of Responsibility 패턴**과 **Strategy 패턴**을 결합하여 구현됩니다. 각 관계 유형별로 전문화된 프로세서가 순서대로 실행되며, 복잡한 JPA 관계 의미론을 정확한 DDL 스키마로 변환합니다.

## 아키텍처 개요

### 1. 프로세서 체인 구조
```java
// RelationshipHandler.java:51-60
private final List<RelationshipProcessor> processors = Arrays.asList(
    new InverseRelationshipProcessor(context, relationshipSupport),      // Order: 0  (최우선)
    new ToOneRelationshipProcessor(context),                             // Order: 10
    new OneToManyOwningFkProcessor(context, relationshipSupport),        // Order: 20  
    new OneToManyOwningJoinTableProcessor(context, relationshipSupport, joinTableSupport), // Order: 30
    new ManyToManyOwningProcessor(context, relationshipSupport, joinTableSupport)          // Order: 40
);
```

### 2. 인터페이스 정의
```java
public interface RelationshipProcessor {
    boolean supports(AttributeDescriptor descriptor);   // 처리 가능 여부 판단
    void process(AttributeDescriptor descriptor, EntityModel ownerEntity); // 실제 처리
    int order();                                        // 우선순위 (낮을수록 먼저 실행)
}
```

---

## 관계 프로세서별 상세 분석

### 1. InverseRelationshipProcessor (Order: 0)

**역할**: `mappedBy` 속성이 있는 관계의 역방향(Inverse) 처리  
**특징**: **DDL 생성 없이 논리적 관계만 검증**

#### 지원 관계
- `@OneToMany(mappedBy="...")`
- `@ManyToMany(mappedBy="...")`  
- `@OneToOne(mappedBy="...")`

#### 핵심 알고리즘
```java
@Override
public boolean supports(AttributeDescriptor descriptor) {
    OneToMany o2m = descriptor.getAnnotation(OneToMany.class);
    if (o2m != null && !o2m.mappedBy().isEmpty()) return true;
    
    ManyToMany m2m = descriptor.getAnnotation(ManyToMany.class);
    if (m2m != null && !m2m.mappedBy().isEmpty()) return true;
    
    OneToOne o2o = descriptor.getAnnotation(OneToOne.class);
    if (o2o != null && !o2o.mappedBy().isEmpty()) return true;
    
    return false;
}
```

#### 순환 참조 방지 시스템
```java
private AttributeDescriptor findMappedByAttribute(String ownerEntityName, String targetEntityName, String mappedByAttributeName) {
    // 무한 재귀 방지
    if (context.isMappedByVisited(targetEntityName, mappedByAttributeName)) {
        // 순환 참조 감지 시 경고 후 null 반환
        return null;
    }
    
    context.markMappedByVisited(targetEntityName, mappedByAttributeName);
    try {
        // AttributeDescriptor 캐시에서 mappedBy 속성 검색
        return targetDescriptors.stream()
                .filter(desc -> desc.name().equals(mappedByAttributeName))
                .findFirst().orElse(null);
    } finally {
        context.unmarkMappedByVisited(targetEntityName, mappedByAttributeName);
    }
}
```

#### 검증 과정
1. **타겟 엔티티 해석**: `support.resolveTargetEntity()` 사용
2. **mappedBy 속성 존재 검증**: 타겟 엔티티에서 해당 속성 검색  
3. **순환 참조 검증**: 방문 스택으로 무한 루프 방지
4. **로그 출력**: 논리적 관계 추적을 위한 NOTE 레벨 메시지

---

### 2. ToOneRelationshipProcessor (Order: 10)

**역할**: `@ManyToOne`, `@OneToOne` (소유측) 관계 처리  
**특징**: **Foreign Key 컬럼 생성 및 @MapsId 지원**

#### 지원 관계
- `@ManyToOne` (항상 소유측)
- `@OneToOne(mappedBy="")`(소유측만, `mappedBy` 없는 경우)

#### 핵심 처리 흐름

##### 1) 타겟 엔티티 검증
```java
Optional<TypeElement> referencedTypeElementOpt = support.resolveTargetEntity(descriptor, manyToOne, oneToOne, null, null);
EntityModel referencedEntity = context.getSchemaModel().getEntities().get(referencedTypeElement.getQualifiedName().toString());

List<ColumnModel> refPkList = context.findAllPrimaryKeyColumns(referencedEntity);
if (refPkList.isEmpty()) {
    // 오류: 참조 엔티티에 PK 없음
    return;
}
```

##### 2) JoinColumn 검증 및 매핑
```java
// 복합키 검증: JoinColumns 개수와 참조 PK 개수 일치 확인
if (joinColumns.isEmpty() && refPkList.size() > 1) {
    // 오류: 복합 PK는 명시적 @JoinColumns 필요
}

if (!joinColumns.isEmpty() && joinColumns.size() != refPkList.size()) {
    // 오류: @JoinColumns 개수 불일치
}
```

##### 3) FK 컬럼 생성 알고리즘
```java
for (int i = 0; i < refPkList.size(); i++) {
    JoinColumn jc = joinColumns.isEmpty() ? null : joinColumns.get(i);
    
    // 1) 참조 PK 결정
    String referencedPkName = (jc != null && !jc.referencedColumnName().isEmpty())
            ? jc.referencedColumnName() : refPkList.get(i).getColumnName();
    
    // 2) FK 컬럼명 결정
    String fkColumnName = (jc != null && !jc.name().isEmpty())
            ? jc.name()
            : context.getNaming().foreignKeyColumnName(fieldName, referencedPkName);
    
    // 3) Nullability 결정 (Association.optional + JoinColumn.nullable 조합)
    boolean associationOptional = (manyToOne != null) ? manyToOne.optional() : oneToOne.optional();
    boolean columnNullableFromAnno = (jc != null) ? jc.nullable() : associationOptional;
    boolean isNullable = associationOptional && columnNullableFromAnno;
    
    // 4) FK 컬럼 생성
    ColumnModel fkColumn = ColumnModel.builder()
            .columnName(fkColumnName)
            .tableName(support.resolveJoinColumnTable(jc, ownerEntity))
            .javaType(referencedPkColumn.getJavaType())
            .isPrimaryKey(false) // @MapsId에서 별도 PK 승격
            .isNullable(isNullable)
            .build();
}
```

##### 4) @MapsId 처리
```java
MapsId mapsId = descriptor.getAnnotation(MapsId.class);
if (mapsId != null) {
    // FK 컬럼을 PK로 승격하는 관계 정보만 저장
    // 실제 PK 승격은 EntityHandler.processMapsIdAttributes()에서 지연 처리
    relationship.setMapsId(true);
    relationship.setMapsIdKeyPath(mapsId.value().isEmpty() ? null : mapsId.value());
}
```

##### 5) 제약조건 생성
```java
// FK 제약조건
RelationshipModel relationship = RelationshipModel.builder()
        .type(manyToOne != null ? RelationshipType.MANY_TO_ONE : RelationshipType.ONE_TO_ONE)
        .constraintName(relationConstraintName)
        .noConstraint(joinColumns.get(0).foreignKey().value() == ConstraintMode.NO_CONSTRAINT)
        // ... 기타 속성
        .build();

// @OneToOne UNIQUE 제약조건 (단일 FK이고 @MapsId 아닌 경우)
boolean shouldAddUnique = (oneToOne != null) && (mapsId == null) && isSingleFk
        && (joinColumns.isEmpty() || joinColumns.get(0).unique());
```

---

### 3. OneToManyOwningFkProcessor (Order: 20)

**역할**: `@OneToMany` FK 전략 (소유측) 처리  
**특징**: **타겟 엔티티에 FK 컬럼 생성**

#### 조건 판별
```java
@Override
public boolean supports(AttributeDescriptor descriptor) {
    OneToMany o2m = descriptor.getAnnotation(OneToMany.class);
    if (o2m == null || !o2m.mappedBy().isEmpty()) return false; // 소유측이 아님
    
    // FK 전략: @JoinColumn 있고 @JoinTable 없어야 함
    JoinTable jt = descriptor.getAnnotation(JoinTable.class);
    JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
    JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
    boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);
    
    return hasJoinColumn && jt == null;
}
```

#### 핵심 차이점: "역방향 FK"
**일반적인 ToOne과 달리, OneToMany FK는 타겟 엔티티(Many측)에 생성됩니다:**

```java
// 소유자(One) PK → 타겟(Many) FK 매핑
List<ColumnModel> ownerPks = context.findAllPrimaryKeyColumns(ownerEntity);

for (int i = 0; i < ownerPks.size(); i++) {
    JoinColumn j = jlist.isEmpty() ? null : jlist.get(i);
    
    // FK는 타겟 엔티티의 테이블에 생성
    String tableNameForFk = support.resolveJoinColumnTable(j, targetEntityModel);
    
    ColumnModel fkColumn = ColumnModel.builder()
            .columnName(fkName)
            .tableName(tableNameForFk)  // 타겟 엔티티 테이블!
            .javaType(ownerPk.getJavaType())
            .isNullable(j.nullable())   // OneToMany는 JoinColumn.nullable() 그대로 적용
            .build();
    
    // 타겟 엔티티에 FK 컬럼 추가
    support.putColumn(targetEntityModel, fkColumn);
}
```

#### RelationshipModel 의미론
```java
// 도메인: User(1) → Orders(N) = ONE_TO_MANY
// 스키마: Orders 테이블에 user_id FK = 물리적으로는 MANY_TO_ONE
// 하지만 JPA 의미론 보존을 위해 ONE_TO_MANY로 기록
RelationshipModel rel = RelationshipModel.builder()
        .type(RelationshipType.ONE_TO_MANY)  // JPA 어노테이션 기준
        .tableName(fkBaseTable)              // FK가 위치하는 테이블 (Many측)
        .columns(fkNames)                    // FK 컬럼들 (Many측 테이블에 위치)
        .referencedTable(ownerEntity.getTableName()) // 참조 테이블 (One측)
        .referencedColumns(refNames)         // 참조 PK들
        .build();

// 관계는 타겟 엔티티에 저장됨 (FK가 있는 곳)
targetEntityModel.getRelationships().put(rel.getConstraintName(), rel);
```

---

### 4. OneToManyOwningJoinTableProcessor (Order: 30)

**역할**: `@OneToMany` JoinTable 전략 (소유측) 처리  
**특징**: **중간 테이블 생성 및 UNIQUE 제약조건 추가**

#### 조건 판별
```java
@Override
public boolean supports(AttributeDescriptor descriptor) {
    OneToMany o2m = descriptor.getAnnotation(OneToMany.class);
    if (o2m == null || !o2m.mappedBy().isEmpty()) return false;
    
    // JoinTable 전략: @JoinColumn 없거나 @JoinTable 명시
    JoinTable jt = descriptor.getAnnotation(JoinTable.class);
    JoinColumns jcs = descriptor.getAnnotation(JoinColumns.class);
    JoinColumn jc = descriptor.getAnnotation(JoinColumn.class);
    boolean hasJoinColumn = (jcs != null && jcs.value().length > 0) || (jc != null);
    
    return !hasJoinColumn || jt != null;
}
```

#### OneToMany JoinTable의 특수성
**ManyToMany와 달리 OneToMany JoinTable에는 UNIQUE 제약조건이 추가됩니다:**

```java
// OneToMany 의미론 보장: 각 타겟 엔티티는 최대 하나의 소유자만 가질 수 있음
public void addOneToManyJoinTableUnique(EntityModel joinTableEntity, Map<String,String> targetFkToPkMap) {
    List<String> cols = new ArrayList<>(targetFkToPkMap.keySet());
    if (!cols.isEmpty()) {
        String ucName = context.getNaming().uqName(joinTableEntity.getTableName(), cols);
        ConstraintModel constraintModel = ConstraintModel.builder()
                .name(ucName)
                .type(ConstraintType.UNIQUE)
                .tableName(joinTableEntity.getTableName())
                .columns(cols)  // 타겟(Many) FK 컬럼들에만 UNIQUE 제약
                .build();
        joinTableEntity.getConstraints().put(ucName, constraintModel);
    }
}
```

#### 스키마 구조 예시
```sql
-- User (1) : Orders (N) with JoinTable
CREATE TABLE user_orders (
    user_id BIGINT NOT NULL,    -- Owner FK
    order_id BIGINT NOT NULL,   -- Target FK
    
    PRIMARY KEY (user_id, order_id),
    UNIQUE (order_id),          -- OneToMany 의미론: 각 Order는 하나의 User만
    
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

---

### 5. ManyToManyOwningProcessor (Order: 40)

**역할**: `@ManyToMany` (소유측) 관계 처리  
**특징**: **순수 Join Table 생성 (UNIQUE 제약 없음)**

#### 조건 판별
```java
@Override
public boolean supports(AttributeDescriptor descriptor) {
    ManyToMany m2m = descriptor.getAnnotation(ManyToMany.class);
    return m2m != null && m2m.mappedBy().isEmpty(); // 소유측만
}
```

#### ManyToMany 복합 PK 전략
```java
private void addManyToManyPkConstraint(EntityModel joinTableEntity,
                                       Map<String,String> ownerFkToPkMap,
                                       Map<String,String> inverseFkToPkMap) {
    List<String> cols = new ArrayList<>();
    cols.addAll(ownerFkToPkMap.keySet());    // Owner FK 컬럼들
    cols.addAll(inverseFkToPkMap.keySet());  // Target FK 컬럼들
    
    // 복합 PK: (owner_fk + target_fk) 조합이 PK
    String pkName = context.getNaming().pkName(joinTableEntity.getTableName(), cols);
    ConstraintModel pkConstraint = ConstraintModel.builder()
            .name(pkName)
            .type(ConstraintType.PRIMARY_KEY)
            .tableName(joinTableEntity.getTableName())
            .columns(cols)  // 모든 FK 컬럼들이 PK 구성
            .build();
    joinTableEntity.getConstraints().put(pkName, pkConstraint);
}
```

#### OneToMany vs ManyToMany 차이점
| 관계 유형 | PK 전략 | UNIQUE 제약 |
|-----------|---------|-------------|
| OneToMany | (owner_fk + target_fk) | target_fk UNIQUE |
| ManyToMany | (owner_fk + target_fk) | 없음 |

---

## 지원 클래스 심화 분석

### 1. RelationshipSupport

#### 핵심 유틸리티 메서드들

##### 타겟 엔티티 해석
```java
public Optional<TypeElement> resolveTargetEntity(AttributeDescriptor attr,
                                              ManyToOne m2o, OneToOne o2o, OneToMany o2m, ManyToMany m2m) {
    // 1) 명시적 targetEntity 우선 (APT 안전)
    TypeElement explicit = classValToTypeElement(() -> annotation.targetEntity());
    if (explicit != null) return Optional.of(explicit);

    // 2) 컬렉션이면 제네릭 인자 사용
    if ((o2m != null) || (m2m != null)) {
        return attr.genericArg(0).map(dt -> (TypeElement) dt.asElement());
    }
    
    // 3) 필드/프로퍼티 타입으로 추론
    return Optional.ofNullable(getReferencedTypeElement(attr.type()));
}
```

##### PK 승격 (MapsId)
```java
public void promoteColumnsToPrimaryKey(EntityModel entity, String table, List<String> cols) {
    // 1) 컬럼 존재 확인 및 PK/NOT NULL 설정
    for (String col : cols) {
        ColumnModel c = entity.findColumn(table, col);
        c.setPrimaryKey(true);
        c.setNullable(false);
    }

    // 2) PRIMARY KEY 제약조건 생성
    String pkName = context.getNaming().pkName(table, cols);
    entity.getConstraints().put(pkName, ConstraintModel.builder()
            .name(pkName).type(ConstraintType.PRIMARY_KEY)
            .tableName(table).columns(new ArrayList<>(cols)).build());
}
```

##### 자동 인덱스 생성
```java
public void addForeignKeyIndex(EntityModel entity, List<String> fkColumns, String tableName) {
    // PK/UNIQUE로 이미 커버된 경우 생략
    if (coveredByPkOrUnique(entity, tableName, fkColumns)) return;
    
    // 중복 인덱스 방지
    if (hasSameIndex(entity, tableName, fkColumns)) return;
    
    // 자동 인덱스 생성 (성능 향상)
    String indexName = context.getNaming().ixName(tableName, fkColumns);
    IndexModel ix = IndexModel.builder()
            .indexName(indexName).tableName(tableName)
            .columnNames(List.copyOf(fkColumns)).isUnique(false).build();
    entity.getIndexes().put(indexName, ix);
}
```

---

### 2. RelationshipJoinSupport

#### Join Table 생성 및 관리

##### Join Table 엔티티 생성
```java
public EntityModel createJoinTableEntity(JoinTableDetails details, 
                                       List<ColumnModel> ownerPks, List<ColumnModel> referencedPks) {
    EntityModel joinTableEntity = EntityModel.builder()
            .entityName(details.joinTableName())
            .tableName(details.joinTableName())
            .tableType(EntityModel.TableType.JOIN_TABLE)  // 특별한 테이블 타입
            .build();

    // Owner FK 컬럼들 생성 (NOT NULL)
    for (Map.Entry<String, String> entry : details.ownerFkToPkMap().entrySet()) {
        String fkName = entry.getKey();
        String pkName = entry.getValue();
        ColumnModel pk = ownerPks.stream().filter(p -> p.getColumnName().equals(pkName)).findFirst().orElse(null);
        
        joinTableEntity.putColumn(ColumnModel.builder()
                .columnName(fkName).tableName(details.joinTableName())
                .javaType(pk.getJavaType()).isNullable(false).build());
    }

    // Target FK 컬럼들 생성 (NOT NULL)
    for (Map.Entry<String, String> entry : details.inverseFkToPkMap().entrySet()) {
        // 동일한 로직으로 Target FK 컬럼 생성
    }

    return joinTableEntity;
}
```

##### Join Table 일관성 검증
```java
public void validateJoinTableFkConsistency(EntityModel existingJoinTable, 
                                         JoinTableDetails details, AttributeDescriptor attr) {
    Set<String> existingColumns = existingJoinTable.getColumns().keySet();
    Set<String> requiredColumns = new HashSet<>();
    requiredColumns.addAll(details.ownerFkToPkMap().keySet());
    requiredColumns.addAll(details.inverseFkToPkMap().keySet());
    
    if (!existingColumns.equals(requiredColumns)) {
        // 스키마 불일치 시 상세한 오류 메시지 출력
        Set<String> missingColumns = new HashSet<>(requiredColumns);
        missingColumns.removeAll(existingColumns);
        
        Set<String> extraColumns = new HashSet<>(existingColumns);
        extraColumns.removeAll(requiredColumns);
        
        // 오류: "Expected columns: [a, b], Found columns: [a, c], Missing: [b], Extra: [c]"
        throw new IllegalStateException("JoinTable FK column set mismatch");
    }
}
```

---

### 3. JoinTableDetails (Record)

**불변 데이터 클래스**로 Join Table 생성에 필요한 모든 정보를 담습니다:

```java
public record JoinTableDetails(
        String joinTableName,                    // Join Table 이름
        Map<String, String> ownerFkToPkMap,     // Owner FK → PK 매핑
        Map<String, String> inverseFkToPkMap,   // Target FK → PK 매핑
        EntityModel ownerEntity,                 // 소유 엔티티
        EntityModel referencedEntity,           // 참조 엔티티
        String ownerFkConstraintName,           // Owner FK 제약조건명 (명시적)
        String inverseFkConstraintName,         // Target FK 제약조건명 (명시적)
        boolean ownerNoConstraint,              // Owner FK 제약조건 생략 여부
        boolean inverseNoConstraint             // Target FK 제약조건 생략 여부
) {}
```

---

## 고급 처리 시나리오

### 1. 복합키 처리

#### 복합키 ToOne 관계
```java
// User 엔티티: 복합 PK (companyId, userId)
// Order 엔티티: User 참조

@Entity
public class Order {
    @ManyToOne
    @JoinColumns({
        @JoinColumn(name = "user_company_id", referencedColumnName = "companyId"),
        @JoinColumn(name = "user_user_id", referencedColumnName = "userId")
    })
    private User user;
}
```

**처리 과정**:
1. `refPkList.size() > 1` 검증으로 복합키 감지
2. `@JoinColumns` 필수 검증 (`joinColumns.isEmpty() && refPkList.size() > 1` → 오류)
3. `referencedColumnName` 명시 필요 (복합키는 순서 매핑 불가)
4. 각 `@JoinColumn`별로 FK 컬럼 생성

#### ManyToMany 복합키 Join Table
```java
// User (companyId, userId) : Role (roleType, roleLevel) - ManyToMany

CREATE TABLE user_roles (
    user_company_id VARCHAR(50) NOT NULL,  -- User.companyId FK
    user_user_id VARCHAR(50) NOT NULL,     -- User.userId FK
    role_type VARCHAR(20) NOT NULL,        -- Role.roleType FK  
    role_level INTEGER NOT NULL,           -- Role.roleLevel FK
    
    PRIMARY KEY (user_company_id, user_user_id, role_type, role_level),
    
    FOREIGN KEY (user_company_id, user_user_id) REFERENCES users(company_id, user_id),
    FOREIGN KEY (role_type, role_level) REFERENCES roles(role_type, role_level)
);
```

---

### 2. @MapsId 처리

#### 전체 PK 공유 (@MapsId)
```java
@Entity
public class UserProfile {
    @Id
    @OneToOne
    @MapsId  // value 생략 = 전체 PK 공유
    private User user;
}
```

**처리 결과**:
- `user_id` FK 컬럼이 동시에 PK로 승격
- `promoteColumnsToPrimaryKey()`에서 `isPrimaryKey=true`, `isNullable=false` 설정
- PRIMARY KEY 제약조건 생성

#### 부분 PK 공유 (@MapsId("path"))
```java
@Entity  
public class OrderItem {
    @EmbeddedId
    private OrderItemId id;  // (orderId, itemSeq)
    
    @ManyToOne
    @MapsId("orderId")  // OrderItemId.orderId 속성만 FK와 매핑
    @JoinColumn(name = "order_id")
    private Order order;
}
```

**처리 결과**:
- `order_id` FK 컬럼이 복합키의 `orderId` 부분을 담당
- `context.registerPkAttributeColumns("orderId", ["order_id"])` 등록
- 나머지 복합키 부분(`itemSeq`)은 별도 컬럼으로 존재

---

### 3. 제약조건 모드 처리

#### @ForeignKey(NO_CONSTRAINT)
```java
@Entity
public class AuditLog {
    @ManyToOne
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;  // FK 제약조건 없이 참조 (soft reference)
}
```

**처리 결과**:
- FK 컬럼은 생성되지만 FOREIGN KEY 제약조건은 생성되지 않음
- `RelationshipModel.noConstraint = true` 설정
- 인덱스는 여전히 생성됨 (성능상 필요)

---

### 4. 보조 테이블 관계

#### @SecondaryTable과 관계
```java
@Entity
@SecondaryTable(name = "user_details", pkJoinColumns = @PrimaryKeyJoinColumn(name = "user_id"))
public class User {
    @ManyToOne
    @JoinColumn(name = "manager_id", table = "user_details")  // 보조 테이블에 FK 배치
    private User manager;
}
```

**처리 과정**:
1. `support.resolveJoinColumnTable(jc, ownerEntity)` 호출
2. `jc.table()` 값이 `user_details`인지 검증
3. `ownerEntity.getSecondaryTables()` 목록에서 유효성 확인
4. FK 컬럼을 `user_details` 테이블에 생성
5. RelationshipModel에 `tableName = "user_details"` 기록

---

## 오류 처리 및 검증

### 1. 컴파일 타임 검증

#### 참조 무결성 검증
```java
// ToOneRelationshipProcessor.java:127-134
ColumnModel referencedPkColumn = refPkMap.get(referencedPkName);
if (referencedPkColumn == null) {
    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "Referenced column name '" + referencedPkName + "' not found in primary keys of "
                    + referencedEntity.getEntityName(), descriptor.elementForDiagnostics());
    ownerEntity.setValid(false);
    return;
}
```

#### 타입 호환성 검증  
```java
// OneToManyOwningFkProcessor.java:181-192
ColumnModel existing = support.findColumn(targetEntityModel, tableNameForFk, fkName);
if (existing != null) {
    if (!existing.getJavaType().equals(fkColumn.getJavaType())) {
        context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Type mismatch for implicit foreign key column '" + fkName + "' in table '" + tableNameForFk + "' on " +
                        targetEntityModel.getEntityName() + ". Expected type " + fkColumn.getJavaType() +
                        " but found existing column with type " + existing.getJavaType(), attr.elementForDiagnostics());
        targetEntityModel.setValid(false);
        return;
    }
}
```

### 2. 부분 실패 허용

**엔티티별 독립적 처리**:
- 한 엔티티에서 관계 처리 실패 시 `entity.setValid(false)` 설정
- 다른 엔티티 처리는 계속 진행
- 최종 JSON 출력 시 유효한 엔티티만 포함

### 3. 진단 메시지 품질

**정확한 소스 위치 매핑**:
```java
context.getMessager().printMessage(Diagnostic.Kind.ERROR,
        "JoinTable name '" + joinTableName + "' conflicts with owner entity table name '" + ownerEntity.getTableName() + "'.", 
        attr.elementForDiagnostics());  // AttributeDescriptor가 정확한 Element 반환
```

**상세한 오류 설명**:
```java
StringBuilder error = new StringBuilder();
error.append("JoinTable '").append(details.joinTableName()).append("' FK column set mismatch. ");

if (!missingColumns.isEmpty()) {
    error.append("Missing columns: ").append(missingColumns).append(". ");
}
if (!extraColumns.isEmpty()) {
    error.append("Extra columns: ").append(extraColumns).append(". ");
}

error.append("Expected columns: ").append(requiredColumns).append(", ");
error.append("Found columns: ").append(existingColumns).append(".");
```

---

## 성능 최적화

### 1. AttributeDescriptor 캐싱
```java
// InverseRelationshipProcessor.java:188-195
List<AttributeDescriptor> targetDescriptors = context.getCachedDescriptors(targetTypeElement);
if (targetDescriptors == null) {
    context.getMessager().printMessage(Diagnostic.Kind.WARNING,
            "Descriptor cache miss for target entity " + targetEntityName +
                    " while resolving mappedBy '" + mappedByAttributeName + "'. Skipping.");
    return null;
}
```

**효과**: 양방향 관계에서 타겟 엔티티의 AttributeDescriptor 재계산 방지

### 2. 자동 인덱스 생성
```java
// RelationshipSupport.java:119-133
public void addForeignKeyIndex(EntityModel entity, List<String> fkColumns, String tableName) {
    if (coveredByPkOrUnique(entity, tableName, fkColumns)) return; // 중복 인덱스 방지
    
    String indexName = context.getNaming().ixName(tableName, fkColumns);
    IndexModel ix = IndexModel.builder()
            .indexName(indexName).tableName(tableName)
            .columnNames(List.copyOf(fkColumns)).isUnique(false).build();
    entity.getIndexes().put(indexName, ix);
}
```

**효과**: FK 컬럼에 자동 인덱스 생성으로 조인 성능 향상

### 3. 순환 참조 방지
```java
// ProcessingContext에서 관리
private final Set<String> mappedByVisitedSet = new HashSet<>();

public boolean isMappedByVisited(String entityName, String attributeName) {
    return mappedByVisitedSet.contains(entityName + "::" + attributeName);
}
```

**효과**: 무한 재귀 방지로 컴파일 시간 단축

---

## 결론

jinx-processor의 관계 프로세서 시스템은 **각 JPA 관계 유형의 특수성을 정확히 반영**하면서도 **확장 가능하고 유지보수하기 쉬운 아키텍처**를 제공합니다. 

**핵심 설계 원칙들**:
1. **단일 책임 원칙**: 각 프로세서는 하나의 관계 유형만 담당
2. **우선순위 기반 처리**: Order 값으로 처리 순서 보장 (Inverse → ToOne → OneToMany → ManyToMany)
3. **의미론 보존**: JPA 도메인 의미를 정확한 DDL 스키마로 변환
4. **오류 허용성**: 부분 실패가 전체 처리를 중단하지 않음
5. **성능 최적화**: 캐싱, 인덱스 자동 생성, 순환 참조 방지

이러한 설계를 통해 복잡한 JPA 관계 구조도 안정적이고 효율적으로 처리할 수 있습니다.