---
sidebar_position: 1
---

# Jinx 시작하기

**Jinx**는 JPA 애노테이션을 스캔해 스키마 스냅샷(JSON)을 만들고, 이전 스냅샷과 비교하여 **DB 마이그레이션 SQL**과 **Liquibase YAML**을 자동 생성하는 도구입니다.

**현재 MySQL 우선 지원** | **JDK 21+ 필요** | **최신 릴리즈: 0.0.19**

## 빠른 시작

### 1. 의존성 추가

```gradle
dependencies {
    annotationProcessor "io.github.yyubin:jinx-processor:0.0.19"
    implementation "io.github.yyubin:jinx-core:0.0.19"
}
```

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

### 3. 스냅샷 생성

빌드하면 `build/classes/java/main/jinx/` 경로에 스키마 스냅샷이 생성됩니다.

**파일명 규칙**: `schema-<yyyyMMddHHmmss>.json` (KST 기준, 충돌 방지)

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

## 더 자세한 정보

더 많은 예시와 설정 방법은 [GitHub Repository](https://github.com/yyubin/jinx)를 참고하세요.
