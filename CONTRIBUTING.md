# Contributing to Jinx

## Rules

1. Open an issue first for bugs, feature requests, or documentation changes.
2. Work from a fork and use a focused branch such as `feature/...`, `fix/...`, or `docs/...`.
3. Keep each change small and scoped to one concern.
4. Follow the existing project structure and naming patterns.
5. Target JDK 21+ and the current Gradle setup.
6. Use Lombok only where the codebase already uses it naturally.
7. Add or update tests for behavior changes. Prefer JUnit 5 and match existing test style.
8. Keep commit messages short and clear, for example `fix: handle null tableName`.
9. Open a pull request with a concise title and a short explanation of why the change is needed.
10. Reference related issues in the pull request when applicable.
11. Do not merge changes with failing CI.

## Good Contributions

- bug fixes
- new dialect support
- SQL or Liquibase output improvements
- test coverage improvements
- documentation cleanup

## License

By contributing, you agree that your changes are provided under the [Apache License 2.0](LICENSE).
