# Jinx

**Jinx**는 Java 기반 ORM 프로젝트에서 엔티티 모델을 분석하여 데이터베이스 마이그레이션 SQL을 생성하는 **스키마 기반 마이그레이션 도구**입니다.

> ⚠️ 현재 개발 중이며, 아직 릴리즈되지 않았습니다. 사용은 자유지만 책임은 사용자에게 있습니다.
>

---

## 특징

- JPA 엔티티 모델을 기반으로 **스키마 구조(JSON)** 자동 생성
- 이전 버전과 새 버전 스키마를 비교하여 **변경 감지**
- 변경 사항에 따라 **DDL (SQL) 마이그레이션 자동 생성**
- Picocli 기반 **CLI 지원**
- 현재 **MySQL** 방언(dialect) 지원 중 (추가 방언 개발 예정)

---

## 예시

```bash
jinx migrate \
  --before schema-old.json \
  --after schema-new.json \
  --dialect mysql \
  --output migration.sql

```

---

## 상태

-  현재 내부 테스트 중
-  추가 Dialect(PostgreSQL 등) 작업 예정
-  Maven 릴리즈 전

---

## 라이선스

MIT