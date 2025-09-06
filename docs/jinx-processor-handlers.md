# jinx-processor 핸들러 아키텍처 분석

## 개요

jinx-processor는 JPA 어노테이션 분석을 여러 전문화된 핸들러로 분할하여 처리합니다. 각 핸들러는 특정 JPA 기능에 대한 책임을 가지며, 계층적이고 협력적인 구조를 통해 복잡한 스키마 생성을 수행합니다.

## 핸들러 계층구조

### 1. 주요 처리 핸들러 (Primary Handlers)

#### EntityHandler (`org.jinx.handler.EntityHandler`)
**역할**: 엔티티 전체 생명주기 관리 및 조정자 역할

**핵심 기능**:
- 엔티티 생명주기 관리 (생성 → 메타데이터 처리 → 필드 처리 → 검증)
- 다른 핸들러들의 조정 (Orchestrator 패턴)
- 보조 테이블(@SecondaryTable) 처리
- 상속 조인 관계 처리 (JOINED 전략)
- @MapsId 지연 처리 큐 관리

**아키텍처 특징**:
```java
// 의존성 주입을 통한 핸들러 조정
public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, 
                     EmbeddedHandler embeddedHandler, ConstraintHandler constraintHandler, 
                     SequenceHandler sequenceHandler, ElementCollectionHandler elementCollectionHandler, 
                     TableGeneratorHandler tableGeneratorHandler, RelationshipHandler relationshipHandler)
```

**처리 단계**:
1. **기본 정보 설정**: `@Entity`, `@Table` 메타데이터 추출
2. **제너레이터 처리**: `SequenceHandler`, `TableGeneratorHandler` 위임
3. **제약조건 처리**: `ConstraintHandler` 위임
4. **복합키 처리**: `@EmbeddedId` 검증 및 `EmbeddedHandler` 위임
5. **필드 처리**: AttributeDescriptor 기반으로 각 핸들러에 위임
6. **후처리**: 보조 테이블 조인, 상속 관계 처리

**지연 처리 전략**:
- `@MapsId` 속성: 참조 엔티티의 PK 확정 후 처리
- JOINED 상속: 부모 엔티티 처리 완료 후 자식 처리
- 최대 5회 재시도로 순환 의존성 해결

---

#### RelationshipHandler (`org.jinx.handler.RelationshipHandler`)
**역할**: JPA 관계 어노테이션 전문 처리기

**핵심 기능**:
- 관계 어노테이션 분석 (`@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`)
- 조인 전략 결정 (FK vs JoinTable)
- `@MapsId` 지연 처리
- 양방향 관계 순환 참조 방지

**전략 패턴 아키텍처**:
```java
private final List<RelationshipProcessor> processors;

// 우선순위 기반 프로세서 체인
this.processors = Arrays.asList(
    new InverseRelationshipProcessor(context, relationshipSupport),      // mappedBy
    new ToOneRelationshipProcessor(context),                             // @ManyToOne, @OneToOne
    new OneToManyOwningFkProcessor(context, relationshipSupport),        // @OneToMany (FK)
    new OneToManyOwningJoinTableProcessor(context, relationshipSupport, joinTableSupport), // @OneToMany (JoinTable)
    new ManyToManyOwningProcessor(context, relationshipSupport, joinTableSupport)          // @ManyToMany
);
```

**@MapsId 처리 전략**:
1. **전체 PK 공유**: `@MapsId` (value 없음) → FK 컬럼이 전체 PK 구성
2. **부분 PK 공유**: `@MapsId("attributePath")` → 복합키의 특정 속성만 FK로 매핑
3. **PK 승격**: FK 컬럼을 PK로 승격하고 NOT NULL 제약 적용
4. **제약조건 갱신**: PRIMARY KEY 제약조건 재생성

**순환 참조 방지**:
- `mappedByVisitedSet`을 통한 방문 추적
- 엔티티별 처리 시작 시 초기화

---

### 2. 컬럼 처리 시스템

#### ColumnHandler (`org.jinx.handler.ColumnHandler`)
**역할**: 컬럼 생성 및 검증의 팩토리

**전략 패턴**:
```java
// 세 가지 전략으로 컬럼 해석
private final ColumnResolver fieldBasedResolver;        // CollectionElementResolver
private final ColumnResolver typeBasedResolver;         // EntityFieldResolver  
private final AttributeColumnResolver attributeBasedResolver; // AttributeBasedEntityResolver
```

**컬럼 생성 전략**:
1. **AttributeDescriptor 기반**: `createFromAttribute()` - 통합 속성 분석
2. **필드 기반**: `createFrom()` - 직접 VariableElement 처리
3. **타입 기반**: `createFromFieldType()` - 컬렉션 요소 처리
4. **타입+속성 기반**: `createFromAttributeType()` - 임베디드 필드 처리

**검증 기능**:
- 테이블명 유효성 검증 (Primary/Secondary 테이블)
- 컬럼명 중복 방지
- 메타데이터 일관성 검증

#### AbstractColumnResolver와 구현체들
**아키텍처**: Template Method 패턴

**구현체별 특화**:
- **EntityFieldResolver**: 엔티티 필드의 JPA 어노테이션 분석
- **CollectionElementResolver**: `@ElementCollection` 요소 타입 분석  
- **AttributeBasedEntityResolver**: AttributeDescriptor 통합 분석

---

### 3. 특수 기능 핸들러

#### EmbeddedHandler (`org.jinx.handler.EmbeddedHandler`)
**역할**: `@Embedded`, `@EmbeddedId` 처리 전문가

**핵심 기능**:
- 재귀적 임베디드 객체 처리
- AttributeOverride, AssociationOverride 적용
- 컬럼명 접두어 자동 생성
- 임베디드 관계 처리 (ToOne)

**처리 알고리즘**:
```java
private void processEmbeddedInternal(AttributeDescriptor attribute, EntityModel ownerEntity, 
                                   Set<String> processedTypes, boolean isPrimaryKey, 
                                   String parentColumnPrefix, String parentAttributePath,
                                   Map<String, String> nameOverrides, Map<String, String> tableOverrides,
                                   Map<String, List<JoinColumn>> assocOverrides)
```

**순환 참조 방지**: `processedTypes` Set으로 타입 스택 추적

**오버라이드 처리**:
- **이름 오버라이드**: `@AttributeOverride(name, column)` → 컬럼명 변경
- **테이블 오버라이드**: `@AttributeOverride(column.table)` → 보조 테이블 배치
- **관계 오버라이드**: `@AssociationOverride(joinColumns)` → FK 컬럼 재정의

**@EmbeddedId 특수 처리**:
- 모든 내부 필드를 PK로 승격 (`isPrimaryKey=true`)
- PK 속성 경로별 컬럼 매핑 등록 (`context.registerPkAttributeColumns()`)

---

#### ElementCollectionHandler (`org.jinx.handler.ElementCollectionHandler`)
**역할**: `@ElementCollection` 전문 처리기

**핵심 아키텍처**: **2단계 검증-커밋 패턴**

**2단계 처리 전략**:
```java
// 1단계: 검증 (메모리에서만)
ValidationResult validation = validateElementCollection(attribute, ownerEntity);

// 2단계: 커밋 (오류 없을 때만)
if (!validation.hasErrors()) {
    validation.commitToModel();
}
```

**지원하는 컬렉션 타입**:
1. **Set/Collection<BasicType>**: 값이 PK 구성요소
2. **List<BasicType>**: `@OrderColumn`으로 순서 관리
3. **Map<BasicType, BasicType>**: 키+값 모두 기본 타입
4. **Map<EntityType, BasicType>**: `@MapKeyJoinColumn`으로 엔티티 키
5. **Set/List/Map<EmbeddableType>**: `EmbeddedHandler` 위임

**컬렉션 테이블 구조 생성**:
- **FK 컬럼들**: 소유 엔티티의 PK → 컬렉션 테이블 FK (PK 일부)
- **키 컬럼** (Map): `@MapKeyColumn` 또는 기본 이름
- **값 컬럼**: `@Column` 또는 기본 이름  
- **순서 컬럼** (List): `@OrderColumn` 또는 기본 이름

**검증 시스템**:
- 컬럼명 중복 검증 (FK, Key, Value, Order 간)
- 제약조건명 중복 검증  
- 인덱스/제약조건 참조 컬럼 존재성 검증
- 메타데이터 일관성 검증

---

#### InheritanceHandler (`org.jinx.handler.InheritanceHandler`)
**역할**: JPA 상속 전략 처리 전문가

**지원 상속 전략**:

1. **SINGLE_TABLE**:
   - 판별자 컬럼 자동 생성 (`@DiscriminatorColumn`)
   - 타입별 판별자 값 설정 (`@DiscriminatorValue`)

2. **JOINED**:
   - 부모-자식 간 PK Join 관계 생성
   - `@PrimaryKeyJoinColumn` 처리
   - 2단계 검증-커밋 패턴 적용

3. **TABLE_PER_CLASS**:
   - 부모 컬럼/제약조건 전체 복사
   - IDENTITY 전략 충돌 경고

**JOINED 처리 알고리즘**:
```java
private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, TypeElement childType) {
    // 1) 검증 단계: pending 목록에만 생성
    List<ColumnModel> pendingAdds = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    
    // 2) 커밋 단계: 오류 없을 때만 실제 추가
    if (errors.isEmpty()) {
        pendingAdds.forEach(childEntity::putColumn);
    }
}
```

**판별자 컬럼 처리**:
- 타입별 길이 검증 (CHAR는 길이 1 강제)
- `columnDefinition` vs `options` 상호배타성 검증
- Java 타입 매핑 (STRING → String, INTEGER → Integer)

---

### 4. 유틸리티 핸들러

#### SequenceHandler (`org.jinx.handler.SequenceHandler`)
**역할**: `@SequenceGenerator` 처리

**기능**:
- 클래스 레벨 `@SequenceGenerator(s)` 수집
- 중복 이름 방지
- SequenceModel 생성 및 스키마 등록

#### TableGeneratorHandler (`org.jinx.handler.TableGeneratorHandler`)  
**역할**: `@TableGenerator` 처리

**기능**:
- 클래스 레벨 `@TableGenerator(s)` 수집
- 테이블 기반 ID 생성 메타데이터 관리
- TableGeneratorModel 생성 및 스키마 등록

#### ConstraintHandler (`org.jinx.handler.ConstraintHandler`)
**역할**: 사용자 정의 제약조건 처리

**기능**:
- `@Constraint`, `@Constraints` 어노테이션 분석
- 제약조건 타입 자동 추론 (`ConstraintType.AUTO`)
- CHECK, UNIQUE, NOT_NULL 등 지원

---

## 핸들러 간 협력 패턴

### 1. 위임 패턴 (Delegation Pattern)
**EntityHandler가 중심**이 되어 각 전문 핸들러에 위임:

```java
// EntityHandler.processFieldsWithAttributeDescriptor()
for (AttributeDescriptor descriptor : descriptors) {
    if (descriptor.hasAnnotation(ElementCollection.class)) {
        elementCollectionHandler.processElementCollection(descriptor, entity);
    } else if (descriptor.hasAnnotation(Embedded.class)) {
        embeddedHandler.processEmbedded(descriptor, entity, new HashSet<>());
    } else if (isRelationshipAttribute(descriptor)) {
        relationshipHandler.resolve(descriptor, entity);
    } else {
        // 일반 컬럼 처리
        processRegularAttribute(descriptor, entity, tableMappings);
    }
}
```

### 2. 전략 패턴 (Strategy Pattern)
**ColumnHandler의 리졸버 선택**:
```java
public ColumnModel createFromAttribute(AttributeDescriptor attribute, EntityModel entity, Map<String, String> overrides) {
    return attributeBasedResolver.resolve(attribute, null, null, overrides);
}
```

**RelationshipHandler의 프로세서 체인**:
```java
for (RelationshipProcessor p : processors) {
    if (p.supports(descriptor)) { 
        p.process(descriptor, entityModel); 
        handled = true; 
        break; 
    }
}
```

### 3. 지연 처리 패턴 (Deferred Processing)
**의존성 기반 단계별 처리**:
1. **즉시 처리**: 독립적인 엔티티/컬럼 처리
2. **1차 지연**: 상속 관계 해석 후 처리  
3. **2차 지연**: `@MapsId` FK→PK 승격 처리
4. **최종 검증**: PK 존재성 검증

### 4. 2단계 검증-커밋 패턴
**ElementCollectionHandler와 InheritanceHandler 적용**:
- **검증 단계**: 메모리상에서 모든 구성요소 생성 및 검증
- **커밋 단계**: 오류 없을 때만 모델에 일괄 반영
- **원자성 보장**: 부분 생성 없이 완전 성공 또는 완전 실패

## 오류 처리 전략

### 1. 단계별 검증
- **구문 검증**: 어노테이션 파라미터 유효성
- **의미 검증**: 참조 무결성, 타입 호환성
- **구조 검증**: 순환 참조, 중복 이름

### 2. 진단 메시지
- APT `Messager`를 통한 컴파일 타임 오류 출력
- 소스 위치 정확한 매핑 (`AttributeDescriptor.elementForDiagnostics()`)
- 오류/경고/정보 레벨 분류

### 3. 부분 실패 허용
- 개별 엔티티 오류가 전체 처리 중단하지 않음
- `EntityModel.isValid()` 플래그로 실패 마킹
- 최종 JSON 출력에서 유효한 엔티티만 포함

## 확장성 설계

### 1. 플러그인 아키텍처
- 새로운 JPA 어노테이션 → 새로운 핸들러 추가
- 기존 핸들러 영향 최소화

### 2. 인터페이스 기반 설계
- `ColumnResolver`, `RelationshipProcessor` 등 인터페이스
- 구현체 교체/확장 용이

### 3. 컨텍스트 기반 상태 관리
- `ProcessingContext`를 통한 통합 상태 관리
- 핸들러 간 정보 공유 일원화

이러한 아키텍처를 통해 jinx-processor는 복잡한 JPA 스키마를 안정적이고 확장 가능하게 처리할 수 있습니다.