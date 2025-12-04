# Jinx — JPA → DDL SQL / Liquibase Migration Generator

> 📖 **Read in English**: [README_EN.md](./README_EN.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx는 JPA 애노테이션을 스캔해 **스키마 스냅샷(JSON)** 을 생성하고, 이전 스냅샷과 비교해 **DDL·Liquibase Migration SQL/YAML**을 자동 생성하는 도구입니다.

**MySQL 우선 지원** | **JDK 21+ 필요** | **최신 버전 0.0.18** | **JPA 3.2.0 지원**

---

## 왜 Jinx인가?

* **JPA 애노테이션 기반 자동 스키마 분석**
  수동 DDL 작성 없이 JSON 스냅샷을 통해 변경점을 추적합니다.
* **Diff 기반 Migration 자동 생성**
  rename, nullable 조정, index 추가 등 변경을 자동 검출합니다.
* **DDL + Liquibase YAML 동시 출력**
  기존 마이그레이션 환경과 자연스럽게 연결됩니다.
* **Gradle·Java 프로젝트에 쉽게 통합 가능**

샘플 엔티티 및 출력물: [https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## 빠른 시작

### 1. 의존성 추가

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.0.18")
    implementation("io.github.yyubin:jinx-core:0.0.18")
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

빌드 수행 시 다음 경로에 스냅샷이 자동 생성됩니다

```
build/classes/java/main/jinx/
```

스냅샷 파일명 규칙

```
schema-<yyyyMMddHHmmss>.json
```

예시

```json
{
  "entities": {
    "org.example.Bird": {
      "tableName": "Bird",
      "columns": {
        "bird::id":   { "type": "BIGINT", "primaryKey": true, "autoIncrement": true },
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

CLI를 직접 사용할 경우

```bash
jinx migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

출력 예시(SQL)

```sql
CREATE TABLE `Bird` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255),
  `zoo_id` BIGINT,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE INDEX `ix_bird__zoo_id` ON `Bird` (`zoo_id`);
```

출력 예시(Liquibase YAML)

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
```

---

## Gradle 통합 (Spring Boot & JDK 21 기준)

아래는 **실제 Spring Boot 프로젝트에서 즉시 사용 가능한 설정**입니다.

### 1) jinx-cli 전용 configuration 추가

```gradle
val jinxCli by configurations.creating

dependencies {
    jinxCli("io.github.yyubin:jinx-cli:0.0.18")
}
```

### 2) 마이그레이션 실행 태스크 등록

```gradle
tasks.register<JavaExec>("jinxMigrate") {
    group = "jinx"
    classpath = configurations["jinxCli"]
    mainClass.set("org.jinx.cli.JinxCli")

    // 최신 스냅샷을 사용하도록
    dependsOn("classes")

    args("db", "migrate", "-d", "mysql")
}
```

### 3) baseline 갱신 태스크 등록

```gradle
tasks.register<JavaExec>("jinxPromoteBaseline") {
    group = "jinx"
    classpath = configurations["jinxCli"]
    mainClass.set("org.jinx.cli.JinxCli")

    dependsOn("classes")
    args("db", "promote-baseline", "--force")
}
```

---

### Gradle에서 실행

최신 스냅샷 기준으로 SQL/YAML 생성

```bash
./gradlew jinxMigrate
```

스냅샷을 baseline으로 승격

```bash
./gradlew jinxPromoteBaseline
```

---

## Gradle Plugin (게시 대기 중)

Jinx Gradle Plugin은 현재 **Gradle Plugin Portal 승인 대기 중**입니다.
승인 후에는 아래처럼 바로 적용할 수 있습니다.

```kotlin
plugins {
    id("org.jinx.gradle") version "0.0.18"
}
```

승인 전에는 다음 방식으로 사용 가능합니다.

```kotlin
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.jinx:jinx-gradle:0.0.18")
    }
}

apply(plugin = "org.jinx.gradle")
```

---

## DSL 사용 예시

```kotlin
jinx {
    profile.set("local")

    naming {
        maxLength.set(63)
        strategy.set("SNAKE_CASE")
    }

    database {
        dialect.set("mysql")
        url.set("jdbc:mysql://localhost:3306/app")
    }

    output {
        format.set("liquibase")
        directory.set("build/jinx")
    }
}
```

---

## 게시 상태 안내

플러그인은 현재 Gradle Plugin Portal 심사 중이며,
승인되면 해당 주소에서 사용 가능합니다.

```
https://plugins.gradle.org/plugin/org.jinx.gradle
```

---

## CLI 옵션 요약

| 옵션                 | 설명                          |
| ------------------ | --------------------------- |
| `migrate`          | 최신 스냅샷 2개 비교 후 Migration 생성 |
| `promote-baseline` | 현재 스냅샷을 baseline으로 승격       |
| `-d, --dialect`    | DB 방언 (mysql 등)             |
| `--rollback`       | 롤백 SQL 출력                   |
| `--liquibase`      | Liquibase YAML 출력           |
| `--force`          | 위험 변경 강제 적용                 |

---

## 현재 지원 기능

* 테이블/컬럼/PK/인덱스/제약조건 생성 및 변경 감지
* 자동 rename 탐지(점진적 개선 중)
* 롤백 SQL 생성
* Liquibase YAML 생성
* MySQL Dialect 기본 지원
  (SPI 확장으로 다른 DB 방언 직접 추가 가능)

---

## 예시 및 테스트 프로젝트

더 많은 예시(엔티티, 스냅샷, SQL):

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## 기여

* 신규 DB 방언 추가
* DDL/Liquibase 규칙 보완
* 테스트 케이스/문서 기여

PR과 이슈 환영
