# Jinx — JPA → DDL / Liquibase Migration Generator

> 📖 **Read in English**: [README_EN.md](./README_EN.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx는 JPA 애노테이션을 스캔해 **스키마 스냅샷(JSON)** 을 만들고, 이전 스냅샷과 비교하여 **DB 마이그레이션 SQL**과 **Liquibase YAML**을 자동 생성하는 도구입니다.

**현재 MySQL 우선 지원** | **JDK 21+ 필요** | **최신 릴리즈: 0.0.16** | **JPA 3.2.0 이상 지원**

## 왜 Jinx인가?

- **JPA 애노테이션 → 스냅샷 → diff 기반 자동 생성**: 수동 DDL 작성 불필요
- **DDL + Liquibase YAML 동시 출력**: 기존 마이그레이션 도구와 호환
- **Phase 기반 점진적 변경**: rename/backfill 등 안전한 마이그레이션 지원 (로드맵)

샘플 엔티티/JSON/SQL은 [jinx-test 저장소](https://github.com/yyubin/jinx-test)에서 확인할 수 있습니다.

---

## 빠른 시작

### 1. 의존성 추가

```gradle
dependencies {
    annotationProcessor "io.github.yyubin:jinx-processor:0.0.16"
    implementation "io.github.yyubin:jinx-core:0.0.16"
}
```

---

### 2. 엔티티 작성

```java
@Entity
public class Bird {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private Long zooId;
}

```

---

### 3. 스냅샷 생성

빌드하면 `build/classes/java/main/jinx/` 경로에 스키마 스냅샷이 생성됩니다.

**파일명 규칙**: `schema-<yyyyMMddHHmmss>.json` (KST 기준, 충돌 방지)

**인덱스 자동 유추**: `Long zooId` → `zoo_id` 컬럼 → `ix_<table>__<column>` 인덱스 생성

예시: `schema-20250922010911.json`

```json
{
  "entities": {
    "org.example.Bird": {
      "tableName": "Bird",
      "columns": {
        "bird::id": { "type": "BIGINT", "primaryKey": true, "autoIncrement": true },
        "bird::name": { "type": "VARCHAR(255)" },
        "bird::zoo_id": { "type": "BIGINT" }
      },
      "indexes": {
        "ix_bird__zoo_id": { "columns": ["zoo_id"] }
      }
    }
  }
}
```

---

### 4. 마이그레이션 실행

최신 2개의 스냅샷을 비교해 SQL과 Liquibase YAML을 생성합니다.

```bash
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

생성된 SQL 예시:

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);

```

Liquibase YAML도 함께 출력됩니다:

```yaml
databaseChangeLog:
  - changeSet:
      id: 20250922010911-1
      author: jinx
      changes:
        - createTable:
            tableName: Bird
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: VARCHAR(255)
              - column:
                  name: zoo_id
                  type: BIGINT

```

---

## 예시 프로젝트

더 많은 엔티티, 스냅샷, 마이그레이션 SQL 샘플은 [jinx-test](https://github.com/yyubin/jinx-test) 저장소에서 확인하세요.

---

## CLI 옵션

| 옵션 | 설명 | 기본값 |
| --- | --- | --- |
| `-p, --path` | 스키마 JSON 디렉토리 | `build/classes/java/main/jinx` |
| `-d, --dialect` | DB 방언 (예: `mysql`) | - |
| `--out` | 결과 저장 경로 | `build/jinx` |
| `--rollback` | 롤백 SQL 생성 | 비활성 |
| `--liquibase` | Liquibase YAML 생성 | 비활성 |
| `--force` | 위험 변경 강제 허용 | 비활성 |

---

---

## Advanced: Gradle 통합

**전용 configuration + JavaExec 태스크**를 추가하면, 빌드 산출물(스키마 스냅샷) 생성 → CLI 실행까지 한 번에 돌릴 수 있습니다.

**`build.gradle` 예시 (Gradle 8+, JDK 21)**

```
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.5'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'org.jinx'
version = '0.0.1-SNAPSHOT'
description = 'jinx-test'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
    jinxCli
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.projectlombok:lombok'
    runtimeOnly 'com.mysql:mysql-connector-j'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    // Jinx (0.0.16)
    implementation       "io.github.yyubin:jinx-core:0.0.16"
    annotationProcessor  "io.github.yyubin:jinx-processor:0.0.16"

    // CLI (transitive 포함)
    jinxCli              "io.github.yyubin:jinx-cli:0.0.16"
}

// gradle -P 속성으로 오버라이드 가능한 기본값
ext.defaultJinxPath      = "build/classes/java/main/jinx"
ext.defaultJinxDialect   = "mysql"
ext.defaultJinxOut       = "build/jinx"

tasks.register('jinxMigrate', JavaExec) {
    group = 'jinx'
    description = 'Run Jinx CLI to generate migration SQL / Liquibase YAML'
    classpath = configurations.jinxCli
    mainClass = 'org.jinx.cli.JinxCli'

    // 프로젝트 속성으로 덮어쓰기 가능
    def p  = (String) (project.findProperty("jinxPath")    ?: defaultJinxPath)
    def d  = (String) (project.findProperty("jinxDialect") ?: defaultJinxDialect)
    def out= (String) (project.findProperty("jinxOut")     ?: defaultJinxOut)

    // 플래그성 옵션: 존재 여부만 체크
    def withLb  = project.hasProperty("jinxLiquibase")
    def withRb  = project.hasProperty("jinxRollback")
    def force   = project.hasProperty("jinxForce")

    // CLI 서브커맨드: migrate
    args 'migrate',
         '-p', p,
         '-d', d,
         '--out', out

    if (withLb) args '--liquibase'
    if (withRb) args '--rollback'
    if (force)  args '--force'

    // 스냅샷이 먼저 생성되도록
    dependsOn 'classes'
}

tasks.named('test') {
    useJUnitPlatform()
}

```

### 실행 예시

기본값(경로/방언/출력경로)으로 실행

```bash
./gradlew jinxMigrate

```

파라미터를 바꿔 실행(프로젝트 속성으로 오버라이드)

```bash
./gradlew jinxMigrate \
  -PjinxPath=build/classes/java/main/jinx \
  -PjinxDialect=mysql \
  -PjinxOut=build/jinx \
  -PjinxLiquibase \
  -PjinxRollback

```

Windows PowerShell:

```powershell
.\gradlew.bat jinxMigrate -PjinxDialect=mysql -PjinxLiquibase

```

> 참고: Gradle의 --args는 기본 run 태스크용 옵션입니다. 위처럼 프로젝트 속성(-P) 으로 받는 게 커스텀 JavaExec 태스크에선 가장 깔끔하고 이식성도 좋습니다.
>

### 체크리스트(자주 나오는 이슈)

- **스냅샷이 하나뿐**이면 “최신 2개 비교”가 불가합니다. 새 빌드를 한 번 더 돌려 스냅샷을 2개 이상 확보하세요.
- IDE에서 **Annotation Processing 활성화**가 꺼져 있으면 스냅샷이 안 생깁니다.
- 방언(`d`)은 현재 `mysql` 우선 지원입니다. 다른 DB는 SPI 인터페이스(`org.jinx.dialect.Dialect`) 구현으로 확장 가능합니다.

### 더 많은 예시

실제 엔티티/스냅샷/SQL 산출물은 여기에서 확인하세요:

**jinx-test** → https://github.com/yyubin/jinx-test

---

## 현재 지원 기능

- 테이블/컬럼/PK/인덱스/제약/리네임 등 주요 DDL
- ID 전략: `IDENTITY`, `SEQUENCE`, `TABLE`
- Liquibase YAML 출력
- MySQL Dialect 기본 제공 (다른 DB는 SPI 인터페이스 확장으로 추가 가능)

---

## 라이센스
Jinx is licensed under the [Apache License 2.0](LICENSE).

---

## 기여

- 새로운 DB 방언 추가
- DDL ↔ Liquibase 매핑 검증
- 테스트 케이스 확충
- 문서 보강

PR과 이슈 환영합니다.