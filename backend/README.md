# AreaTherm Backend

Production Spring Boot backend for **AreaTherm** — a physics-based
decision-support platform for designing passive, energy-efficient shelters
in extreme climates. The frontend prototype (vanilla HTML/CSS/JS, the
original working implementation, no build step) lives one level up at
[`../app`](../app); this directory is the target production backend
described in [`../ARCHITECTURE.md`](../ARCHITECTURE.md). The two are not
connected yet — the frontend still runs its own in-browser physics engine.

This is **infrastructure only** — no LLM is wired up anywhere in this build
(see `ml/` below). `thermal/` and `optimization/` are a numerically-verified,
line-for-line port of the frontend's own `../app/js/engine.js` physics and
optimizer; nothing about the underlying calculations was changed or
reinterpreted in the port. `../DATABASE_SCHEMA.sql`, `../ARCHITECTURE.md`,
and `../API_SPEC.md` are this backend's design source of truth.

## Stack

Java 17 · Spring Boot 3.5.16 · Spring Data JPA · Spring Security (JWT) ·
Flyway · H2 (local) / MySQL 8 (containerized) · OpenPDF + Flying Saucer
(PDF reports) · springdoc-openapi.

## Architecture

```
api/            REST controllers (versioned /api/v1/...)
climate/        Location/ClimateProfile entities + service; live-fetch adapters are stubs
design/         ShelterDesign/Opening/ThermalMass/ComfortProfile + entity<->physics-record mapping
material/       Material entity + seeded catalog + MaterialCatalog builder for the optimizer
thermal/        Pure, framework-free physics engine (zero Spring/JPA deps — enforced by an ArchUnit test)
optimization/   Pure, framework-free 567-candidate optimizer (same purity guarantee)
simulation/     Simulation orchestration: entities -> physics records -> ThermalEngine -> persisted results
optimizationrun/  Optimization-run orchestration + persisted candidates
validation/     Validation-dataset entities (MAE/RMSE/MAPE/R² via ThermalEngine.validationStats)
report/         Real PDF generation (OpenPDF/Flying Saucer, not a stub)
ml/             Deliberate stub — SurrogatePredictor.isAvailable() always false, no external calls
security/       JWT auth (register/login), Spring Security 6 filter chain, BCrypt
audit/          Audit log entity (schema in place; write-side hook not yet wired)
config/         Async executor for the QUEUED -> RUNNING -> COMPLETE/FAILED job lifecycle
```

`thermal/` and `optimization/` are pure Java records + static methods —
no Spring, no JPA, reusable from anywhere. Every other package maps JPA
entities to/from those plain physics records at the boundary (see
`ShelterDesignService`/`ClimateProfileService`/`MaterialLibraryService`).

## Running locally (zero external dependencies)

From this directory:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Boots on port 8080 against a local H2 file database (`./data/areatherm`,
auto-created, Flyway-migrated on startup). Swagger UI at
`/swagger-ui.html`, H2 console at `/h2-console`. No global Maven install
needed — `mvnw`/`mvnw.cmd` bootstrap their own; only a JDK 17 is required.

## Running against MySQL (matches the documented production target)

```bash
podman compose up -d      # or: docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=docker
```

`docker-compose.yml` starts MySQL 8 only (Redis is documented as optional
in `../ARCHITECTURE.md` and isn't stood up here — nothing yet needs it).

## Tests

```bash
./mvnw test
```

9 tests: an ArchUnit check that `thermal`/`optimization` stay framework-free,
5 golden-file tests asserting the Java physics port against real captured
output from the frontend's own `../app/js/engine.js` (winter/summer, 15-min
timestep, PCM thermal mass, round geometry — see
`src/test/resources/physics`), and an optimizer smoke test (567-candidate
search, sensitivity analysis, window layout recommendation). All pass from
a clean clone using only the Maven wrapper — verified independently, not
assumed.

## API surface

Base path `/api/v1`, JWT bearer auth (`POST /auth/register`,
`POST /auth/login`; everything else under `/api/v1/**` requires a token).
See `../API_SPEC.md` for the full documented contract. Implemented: auth,
projects, locations, materials, comfort profiles, climate profiles
(user-provided, with optional hourly series — no live external fetch),
shelter designs, simulations (async `202` -> poll, paginated time series),
optimization runs, PDF reports. Not yet wired to a route: `/explain`,
`/sensitivity`, validation-dataset endpoints (the underlying engine support
exists in `thermal`/`validation` already).

## Known limitations / explicit non-goals (this pass)

- No live server-side climate fetch — `climate/` adapters are stubs, matching
  `../ARCHITECTURE.md`'s own "stubs today" framing for that package.
- `ml/` is a genuine stub — no ML surrogate, no LLM, by design.
- No Redis, no message broker.
- No Testcontainers yet (MySQL is verified by hand against a real container
  during development).
- The optimizer's candidate persistence (567 `ShelterDesign` + child rows per
  run) uses plain JPA saves, not batch inserts — ~20s per run locally, fine
  for a background async job but a reasonable target if this ever needs to
  be faster under load.
- Not yet connected to the frontend in `../app` — that still runs its own
  client-side physics engine independently.
