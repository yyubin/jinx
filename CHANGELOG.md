# 🧾 CHANGELOG

## [0.0.20] - 2025-12-27
### 🔧 Fixed
- **컬럼 변경이 모든 테이블로 잘못 전파되던 버그 수정** - `MigrationGenerator`에서 특정 테이블의 컬럼 추가/수정/삭제 시 모든 테이블에 동일한 DDL이 중복 생성되던 심각한 버그 해결
    - **원인**: `diff.tableContentAccept()`가 모든 `modifiedTables`를 순회하면서 각 visitor를 적용하여 O(N²) 복잡도로 중복 처리
    - **해결**: `m.accept()`로 변경하여 각 visitor가 자신의 테이블만 처리하도록 수정
    - **영향**: DROP, ALTER, FK_ADD 3개 단계 모두 수정

### 🧩 Changed
- **MigrationGenerator.java** (3곳 수정):
    - DROP 단계 (Line 40): `diff.tableContentAccept(v, DROP)` → `m.accept(v, DROP)`
    - ALTER 단계 (Line 63): `diff.tableContentAccept(v, ALTER)` → `m.accept(v, ALTER)`
    - FK_ADD 단계 (Line 70): `diff.tableContentAccept(v, FK_ADD)` → `m.accept(v, FK_ADD)`

### 🧪 Tests
- **MigrationGeneratorTest.java** 업데이트:
    - DROP 단계 (Lines 44-56): `diff.tableContentAccept()` mock → `me.accept()` mock
    - ALTER 단계 (Lines 58-70): `diff.tableContentAccept()` mock → `me.accept()` mock
    - FK_ADD 단계 (Lines 72-81): `diff.tableContentAccept()` mock → `me.accept()` mock
    - `diff.getAddedTables()` stub 추가 (Lines 43, 211)
- 전체 프로젝트 테스트 통과 (`BUILD SUCCESSFUL`)

### 📈 Impact
- **성능 개선**: O(N²) → O(N) 복잡도로 감소 (N = 테이블 수)
- **SQL 크기 감소**: 불필요한 중복 ALTER 문 제거
- **정확성 보장**: 이제 컬럼 변경이 의도한 테이블에만 적용됨

**수정 전 (버그):**
```sql
ALTER TABLE follow ADD COLUMN taste_tag VARCHAR(100);      -- 잘못된 중복
ALTER TABLE users ADD COLUMN taste_tag VARCHAR(100);       -- 의도한 테이블
ALTER TABLE review_reaction ADD COLUMN taste_tag VARCHAR(100);  -- 잘못된 중복
-- ... 모든 테이블에 중복 적용
```

**수정 후 (정상):**
```sql
ALTER TABLE users ADD COLUMN taste_tag VARCHAR(100);       -- 올바름
```

### 🔐 Security
- **Publishing credentials 보안 강화**:
    - `gradle.properties`에서 `gradle.publish.key`, `gradle.publish.secret` 제거
    - `local.properties` (gitignore에 포함)로 credentials 분리
    - `PUBLISHING.md` 가이드 추가

### 📚 Documentation
- README 및 예제 버전을 0.0.20으로 업데이트
- `PUBLISHING.md` 추가: Gradle Plugin Portal 퍼블리싱 가이드 및 보안 사고 대응 방법

---

## [0.0.13] - 2025-10-24
### 🔧 Fixed
- **ToOne 관계 FK 누락 문제 수정** - `@ManyToOne` / `@OneToOne` 관계에서 참조 대상 엔티티가 알파벳순으로 나중에 처리되는 경우 외래키(FK) 컬럼이 DDL에서 누락되던 문제 해결
    - **Processor 단계**: Deferred Processing 메커니즘 추가하여 엔티티 처리 순서와 무관하게 모든 FK 컬럼 생성 보장
    - **SQL 생성 단계**: `CreateTableBuilder.defaultsFrom()`에서 FK 제약 조건 SQL 생성 누락 문제 수정
    - 순환 의존성(circular dependencies) 지원
    - Referenced entity 누락 시 조용히 실패하던 문제 해결 → NOTE 메시지로 디버깅 용이성 향상
- **OneToOne 관계 UNIQUE 제약 조건 누락 문제 수정** - `@OneToOne` 관계에서 `@JoinColumn.unique` 값과 무관하게 항상 UNIQUE 제약 조건 생성 (Hibernate 동작과 일치)
- **Inverse 관계 검증 경고 제거** - `@OneToMany(mappedBy=...)` 검증 시 target entity가 아직 처리되지 않은 경우 불필요한 WARNING 출력 제거

### 🧩 Changed
- **Processor 모듈**:
    - `ToOneRelationshipProcessor.process()` 로직 개선:
        - Referenced entity가 아직 처리되지 않은 경우 자동으로 deferred queue에 추가
        - 중복 처리 방지 로직 추가 (재시도 시 이미 생성된 관계는 스킵하되 UNIQUE 제약 조건은 확인)
        - `@OneToOne` 관계는 `@JoinColumn.unique` 값과 무관하게 항상 UNIQUE 제약 조건 추가
    - `EntityHandler.runDeferredPostProcessing()`에 관계 재처리 로직 추가:
        - JOINED 상속 및 @MapsId와 함께 ToOne 관계도 재처리
    - `InverseRelationshipProcessor` 검증 로직 개선:
        - Target entity가 아직 처리되지 않은 경우 조용히 스킵 (불필요한 WARNING 제거)
- **Core 모듈**:
    - `CreateTableBuilder.defaultsFrom()` 메서드 개선:
        - `entity.getRelationships()`를 순회하여 `RelationshipAddContributor` 추가
        - FK 제약 조건이 CREATE TABLE 이후 ALTER TABLE로 정상 생성됨

### 🧪 Tests
- `ToOneRelationshipProcessorTest`에 Deferred Processing 단위 테스트 4개 추가:
    - `process_defers_when_referenced_entity_not_found()`
    - `process_defers_only_once_when_referenced_entity_not_found()`
    - `process_skips_when_relationship_already_processed()`
    - `process_succeeds_after_referenced_entity_becomes_available()`
- `DeferredToOneRelationshipProcessingTest` 통합 테스트 3개 추가:
    - `manyToOne_deferred_processing_creates_fk_after_retry()`
    - `multiple_manyToOne_deferred_processing()`
    - `oneToOne_deferred_processing_creates_fk_with_unique()`

### 📈 Impact
- 엔티티 처리 순서 의존성 제거 → 안정적인 DDL 생성 보장
- 복잡한 엔티티 관계 그래프에서도 모든 FK가 올바르게 생성됨
- 하위 호환성 유지 (기존 동작 변경 없음, 누락되던 FK만 추가)

### 📚 Documentation
- `docs/issue/TOONE_DEFERRED_PROCESSING_FIX.md` 추가: 문제 분석, 해결 방법, 테스트 결과 상세 문서화

---

## [0.0.9] - 2025-10-23
### 🔧 Fixed
- **Primitive 타입**(`int`, `boolean`, `double` 등)이 `TEXT`로 잘못 매핑되던 문제 수정
    - 이제 JPA 어노테이션에 맞게 `INT`, `TINYINT(1)`, `DOUBLE` 등으로 올바르게 매핑됨
- **Enum 타입**(`@Enumerated(EnumType.STRING | ORDINAL)`)의 SQL 매핑 오류 수정
    - `EnumType.STRING` → `VARCHAR(length)`
    - `EnumType.ORDINAL` → `INT`
- DDL 생성 로직을 Liquibase 타입 매핑 로직과 일관되게 통합

### 🧩 Changed
- `MySqlJavaTypeMapper`에 8개의 Primitive 타입 매핑 추가:
    - `int`, `long`, `double`, `float`, `boolean`, `byte`, `short`, `char`
- `MySqlDialect.getColumnDefinitionSql()`에 Enum 타입 처리 로직 추가

### 🧪 Tests
- Primitive 타입 매핑 테스트 8개 추가 (`MySqlJavaTypeMapperTest`)
- Enum 타입 매핑 테스트 2개 추가 (`MySqlDialectTest`)
- 전체 테스트 통과 및 회귀 없음

### 📈 Impact
- 10개 이상의 엔티티, 30개 이상의 컬럼이 잘못된 `TEXT` 타입에서 올바른 SQL 타입으로 수정됨
- 완전한 하위 호환 유지 및 릴리즈 안정성 검증 완료
