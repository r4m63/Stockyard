# Changelog

All notable changes to **Stockyard** are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

> **Pre-1.0 notice.** Stockyard находится в стадии разработки (`0.x.y`). API, схемы данных и архитектурные решения могут меняться между **минорными** релизами. Стабильность гарантируется только начиная с `1.0.0` (планируется к финальной защите курса).

## How this file is maintained

- Записи накапливаются в секции **`[Unreleased]`** при каждом `/committer` после code-коммитов.
- При вызове `/committer release patch|minor|major|auto` секция `[Unreleased]` фиксируется как `[X.Y.Z] - YYYY-MM-DD`, создаётся git-tag `vX.Y.Z`.
- Категории — стандартные Keep a Changelog: **Added**, **Changed**, **Deprecated**, **Removed**, **Fixed**, **Security**.
- Только **user-visible** изменения. Внутренние рефакторинги, тесты, форматирование — НЕ попадают в Changelog.

---

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

---

## [0.1.0] - 2026-05-09

### Added
- Архитектурный фундамент: 12 документов в `docs/architecture/` (контекст, структура, компоненты, развёртывание, коммуникация, данные, согласованность, масштабирование, наблюдаемость, сценарии, тестирование).
- 6 архитектурных решений (`docs/architecture/adr/ADR-001` … `ADR-006`).
- `REQUIREMENTS.md` с требованиями курса РМП.
- `HOWTO.md` с описанием ролевого workflow разработки.
- `CLAUDE.md` с контекстом проекта для AI-assisted разработки.
- Конфигурация `.claude/` с 8 слэш-командами (`architect`, `backend`, `frontend`, `mobile`, `tester`, `reviewer`, `committer`, `task`) и системой task ledger.
- Инфраструктура версионирования: `VERSION` + `CHANGELOG.md` (Keep a Changelog 1.1.0).

---

<!--
Compare links — обновляются автоматически /committer release.
Замени <org>/<repo> на реальный путь после публикации репозитория.
-->

[Unreleased]: https://github.com/r4m63/Stockyard/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/r4m63/Stockyard/releases/tag/v0.1.0
