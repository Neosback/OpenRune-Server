# OpenRune Studio contract fixtures

These fixtures mirror the canonical frontend fixtures from:

- Repository: `Neosback/OR-ContentTS`
- Baseline commit: `5de9d2e9f16520a1a3d93a03e6087452db24cac6`
- `client/src/project/fixtures/edit-format-v1.golden.json`
- `client/src/project/fixtures/project-v1.golden.json`

They intentionally remain byte-readable JSON fixtures so Kotlin tests prove that OpenRune Server can consume documents emitted by the Studio contract without frontend-specific runtime types.

When either v1 fixture changes intentionally, update the matching fixture and parity assertions here in the same integration change.
