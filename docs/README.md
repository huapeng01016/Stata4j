# Stata4j developer docs

These documents record the September 2026 rework of the reader: what changed and why, how the `.dta` format is handled, and how it is tested. For how to *use* the library, see the top-level [README](../README.md), [API reference](../API.md) and [Getting Started](../GETTING_STARTED.md).

| Document | Contents |
|---|---|
| [changes.md](changes.md) | What was done: the problems found, the fixes, new features, breaking changes and a migration guide |
| [dta-format.md](dta-format.md) | How each `.dta` release is parsed: layouts, type codes, missing values, strL, value labels, and the assumption behind formats 120/121 |
| [testing.md](testing.md) | Test layout, the fixture files and how to regenerate them, and known gaps in coverage |
