# Jinx — JPA → DDL SQL 마이그레이션 생성기

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yyubin/jinx-core.svg)](https://central.sonatype.com/artifact/io.github.yyubin/jinx-core)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yyubin.jinx)](https://plugins.gradle.org/plugin/io.github.yyubin.jinx)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jinx는 **컴파일 타임에 JPA 애노테이션을 분석**하여  
**스키마 스냅샷(JSON)**을 생성하고,  
스냅샷 간 차이를 비교해 **DDL SQL**을 자동으로 생성하는 도구입니다.

Liquibase YAML도 **출력 포맷 중 하나**로 지원하지만,  
**SQL이 주력이며 가장 많이 검증된 출력 결과**입니다.

**MySQL 우선 지원** | **JDK 21+ 필요** | **최신 버전: 0.0.22** | **JPA 3.2.0 지원**

---

## 왜 Jinx인가?

Jinx는 데이터베이스 스키마 변경을  
**명시적이고**, **검수 가능하며**, **자동화 친화적**으로 만들기 위해 존재합니다.

### 1. DDL 작성 시 발생하는 휴먼 에러 사전 차단

DDL을 직접 작성하지 않고 JPA 메타데이터에서 생성합니다.  
오타, 누락된 컬럼, 잘못된 제약 조건이 운영 환경으로 가는 것을 막습니다.

### 2. 개발자가 직접 검수 가능한 마이그레이션

Jinx는 사람이 읽을 수 있는 **순수 SQL 파일**을 생성합니다.  
스키마 변경 역시 애플리케이션 코드처럼 리뷰와 승인 과정을 거칠 수 있습니다.

### 3. CI/CD 파이프라인에 자연스럽게 통합

결과물이 SQL 파일이기 때문에  
별도의 런타임 도구 없이 기존 CI/CD 흐름에 쉽게 포함할 수 있습니다.  
실제 데이터베이스 연결도 필요하지 않습니다.

### 4. 데이터베이스 없이도 동작

스키마 분석과 diff는 스냅샷 파일만으로 수행됩니다.  
로컬이나 CI 환경에서 DB 없이도 마이그레이션을 생성·검증할 수 있습니다.

### 5. 별도 마이그레이션 런타임 없이 이력 관리 가능

생성된 SQL 파일을 Git에 커밋하면  
Git 자체가 스키마 변경 이력이 됩니다.

추가적인 마이그레이션 런타임 도입이 부담스럽다면,  
Jinx + Git만으로도 충분히 추적 가능한 구조를 만들 수 있습니다.

---

## 설계 철학

### 컴파일 타임 분석 (리플렉션 미사용)

Jinx는 **애노테이션 프로세싱 기반**으로  
컴파일 타임에 스키마를 분석합니다.

런타임 리플렉션에 의존하지 않으며,  
**리플렉션 기반 JPA 표준 모델을 그대로 따르지 않습니다.**

이는 의도적인 선택입니다.

* 결정적이고 재현 가능한 스키마 생성
* 런타임 메타데이터 요구사항 제거
* AOT 지향 빌드 파이프라인과의 높은 궁합

자바 생태계가 점점 리플렉션 사용을 줄이는 방향으로 가는 상황에서,  
Jinx는 자연스럽게 장기적인 빌드 환경 변화에 대응합니다.

> Jinx는 Hibernate와 같은 JPA 런타임을 대체하지 않습니다.  
> 스키마 분석과 마이그레이션 생성에만 집중합니다.

---

## 출력 포맷

### SQL (주력)

DDL SQL은 Jinx의 **1급 출력 포맷**이며  
가장 많은 테스트와 검증이 이루어집니다.

### Liquibase YAML (부가)

Liquibase를 이미 사용 중인 팀을 위해  
호환 가능한 출력 포맷으로 제공됩니다.

Liquibase는 핵심 모델이 아니라  
**SQL 생성 결과를 변환한 표현 방식 중 하나**입니다.

---

## 빠른 시작

### 1. 의존성 추가

```gradle
dependencies {
    annotationProcessor("io.github.yyubin:jinx-processor:0.0.22")
    implementation("io.github.yyubin:jinx-core:0.0.22")
}
````

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

컴파일 시 자동으로 생성됩니다.

```
build/classes/java/main/jinx/
```

파일 이름 형식:

```
schema-<yyyyMMddHHmmss>.json
```

---

### 4. 마이그레이션 생성 (CLI)

```bash
jinx db migrate \
  -p build/classes/java/main/jinx \
  -d mysql \
  --out build/jinx \
  --rollback \
  --liquibase
```

---

## Gradle 연동 (Spring Boot & JDK 21)

### 플러그인 적용

```kotlin
plugins {
    id("io.github.yyubin.jinx") version "0.0.22"
}
```

---

### DSL 설정 예시

```kotlin
jinx {
    profile.set("local")

    naming {
        maxLength.set(63)
        strategy.set("SNAKE_CASE")
    }

    database {
        dialect.set("mysql")
    }

    output {
        format.set("sql")
        directory.set("build/jinx")
    }
}
```

---

## CLI 옵션 요약

| 옵션                 | 설명                  |
| ------------------ | ------------------- |
| `db migrate`       | 스냅샷 diff 기반 SQL 생성  |
| `promote-baseline` | 현재 스냅샷을 기준선으로 승격    |
| `-d, --dialect`    | 데이터베이스 방언 (mysql 등) |
| `--rollback`       | 롤백 SQL 생성           |
| `--liquibase`      | Liquibase YAML 출력   |
| `--force`          | 파괴적 변경 허용           |

---

## 지원 기능

* 테이블 / 컬럼 / PK / 인덱스 / 제약 조건 diff
* 롤백 SQL 생성
* Liquibase YAML 출력
* MySQL 방언 기본 제공 (SPI로 확장 가능)

---

## 예제 & 테스트 저장소

[https://github.com/yyubin/jinx-test](https://github.com/yyubin/jinx-test)

---

## 기여

* 새로운 DB 방언 추가
* DDL / Liquibase 매핑 개선
* 테스트 및 문서 보강

PR과 이슈는 언제든 환영합니다.
