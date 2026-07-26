# grammar

Kotoba safe-subset grammar gate — the admissible source surface.

**Tier**: `T1`  **Role**: `library`

Split out of the overloaded core repos by ADR-2607266000 so that each
responsibility has exactly one owner and the dependency direction is
checkable from outside.

## Owns

- `kotoba.grammar (guest grammar gate)`
- `resources/kotoba/lang/guest-grammar.edn (the grammar itself)`

## Does not own

- compile
- lower to KIR
- decide capability policy

## Depends on

- nothing (contract/leaf tier)

## Test

```bash
clojure -M:test
```
