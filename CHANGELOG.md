## 📝 CHANGELOG

### [Released - 0.0.22]

#### 🐛 Fixed

* 컬럼 리네임 시 인덱스, 제약조건, 외래키 SQL 생성 순서가 잘못되어
  **존재하지 않는 컬럼을 참조하는 인덱스가 먼저 생성되던 문제 수정**
* MySQL 마이그레이션에서 `ModifyContributor`가
  `DROP + CREATE`를 한 번에 수행하던 구조로 인해 발생하던
  **컬럼 의존성 순서 오류 해결**

#### 🛠 Changed

* 인덱스 / 제약조건 / 외래키 수정 로직을
  `ModifyContributor` 방식에서 **Drop + Add Contributor 분리 방식**으로 변경
* priority 체계에 맞게 다음 순서가 보장되도록 개선:

  ```
  DROP (Index / Constraint / FK)
  → COLUMN DROP / ADD
  → ADD (Index / Constraint / FK)
  ```

#### 🧩 Internal

* `MySqlMigrationVisitor`에서 다음 메서드 동작 변경:

  * `visitModifiedIndex`
  * `visitModifiedConstraint`
  * `visitModifiedRelationship`
* 내부적으로 더 이상 사용되지 않는 Contributor 클래스 발생:

  * `IndexModifyContributor`
  * `ConstraintModifyContributor`
  * `RelationshipModifyContributor`
    (추후 제거 가능)

---

### 🔍 영향 범위

* 컬럼 리네임과 함께 인덱스 / 제약조건 / 외래키가 변경되는 모든 MySQL 마이그레이션 시나리오
* 기존 마이그레이션 SQL의 **실행 안정성 및 순서 보장성 향상**

---

### ✅ 검증

* 컬럼 리네임 + 인덱스 변경 시나리오에서 SQL 생성 순서 확인
* 실제 MySQL 환경에서 마이그레이션 SQL 정상 실행 확인
