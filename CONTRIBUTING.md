# Contributing to Jinx

먼저, Jinx에 관심 가져주셔서 감사합니다! 🙌  
이 문서는 기여자가 프로젝트에 기여할 수 있는 방법을 안내합니다.

---

## 🤝 어떻게 기여하나요?

1. **이슈 등록**
    - 버그, 기능 제안, 문서 개선 등은 [GitHub Issues](../../issues)에 등록해주세요.
    - 명확한 설명과 재현 방법을 적어주시면 큰 도움이 됩니다.

2. **Fork & Branch**
    - 이 저장소를 fork 후 새로운 branch를 생성해주세요.
    - 브랜치 이름은 `feature/`, `fix/`, `docs/` 등으로 시작하는 것을 권장합니다.  
      예: `feature/add-postgres-dialect`

3. **코드 스타일**
    - Java 17+ / Gradle 기반
    - [Lombok](https://projectlombok.org/) 사용
    - 테스트는 JUnit 5로 작성해주세요.
    - 가능하면 기존 테스트를 참고하여 단위 테스트를 함께 추가해주세요.

4. **커밋 규칙**
    - 커밋 메시지는 명확하고 간단히:  
      예) `fix: handle null tableName in ColumnKey`  
      예) `feat: add Liquibase YAML generator`

5. **PR 제출**
    - PR 제목은 간결하게 요약해주세요.
    - 변경 이유, 구현 내용, 관련 이슈 번호를 본문에 적어주시면 좋습니다.
    - CI가 통과된 경우에만 머지됩니다.

---

## 💡 기여 아이디어

- 새로운 DB Dialect 추가 (예: PostgreSQL, Oracle)
- SQL/Liquibase 출력 품질 개선
- 더 많은 테스트 케이스 작성
- 문서(README, Wiki, Javadoc) 개선

---

## 📜 License

기여 시, 모든 코드는 [Apache License 2.0](LICENSE)에 따라 배포된다는 점에 동의하는 것으로 간주됩니다.
