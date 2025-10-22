# JOINED 상속 전략에서 FK 제약조건 중복 이슈 분석

## 문제 요약

Jinx 0.0.7 버전에서 `JOINED` 상속 전략 사용 시, 동일한 제약 조건 이름이 중복 생성되는 문제가 발생합니다.

**에러 예시:**
```
Duplicate relationship constraint name: fk_stat_condition__id_d01e9443
```

## 문제 발생 원인

### 1. `@ForeignKey` 어노테이션 처리 누락

**일반 관계(@ManyToOne, @OneToOne 등)에서는 `@ForeignKey` 처리가 정상 작동:**

- `ToOneRelationshipProcessor.java:216-229`
- `OneToManyOwningFkProcessor.java:210-243`
- `EmbeddedHandler.java:318-340`

이들 프로세서는 모두 다음을 지원합니다:
- `@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 감지
- `@ForeignKey(name = "custom_name")` 명시적 이름 지정

**하지만 JOINED 상속에서는 처리되지 않음:**

`InheritanceHandler.java:311-349`의 `resolvePrimaryKeyJoinPairs()` 메서드는 `@PrimaryKeyJoinColumn` 어노테이션만 읽고, 해당 어노테이션에 포함된 `@ForeignKey` 정보를 무시합니다.

```java
// InheritanceHandler.java:275-286
RelationshipModel relationship = RelationshipModel.builder()
    .constraintName(context.getNaming().fkName(...))  // 항상 자동 생성
    .build();
// ❌ @ForeignKey 어노테이션 확인 없음
// ❌ noConstraint 설정 없음
```

### 2. 제약조건 이름 생성 알고리즘의 한계

**알고리즘 (`DefaultNaming.java:32-41`):**

```java
public String fkName(String childTable, List<String> childCols,
                     String parentTable, List<String> parentCols) {
    String base = "fk_"
            + norm(childTable)      // 정규화: 소문자, 특수문자 제거
            + "__"
            + joinNormalizedColumns(childCols)  // 컬럼명 정렬 후 결합
            + "__"
            + norm(parentTable);
    return clampWithHash(base);     // 길이 초과 시 해시 적용
}
```

**해시 절단 로직 (`DefaultNaming.java:123-128`):**

```java
private String clampWithHash(String name) {
    if (name.length() <= maxLength) return name;
    String hash = Integer.toHexString(name.hashCode());
    int keep = Math.max(1, maxLength - (hash.length() + 1));
    return name.substring(0, keep) + "_" + hash;
}
```

**문제점:**
1. **해시 충돌 가능성:** Java `String.hashCode()`는 32비트이므로, 비슷한 문자열에서 충돌 가능
2. **자식 테이블 구분 불충분:** 긴 테이블명이 잘릴 때 구분이 어려움

**충돌 시나리오 예시:**

```
Condition (부모)
 ├─ id (PK)

StatCondition (자식 1)
 ├─ id → Condition.id
 └─ FK 생성: fk_stat_condition__id__condition
     → 길이 초과 시: fk_stat_condition__id_d01e9443

AnotherStatCondition (자식 2)
 ├─ id → Condition.id
 └─ FK 생성: fk_another_stat_condition__id__condition
     → 길이 초과 시: fk_stat_conditio__id_d01e9443

// 만약 hash("fk_stat_condition__id__condition") == hash("fk_another_stat_condition__id__condition")
// → 동일한 이름 생성! ❌
```

### 3. 중복 감지는 있지만 너무 늦음

`InheritanceHandler.java:295-304`에서 중복을 감지하지만, 이미 이름이 생성된 **후**에만 검증하므로 해결책이 아닙니다.

```java
if (dup) {
    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
        "Duplicate relationship constraint name: " + fkName, childType);
    childEntity.setValid(false);
    return;
}
```

## 임시 해결책(Workaround)이 작동하지 않는 이유

### ❌ `@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 무시됨
```java
@Entity
@PrimaryKeyJoinColumn(
    joinColumns = @JoinColumn(
        name = "id",
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)  // ← 무시됨
    )
)
public class StatCondition extends Condition { ... }
```

### ❌ `@ForeignKey(name = "custom_name")` 덮어씌워짐
```java
@PrimaryKeyJoinColumn(
    foreignKey = @ForeignKey(name = "fk_custom_stat")  // ← 무시됨
)
// 여전히 context.getNaming().fkName()으로 자동 생성
```

## 해결 방안

### 1. `InheritanceHandler`에서 `@ForeignKey` 어노테이션 처리 추가

`resolvePrimaryKeyJoinPairs()` 메서드에서:
- `@PrimaryKeyJoinColumn`의 `foreignKey` 속성 읽기
- `ConstraintMode.NO_CONSTRAINT` 확인
- 명시적 FK 이름 추출

### 2. 제약조건 이름 생성 알고리즘 개선

**옵션 A:** 해시에 더 많은 정보 포함
```java
// 현재: fk_<childTable>__<cols>__<parentTable>
// 개선: fk_<childTable>__<cols>__<parentTable>__<parentCols>
```

**옵션 B:** UUID 또는 더 긴 해시 사용
```java
String hash = UUID.nameUUIDFromBytes(name.getBytes()).toString().substring(0, 8);
```

**옵션 C:** JOINED 상속 전용 네이밍 규칙
```java
public String joinedInheritanceFkName(String childTable, String parentTable, List<String> cols) {
    // "fk_joined_<child>_<parent>_<hash>" 형태로 고유성 보장
}
```

### 3. 중복 감지를 이름 생성 **전**으로 이동

기존 제약조건 목록을 확인하고, 충돌 시 suffix 추가:
```java
String baseName = context.getNaming().fkName(...);
String finalName = ensureUnique(entity, baseName);
```

## 관련 파일

- `jinx-core/src/main/java/org/jinx/naming/DefaultNaming.java:32-41` - FK 이름 생성
- `jinx-processor/src/main/java/org/jinx/handler/InheritanceHandler.java:275-286` - JOINED 상속 FK 생성
- `jinx-processor/src/main/java/org/jinx/handler/InheritanceHandler.java:311-349` - @PrimaryKeyJoinColumn 처리
- `jinx-processor/src/main/java/org/jinx/handler/relationship/ToOneRelationshipProcessor.java:216-251` - 일반 관계의 @ForeignKey 처리 참고

## 수정 이력

- 2025-10-23: 초기 분석 문서 작성
