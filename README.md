# Jinx (WIP) — JPA → DDL/Liquibase migration generator

> ⚠️ 아직 릴리즈 전(WIP)입니다. 내부 구조 리팩토링과 기능 쪼개기 진행 중이에요.

Jinx는 JPA 애노테이션을 스캔해 **스키마 스냅샷(JSON)** 을 만들고, 이전 스냅샷과 비교하여 **DB 마이그레이션 SQL**(및 선택적으로 **롤백 SQL**, **Liquibase YAML**)을 생성합니다.
목표는 **DB/Dialect 의존성을 최소화**하면서도 **DDL 품질과 가독성**을 확보하는 것입니다.

---

## 빠른 개요

### 파이프라인

1. **Annotation Processor** (`jinx-processor`)

    * `@Entity` 등 JPA 애노테이션을 스캔해 `SchemaModel`을 생성
    * `build/classes/java/main/jinx/schema-YYYYMMDDHHmmss.json` 으로 출력
2. **CLI 비교/생성기** (`migrate` 커맨드)

    * 최신 2개의 스냅샷을 비교 → `DiffResult` 생성
    * `DialectBundle`(DB 방언 묶음)을 주입받아 SQL / Liquibase YAML 생성

### 현재 지원 상태(요약)

* **DDL**: 테이블/컬럼/PK/인덱스/제약/외래키/컬럼 리네임/테이블 리네임
* **ID 전략**: `IDENTITY`(MySQL: `AUTO_INCREMENT`), `SEQUENCE`(방언 제공 시), `TABLE`(TableGenerator)
* **Liquibase YAML**: 주요 change 생성 + 타입 매핑
* **초기 데이터**: Liquibase `insert` change DTO 추가 (WIP)
* **DB 방언**: MySQL 우선 구현 (Sequence 미사용), 기타는 인터페이스 확장으로 확장 가능

---

## 리팩토링 하이라이트

### 1) Dialect 분리

기존 단일 인터페이스를 아래처럼 역할별로 분해했습니다.

* `BaseDialect` : 공통(식별자 quoting, 타입 매퍼/값 변환기 제공 등)
* `DdlDialect` : 테이블/컬럼/PK/제약/인덱스/관계 등 **DDL 생성**
* `IdentityDialect` : `IDENTITY` 컬럼 문법 제공
* `SequenceDialect` : 시퀀스 DDL
* `TableGeneratorDialect` : Table generator 테이블/초기값 DDL
* (Liquibase 타입 네이밍이 필요한 경우) `LiquibaseDialect`

> **MySQL 구현**: `MySqlDialect`는 `BaseDialect + DdlDialect`를 기본으로, `IdentityDialect`, `TableGeneratorDialect`만 선택적으로 구현합니다. (시퀀스는 미구현)

### 2) DialectBundle

여러 방언 인터페이스를 한 번에 전달하기 위한 번들:

```java
var mysql = new MySqlDialect();
var bundle = DialectBundle.builder(mysql, DatabaseType.MYSQL)
    .identity(mysql)
    .tableGenerator(mysql)
    // .sequence(...)  // MySQL은 미지정
    .build();
```

### 3) Visitor 체계 재구성

마이그레이션 처리 단위를 역할별 Visitor로 분리:

* `TableVisitor` : 테이블 생성/삭제/리네임
* `TableContentVisitor` : 컬럼/PK/인덱스/제약/관계의 추가/삭제/수정
* `TableGeneratorVisitor` : TableGenerator 추가/삭제/수정
* `SequenceVisitor` : 시퀀스 추가/삭제/수정
* 공통 상위: `SqlGeneratingVisitor` (생성 SQL 수집)

`VisitorFactory` 가 `DialectBundle`에 맞는 Visitor Provider를 만듭니다.
(MySQL의 경우 Table/Content 전용 visitor, TableGenerator 전용 visitor로 분리)

### 4) Builder + Contributor 패턴

SQL 생성은 **Builder**가 orchestration, **Contributor**가 구체 SQL 조각을 구성:

* **Builder**

    * `CreateTableBuilder`(Body + Post 단계)
    * `AlterTableBuilder`
    * `TableGeneratorBuilder`
* **Contributor 인터페이스**

    * 공통: `SqlContributor { int priority(); }`
    * 세부: `DdlContributor`, `TableGeneratorContributor`, `SequenceContributor` …
    * 마커: `TableBodyContributor`, `PostCreateContributor` 등

예) `CreateTableBuilder`에 기본 파츠를 한번에 추가:

```java
new CreateTableBuilder(entity.getTableName(), ddlDialect)
    .defaultsFrom(entity)   // 컬럼, 제약, 인덱스 Contributor 자동 주입
    .build();
```

### 5) MigrationGenerator 단계화

마이그레이션 생성 순서를 **명시적 단계**로 고정:

1. **Pre-Objects**: `Sequence`, `TableGenerator` (ADDED/MODIFIED)
2. **파괴적 변경**

    * ModifiedEntity의 **DROP Phase** (FK/인덱스/제약/컬럼 제거 등)
    * 테이블 **DROP/RENAME**
3. **구성적 변경**

    * 테이블 **CREATE**
    * ModifiedEntity의 **ALTER Phase** (컬럼/인덱스/제약 추가/수정)
4. **FK 추가 Phase** (참조 일관성 확보 후)
5. **Post-Objects**: `Sequence`, `TableGenerator` (DROPPED)

---

## Liquibase 생성 (WIP)

* `LiquibaseYamlGenerator` + `LiquibaseVisitor`
* Phase는 SQL과 동일한 순서를 따르며, ChangeSet ID는 `ChangeSetIdGenerator` 제공
* DTO 모델: `…Change` + `…Config` (Jackson 직렬화)
* `LiquibaseUtils`

    * `buildConstraints(...)`
    * `buildConstraintsWithoutPK(...)` (PK 제외 제약만 반영)
    * `createChangeSet(id, changes)`

### 새로 추가된 DTO (예시)

* `InsertDataChange` / `InsertDataConfig` / `ColumnValue`
  → 초기 데이터 삽입용 `insert` change 표현

---

## CLI 사용법

```bash
# 최신 두 개 스키마 스냅샷 비교 → SQL/LB 생성
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase \
  --force
```

옵션:

* `-p, --path` : 스키마 JSON 폴더 (기본 `build/classes/java/main/jinx`)
* `-d, --dialect` : `mysql` 등
* `--out` : 결과 저장 경로 (기본 `build/jinx`)
* `--rollback` : 롤백 SQL 생성
* `--liquibase` : Liquibase YAML 생성
* `--force` : 위험 변경(예: Enum 매핑 변경) 강행

스키마 파일은 `schema-YYYYMMDDHHmmss.json` 패턴으로 저장됩니다. CLI는 최신 2개를 비교합니다.

---

## 프로세서(Annotation Processor)

* JDK 17+
* 주요 핸들러

    * `EntityHandler`, `RelationshipHandler`, `InheritanceHandler`
    * `EmbeddedHandler`, `ElementCollectionHandler`
    * `ConstraintHandler`, `SequenceHandler`, `TableGeneratorHandler`
    * `ColumnHandler`(필드/타입 기반 Resolver), `AbstractColumnResolver`
* 출력: `SchemaModel` JSON

---

## 디렉터리(개략)

```
jinx-processor/         # Annotation Processor
jinx-migration/         # Diff → SQL/LB 생성기, Dialect/Visitor/Contributor/Builder
jinx-cli/               # picocli 기반 CLI
```

---

## 완료된 기능들

### ✅ 구현 완료
* **`@Access(PROPERTY)` 및 혼합 접근 전략 지원**: AttributeDescriptor 기반으로 완전히 지원 (FIELD/PROPERTY 혼합 처리)
* **복수 `@JoinColumns`/`inverseJoinColumns` 전부 반영 및 검증**: RelationshipHandler에서 완전 지원
* **SINGLE_TABLE 기본 Discriminator 컬럼/타입/길이 적용**: InheritanceHandler에서 구현 완료
* **`@OrderBy`/`@MapsId` 매핑 지원**: RelationshipHandler 및 EntityHandler에서 처리
* **`columnDefinition`/`defaultValue` 처리**: ColumnModel 및 ColumnConfig에 완전 반영

### 🚧 진행 중/부분 구현
* **JOINED 상속의 `@PrimaryKeyJoinColumn(s)` / `@SecondaryTable.pkJoinColumns` 복합키 처리**: PrimaryKeyJoinColumnModel 구현됨, 복합키 처리 추가 검증 필요
* **FK 컬럼 생성시 `nullable/unique/precision/scale/columnDefinition` 반영**: 기본 속성은 구현됨, 외래키 컬럼 생성 시 완전 반영 검증 필요

## 남은 작업 항목

* `@JoinColumn.foreignKey`, `@JoinTable.foreignKey` 이름 반영
* `@SecondaryTable` 의 `indexes`/`uniqueConstraints` 처리  
* `@CollectionTable(indexes/uniqueConstraints)`, `@MapKeyJoinColumn(s)` 등 Map 확장
* Dialect 기반 `AUTO` 전략 결정
* Bean Validation → DDL 힌트(선택)
* 단방향 `@OneToMany` FK 위치/전략 재검토

> 위 항목들은 이슈로 정리되어 있으며 단계적으로 반영 예정입니다.

---

## 개발 메모

* Java 17+, 빌드 도구는 Gradle/Maven 아무거나 OK
* Annotation Processing 활성화 필요
* DB별 방언 구현은 **인터페이스만 구현**하면 번들에 **선택적으로** 끼울 수 있습니다. (예: MySQL은 Sequence 미구현)

---

## 라이선스

미정 (WIP)

---

## 문의/기여

PR/이슈 환영합니다. 구조 개선/새 Dialect/테스트 케이스 추가 등 어떤 형태든 💛
(특히 Liquibase 모델/출력과 DDL 일관성 검증 테스트 환영)
