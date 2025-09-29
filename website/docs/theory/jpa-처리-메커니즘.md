---
sidebar_position: 1
---

# Jinx JPA 처리 메커니즘

Jinx는 컴파일 타임에 Annotation Processing Tool(APT)을 사용하여 JPA 어노테이션을 분석하고 DDL 마이그레이션을 지원하는 도구입니다. 이 문서는 jinx-processor 모듈에서 JPA 어노테이션을 어떻게 처리하는지에 대한 상세한 내용을 다룹니다.

## 목차

1. [아키텍처 개요](#아키텍처-개요)
2. [JPA 어노테이션 처리 방식](#jpa-어노테이션-처리-방식)
3. [캐싱 메커니즘](#캐싱-메커니즘)
4. [상속 처리 및 BFS 로직](#상속-처리-및-bfs-로직)
5. [Primary Key 판별 로직](#primary-key-판별-로직)
6. [처리 순서 및 페이즈](#처리-순서-및-페이즈)

## 아키텍처 개요

### 주요 컴포넌트

Jinx의 JPA 처리는 다음과 같은 핵심 컴포넌트들로 구성됩니다.

- **JpaSqlGeneratorProcessor**: 메인 어노테이션 프로세서
- **ProcessingContext**: 처리 과정에서 공유되는 컨텍스트 및 캐시
- **EntityHandler**: 엔티티 처리 담당
- **InheritanceHandler**: 상속 관계 처리 담당
- **AttributeDescriptorFactory**: 필드/프로퍼티 접근 방식 결정 및 디스크립터 생성
- **각종 Handler**: 컬럼, 관계, 제약조건 등 특정 기능 처리

### 지원하는 JPA 어노테이션

```java
@SupportedAnnotationTypes({
    "jakarta.persistence.*",
    "org.jinx.annotation.Constraint",
    "org.jinx.annotation.Constraints",
    "org.jinx.annotation.Identity"
})
```

## JPA 어노테이션 처리 방식

### 1. 어노테이션 탐지 및 우선순위

JPA 처리는 다음 순서로 이루어집니다.

1. **@Converter(autoApply=true)** 처리
2. **@MappedSuperclass** 및 **@Embeddable** 처리
3. **@Entity** 처리

### 2. Access Type 결정

`AccessUtils.determineAccessType()`을 통해 엔티티의 기본 접근 방식을 결정합니다.

```java
// 우선순위:
// 1. 클래스 레벨 @Access 어노테이션
// 2. 패키지 레벨 @Access 어노테이션
// 3. 계층에서 @Id/@EmbeddedId 배치 기반 추론
// 4. 기본값: AccessType.FIELD
```

**계층 기반 추론 로직**
```java
private static AccessType findFirstIdAccessTypeInHierarchy(TypeElement typeElement) {
    for (TypeElement current = typeElement;
         current != null && !"java.lang.Object".equals(current.getQualifiedName().toString());
         current = getSuperclass(current)) {

        // 필드에 @Id/@EmbeddedId가 있으면 FIELD
        if (hasIdOnFields(current)) {
            return AccessType.FIELD;
        }
        // 메서드(getter)에 @Id/@EmbeddedId가 있으면 PROPERTY
        if (hasIdOnMethods(current)) {
            return AccessType.PROPERTY;
        }
    }
    return null; // 호출부에서 FIELD로 폴백
}
```

### 3. Attribute Descriptor 생성

`AttributeDescriptorFactory`는 필드와 프로퍼티를 분석하여 올바른 접근 방식을 결정합니다.

#### Mapping Annotation 충돌 검사
```java
private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
    jakarta.persistence.Basic.class,
    jakarta.persistence.Column.class,
    jakarta.persistence.JoinColumn.class,
    // ... 기타 JPA 매핑 어노테이션들
);
```

#### 접근 방식 선택 로직
1. **명시적 @Access 우선**: 필드/메서드에 명시된 @Access가 최우선
2. **매핑 어노테이션 기반**: JPA 매핑 어노테이션이 있는 쪽을 선택
3. **기본 AccessType 사용**: 위 조건에 해당하지 않으면 클래스 기본값 사용

## 캐싱 메커니즘

### 1. ProcessingContext 캐시

Jinx는 성능 최적화를 위해 여러 레벨의 캐싱을 사용합니다.

```java
public class ProcessingContext {
    // AttributeDescriptor 캐싱 - 양방향 관계 해석 시 재계산 방지
    private final Map<String, List<AttributeDescriptor>> descriptorCache = new HashMap<>();

    // TypeElement 레지스트리 - 라운드 동안만 유효
    private final Map<String, TypeElement> mappedSuperclassElements = new HashMap<>();
    private final Map<String, TypeElement> embeddableElements = new HashMap<>();

    // PK 속성-컬럼 매핑 (@MapsId 해석용)
    private final Map<String, Map<String, List<String>>> pkAttributeToColumnMap = new HashMap<>();

    // MappedBy 순환 참조 방지
    private final Set<String> mappedByVisitedSet = new HashSet<>();
}
```

### 2. 캐시 라이프사이클

```java
public void beginRound() {
    clearMappedByVisited();
    deferredEntities.clear();
    deferredNames.clear();
    descriptorCache.clear();           // 라운드별 초기화
    pkAttributeToColumnMap.clear();
    mappedSuperclassElements.clear();
    embeddableElements.clear();
}
```

### 3. AttributeDescriptor 캐싱

```java
public List<AttributeDescriptor> getCachedDescriptors(TypeElement typeElement) {
    String fqn = typeElement.getQualifiedName().toString();
    return descriptorCache.computeIfAbsent(fqn,
            k -> attributeDescriptorFactory.createDescriptors(typeElement));
}
```

## 상속 처리 및 BFS 로직

### 1. BFS를 사용하는 영역

Jinx에서 BFS(Breadth-First Search) 알고리즘이 사용되는 주요 영역.

#### AttributeConverter 타입 해석
```java
private Optional<TypeMirror> findAttributeConverterAttributeType(TypeElement converterType) {
    Types types = processingEnv.getTypeUtils();
    Elements elements = processingEnv.getElementUtils();

    TypeElement acElement = elements.getTypeElement("jakarta.persistence.AttributeConverter");
    if (acElement == null) return Optional.empty();
    TypeMirror acErasure = types.erasure(acElement.asType());

    Deque<TypeMirror> q = new ArrayDeque<>();  // BFS 큐
    TypeMirror root = converterType != null ? converterType.asType() : null;
    if (root != null) q.add(root);

    while (!q.isEmpty()) {
        TypeMirror cur = q.poll();
        if (!(cur instanceof DeclaredType dt)) continue;

        // AttributeConverter<T, ?> 인터페이스를 찾으면 T 타입 반환
        if (types.isSameType(types.erasure(dt), acErasure)) {
            List<? extends TypeMirror> args = dt.getTypeArguments();
            if (args.size() == 2) return Optional.of(args.get(0));
        }

        // 큐에 부모 클래스와 인터페이스들 추가
        Element el = dt.asElement();
        if (el instanceof TypeElement te) {
            for (TypeMirror itf : te.getInterfaces()) q.add(itf);
            TypeMirror sc = te.getSuperclass();
            if (sc != null && sc.getKind() != TypeKind.NONE) q.add(sc);
        }
    }
    return Optional.empty();
}
```

#### 클래스 계층 탐색
`AttributeDescriptorFactory.collectAttributesFromHierarchy()`에서 상속 계층을 재귀적으로 탐색.

```java
private void collectAttributesFromHierarchy(TypeElement typeElement, Map<String, AttributeCandidate> candidates) {
    if (typeElement == null || "java.lang.Object".equals(typeElement.getQualifiedName().toString())) {
        return;
    }
    // 부모부터 먼저 처리 (상속된 속성이 우선)
    collectAttributesFromHierarchy(AccessUtils.getSuperclass(typeElement), candidates);

    boolean isEntity = typeElement.getAnnotation(jakarta.persistence.Entity.class) != null;
    boolean isMappedSuperclass = typeElement.getAnnotation(jakarta.persistence.MappedSuperclass.class) != null;
    boolean isEmbeddable = typeElement.getAnnotation(jakarta.persistence.Embeddable.class) != null;

    if (isEntity || isMappedSuperclass || isEmbeddable) {
        // 현재 클래스의 필드와 메서드들을 candidates에 추가
    }
}
```

### 2. 상속 전략별 처리

#### SINGLE_TABLE
```java
case SINGLE_TABLE:
    entityModel.setInheritance(InheritanceType.SINGLE_TABLE);
    DiscriminatorValue discriminatorValue = typeElement.getAnnotation(DiscriminatorValue.class);
    if (discriminatorValue != null) {
        entityModel.setDiscriminatorValue(discriminatorValue.value());
    }
    break;
```

#### JOINED
복잡한 외래키 관계 설정이 필요.
```java
case JOINED:
    entityModel.setInheritance(InheritanceType.JOINED);
    findAndProcessJoinedChildren(entityModel, typeElement);
    break;
```

#### TABLE_PER_CLASS
부모의 모든 컬럼을 자식 테이블에 복사.
```java
case TABLE_PER_CLASS:
    entityModel.setInheritance(InheritanceType.TABLE_PER_CLASS);
    findAndProcessTablePerClassChildren(entityModel, typeElement);
    checkIdentityStrategy(typeElement, entityModel); // IDENTITY 전략 경고
    break;
```

### 3. JOINED 상속의 복잡한 처리

JOINED 상속에서는 부모-자식 관계의 PK-FK 매핑이 중요합니다.

```java
private void processSingleJoinedChild(EntityModel childEntity, EntityModel parentEntity, TypeElement childType) {
    List<ColumnModel> parentPkCols = context.findAllPrimaryKeyColumns(parentEntity);
    List<JoinPair> joinPairs = resolvePrimaryKeyJoinPairs(childType, parentPkCols);

    // 1) 검증 단계: 타입/PK/nullable 조건 확인
    List<ColumnModel> pendingAdds = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    for (JoinPair jp : joinPairs) {
        ColumnModel parentPk = jp.parent();
        String childCol = jp.childName();

        ColumnModel existing = childEntity.findColumn(null, childCol);
        if (existing != null) {
            // 기존 컬럼과 예상 조건 비교
            boolean typeMismatch = !normalizeType(existing.getJavaType()).equals(normalizeType(parentPk.getJavaType()));
            boolean pkMismatch = !existing.isPrimaryKey();
            boolean nullMismatch = existing.isNullable();

            if (typeMismatch || pkMismatch || nullMismatch) {
                errors.add("JOINED column mismatch: ...");
            }
        } else {
            // 새로운 컬럼 생성 준비
            pendingAdds.add(createJoinedColumn(parentPk, childCol, childEntity.getTableName()));
        }
    }

    // 2) 커밋 단계: 오류가 없을 때만 실제 적용
    if (!errors.isEmpty()) {
        // 에러 로깅 및 엔티티 무효화
        return;
    }

    pendingAdds.forEach(childEntity::putColumn);
    // 관계 모델 생성 및 등록
}
```

## Primary Key 판별 로직

### 1. PK 어노테이션 탐지

Primary Key는 다음 어노테이션으로 식별됩니다.
- `@Id`: 단일 PK
- `@EmbeddedId`: 복합 PK (Embeddable 클래스)

```java
// ColumnBuilderFactory에서 PK 여부 결정
.isPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null)
```

### 2. PK 검증 로직

처리 과정에서 여러 단계의 PK 검증이 수행됩니다.

#### 첫 번째 PK 검증 (엔티티 처리 후)
```java
// 3. 최종 PK 검증 (2차 패스) - COLLECTION_TABLE은 제외
for (Map.Entry<String, EntityModel> e : context.getSchemaModel().getEntities().entrySet()) {
    EntityModel em = e.getValue();
    if (!em.isValid()) continue;

    // COLLECTION_TABLE 타입은 실제 엔티티가 아니므로 PK 검증에서 제외
    if (em.getTableType() == org.jinx.model.EntityModel.TableType.COLLECTION_TABLE) {
        continue;
    }

    if (context.findAllPrimaryKeyColumns(em).isEmpty()) {
        context.getMessager().printMessage(
            Diagnostic.Kind.ERROR,
            "Entity '" + e.getKey() + "' must have a primary key.",
            te
        );
        em.setValid(false);
    }
}
```

#### 두 번째 PK 검증 (JOINED 상속 처리 후)
```java
// 5. JOINED 상속 처리 완료 후 최종 PK 검증
for (Map.Entry<String, EntityModel> e : context.getSchemaModel().getEntities().entrySet()) {
    EntityModel em = e.getValue();
    if (!em.isValid()) continue;
    if (context.findAllPrimaryKeyColumns(em).isEmpty()) {
        context.getMessager().printMessage(
            Diagnostic.Kind.ERROR,
            "Entity '" + e.getKey() + "' must have a primary key after JOINED inheritance processing.",
            te
        );
        em.setValid(false);
    }
}
```

### 3. @EmbeddedId 처리

복합 PK는 별도의 복잡한 처리가 필요합니다.

```java
// EntityHandler에서 복합키 처리
if (type.getAnnotation(IdClass.class) != null) {
    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
        "IdClass is not supported. Use @EmbeddedId instead for composite primary keys.",
        type);
    entity.setValid(false);
    return;
}
```

### 4. PK 속성-컬럼 매핑 (@MapsId 지원)

```java
public void registerPkAttributeColumns(String entityFqcn, String attributePath, List<String> columnNames) {
    pkAttributeToColumnMap
        .computeIfAbsent(entityFqcn, k -> new HashMap<>())
        .put(attributePath, columnNames);
}

public List<String> getPkColumnsForAttribute(String entityFqcn, String attributePath) {
    return Optional.ofNullable(pkAttributeToColumnMap.get(entityFqcn))
        .map(attrMap -> attrMap.get(attributePath))
        .orElse(null);
}
```

## 처리 순서 및 페이즈

### 1. 초기화 페이즈

```java
@Override
public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    SchemaModel schemaModel = SchemaModel.builder()
            .version(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
            .build();
    this.context = new ProcessingContext(processingEnv, schemaModel);

    // 핸들러들 초기화
    this.sequenceHandler = new SequenceHandler(context);
    ColumnHandler columnHandler = new ColumnHandler(context, sequenceHandler);
    this.relationshipHandler = new RelationshipHandler(context);
    // ... 기타 핸들러들
}
```

### 2. 메인 처리 페이즈

```java
@Override
public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver()) {
        context.beginRound(); // 라운드 시작 시 컨텍스트 상태 초기화
    }

    processRetryTasks(); // 이전 라운드에서 지연된 작업들 처리

    // 1. @Converter(autoApply=true) 처리
    for (Element element : roundEnv.getElementsAnnotatedWith(Converter.class)) {
        // BFS로 AttributeConverter<T, ?> 타입 해석
    }

    // 2. @MappedSuperclass 및 @Embeddable 처리 (캐싱)
    for (Element element : roundEnv.getElementsAnnotatedWith(MappedSuperclass.class)) {
        // 캐시에 저장
    }

    // 3. @Entity 처리
    for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
        entityHandler.handle((TypeElement) element);
    }

    if (roundEnv.processingOver()) {
        // 최종 처리 페이즈
    }
}
```

### 3. 최종 처리 페이즈 (roundEnv.processingOver())

```java
if (roundEnv.processingOver()) {
    // 1. 상속 해석 (COLLECTION_TABLE 제외)
    for (EntityModel entityModel : context.getSchemaModel().getEntities().values()) {
        if (entityModel.getTableType() != COLLECTION_TABLE) {
            inheritanceHandler.resolveInheritance(typeElement, entityModel);
        }
    }

    // 2. 첫 번째 PK 검증

    // 3. Deferred FK (JOINED 상속 관련) 처리 - 최대 5회 시도
    int maxPass = 5;
    for (int pass = 0; pass < maxPass && !context.getDeferredEntities().isEmpty(); pass++) {
        entityHandler.runDeferredPostProcessing();
    }

    // 4. 두 번째 PK 검증 (JOINED 상속 처리 후)

    // 5. JSON 스키마 저장
    context.saveModelToJson();
}
```

### 4. 지연 처리 (Deferred Processing)

복잡한 상속 관계나 의존성이 있는 엔티티들은 지연 처리됩니다.

```java
private final Queue<EntityModel> deferredEntities = new ArrayDeque<>();
private final Set<String> deferredNames = new HashSet<>();

public void runDeferredPostProcessing() {
    while (!context.getDeferredEntities().isEmpty()) {
        EntityModel deferredEntity = context.getDeferredEntities().poll();
        String childName = deferredEntity.getFqcn();

        if (!context.getDeferredNames().contains(childName)) {
            continue; // 이미 처리됨
        }

        TypeElement childType = context.getElementUtils().getTypeElement(childName);
        if (childType == null) {
            // 아직 해석 불가능 - 다음 라운드로 재연기
            context.getDeferredEntities().offer(deferredEntity);
            continue;
        }

        // 처리 시도
        processInheritanceJoin(childType, deferredEntity);
    }
}
```

---

Jinx의 JPA 처리 메커니즘은 다음과 같은 특징을 가지고 있습니다.

1. **계층적 처리**: 상속 계층을 고려한 체계적인 어노테이션 분석
2. **효율적 캐싱**: 중복 계산을 방지하는 다단계 캐싱 시스템
3. **BFS 알고리즘**: 타입 해석과 상속 관계 분석에서 BFS 사용
4. **단계별 검증**: 여러 단계의 PK 검증으로 무결성 보장
5. **지연 처리**: 복잡한 의존성이 있는 엔티티의 점진적 해석
6. **에러 핸들링**: 상세한 에러 메시지와 함께하는 강건한 에러 처리