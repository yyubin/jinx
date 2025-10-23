# 🧾 CHANGELOG

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
