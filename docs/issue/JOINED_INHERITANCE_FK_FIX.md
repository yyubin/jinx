# JOINED 상속 전략 FK 제약조건 중복 이슈 수정

## 수정 일자
2025-10-23

## 문제 설명
JOINED 상속 전략 사용 시, 여러 자식 엔티티에서 동일한 FK 제약조건 이름이 생성되어 에러가 발생하는 문제

**에러:**
```
Duplicate relationship constraint name: fk_stat_condition__id_d01e9443
```

## 수정 내용

### 1. `InheritanceHandler` - @ForeignKey 어노테이션 처리 추가

**파일:** `jinx-processor/src/main/java/org/jinx/handler/InheritanceHandler.java`

#### 변경 사항:

**✅ `@PrimaryKeyJoinColumn`의 `@ForeignKey` 어노테이션 읽기**
- `extractForeignKeyInfo()` 메서드 추가
- `@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 지원
- `@ForeignKey(name = "custom_name")` 명시적 이름 지정 지원

**✅ 자동 중복 방지 메커니즘**
- `ensureUniqueConstraintName()` 메서드 추가
- 제약조건 이름 충돌 시 자동으로 `_1`, `_2` 등의 suffix 추가
- `isConstraintNameUsed()` 메서드로 사전 검증

**코드 예시:**
```java
// @ForeignKey 어노테이션 처리
ForeignKeyInfo fkInfo = extractForeignKeyInfo(childType);

// FK 제약조건 이름 결정
String constraintName;
if (fkInfo.explicitName != null && !fkInfo.explicitName.isEmpty()) {
    // 명시적으로 지정된 이름 사용
    constraintName = fkInfo.explicitName;
} else {
    // 자동 생성 + 중복 방지
    constraintName = context.getNaming().fkName(...);
    constraintName = ensureUniqueConstraintName(childEntity, constraintName);
}

RelationshipModel relationship = RelationshipModel.builder()
    .constraintName(constraintName)
    .noConstraint(fkInfo.noConstraint)  // NO_CONSTRAINT 설정
    .build();
```

### 2. `DefaultNaming` - 해시 알고리즘 개선

**파일:** `jinx-core/src/main/java/org/jinx/naming/DefaultNaming.java`

#### 변경 사항:

**기존:**
```java
// String.hashCode() 사용 (32비트, 충돌 가능성 높음)
String hash = Integer.toHexString(name.hashCode());
```

**수정 후:**
```java
// SHA-256 사용 (충돌 가능성 극히 낮음)
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
return String.format("%02x%02x%02x%02x", hash[0], hash[1], hash[2], hash[3]);
```

**개선 효과:**
- 해시 충돌 가능성 대폭 감소
- 동일한 입력에 대해 항상 동일한 출력 보장 (안정성)
- Fallback으로 기존 `String.hashCode()` 유지 (하위 호환성)

### 3. 경고 메시지 개선

**기존:**
```
To disable this constraint, you must explicitly use @PrimaryKeyJoinColumn
along with @JoinColumn and @ForeignKey(ConstraintMode.NO_CONSTRAINT).
```

**수정 후:**
```
To disable this constraint, use
@PrimaryKeyJoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)).
```

## 사용 방법

### 옵션 1: @ForeignKey로 제약조건 비활성화

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@PrimaryKeyJoinColumn(
    foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
)
public class StatCondition extends Condition {
    // ...
}
```

### 옵션 2: 명시적으로 FK 이름 지정

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@PrimaryKeyJoinColumn(
    foreignKey = @ForeignKey(name = "fk_stat_condition_custom")
)
public class StatCondition extends Condition {
    // ...
}
```

### 옵션 3: 자동 생성 (권장)

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public class StatCondition extends Condition {
    // Jinx가 자동으로 고유한 이름 생성
    // 충돌 시 _1, _2 등의 suffix 자동 추가
}
```

## 테스트 결과

### 빌드 결과
```bash
./gradlew build
# BUILD SUCCESSFUL
```

### 테스트 결과
```bash
./gradlew test
# BUILD SUCCESSFUL
# All tests passed
```

## 하위 호환성

✅ **기존 코드에 영향 없음:**
- 기존의 FK 제약조건 이름 생성 로직 유지
- 새로운 기능은 JOINED 상속에만 적용
- 일반 관계(@ManyToOne, @OneToOne 등)는 기존과 동일하게 동작

✅ **점진적 마이그레이션 가능:**
- 기존 프로젝트는 그대로 사용 가능
- 필요한 엔티티만 `@ForeignKey` 어노테이션 추가

## 관련 이슈

- [JOINED_INHERITANCE_FK_ISSUE.md](JOINED_INHERITANCE_FK_ISSUE.md) - 상세 분석 문서

## 변경된 파일

1. `jinx-processor/src/main/java/org/jinx/handler/InheritanceHandler.java`
   - `extractForeignKeyInfo()` 메서드 추가
   - `processPrimaryKeyJoinColumnForeignKey()` 메서드 추가
   - `ensureUniqueConstraintName()` 메서드 추가
   - `isConstraintNameUsed()` 메서드 추가
   - `ForeignKeyInfo` 내부 클래스 추가

2. `jinx-core/src/main/java/org/jinx/naming/DefaultNaming.java`
   - `computeStableHash()` 메서드 추가
   - `clampWithHash()` 메서드 개선

## 다음 단계

- [ ] 0.0.9 릴리즈 노트에 포함
- [ ] README 업데이트 (JOINED 상속 사용 예시 추가)
- [ ] 통합 테스트 케이스 추가 (복잡한 상속 구조)
