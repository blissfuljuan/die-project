# 0001 — SOLID and design patterns as backend standards

Status: accepted (2026-10-16). Applies to all new code; existing code is migrated by `docs/refactor-plan.md`.

## Decision
New and changed backend code follows SOLID and uses the patterns below **where they remove a real maintenance problem** (a second implementation exists or is planned, a switch grows with every feature, or a process boundary needs isolation). No interface or pattern for a single foreseeable implementation. Everything stays inside the MVC layout (`controller/view/model/service/repository`) enforced by `MvcArchitectureTest`.

## Pattern catalogue (use these names)
| Need | Pattern | Placement |
|---|---|---|
| Behaviour that varies by kind/mode/role (progression modes, artifact kinds, roster file formats, creation flows, role scopes) | **Strategy** + registry (`Map<Key, Strategy>` collected from beans) | rule strategies: `<module>/model/rule`; infrastructure strategies: `<module>/service/{port,adapter}` |
| Choosing/constructing a strategy or object from input | **Factory / registry** | `service` (e.g. `RosterReaderFactory`) |
| External system or replaceable infrastructure (Drive/S3/local storage, ClamAV, SMTP, queue) | **Port & Adapter** (hexagonal) + **Dependency Injection** | ports `service/port`, adapters `service/adapter`, wiring in `config` |
| Fixed skeleton with varying steps (lock class -> lock project -> re-check authorization -> check version -> mutate -> audit) | **Template Method** (or a small `MutationTemplate` helper taking lambdas) | `service` |
| One simple entry over a subsystem (authorization) | **Facade** (`AccessPolicy` delegating to `ClassAccess`, `ProjectAccess`, ...) | `access/service` |
| Persistence | **Repository** (already used); SQL scoping fragments as **Specification**-like value objects (`SqlScope`) | `repository`, `access/model` |
| Reactions that must not couple the actor to the reaction (audit, notifications, stage recomputation) | **Observer** via Spring `ApplicationEventPublisher` (after-commit listeners; same rule as `AfterCommit`) | events in `model`, listeners in `service` |
| Optional/compound result with many parameters | **Builder** only if >5 optional fields; otherwise records | `model` |
| Cross-cutting behaviour (throttling, timing, audit of a method) | **Decorator** / Spring AOP only when two or more services need it | `service` |
| Request-state transitions (submission draft -> official, stage status) | **State** as enum with behaviour or transition table, never scattered string compares | `model` |

## SOLID rules of thumb
- S: a class changes for one reason; services over ~250 lines or mixing queries, rules and orchestration get split.
- O: adding a mode/kind/format = adding a class (strategy) + registration, not editing a `switch`.
- L: implementations of a port pass the same contract test.
- I: consumers depend on the narrowest port (`ObjectReader`, not the whole provider).
- D: services depend on ports and `Clock`; adapters are instantiated only by Spring/`config`.

## Typed domain values
Roles, statuses and kinds ("STUDENT", "REPRESENTATIVE", "TEAM", "STUDENT_FORMED", "ARCHIVED", ...) are enums in `<module>/model`, mapped at the repository boundary. String literals for them in services are a defect.

## Consequences
- More small classes; each is easier to test (rules are plain unit tests).
- Refactors of existing code must be behaviour-preserving: the existing 161 tests (integration + authorization matrix) must pass unchanged, and structure changes are done in small steps.
- `MvcArchitectureTest` gains rules per seam (see `docs/p2-plan.md`).
