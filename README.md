# grammar

Kotoba safe-subset grammar gate — the admissible source surface.

**Tier**: `T1`  **Role**: `library`

Split out of the overloaded core repos by ADR-2607266000 so that each
responsibility has exactly one owner and the dependency direction is
checkable from outside.

## Owns

- `kotoba.grammar (guest grammar gate)`
- `resources/kotoba/lang/guest-grammar.edn (the grammar itself)`
- `src/kotoba/grammar/embedded.cljc (GENERATED projection of it — do not edit)`

## Does not own

- compile
- lower to KIR
- decide capability policy

## Depends on

- nothing (contract/leaf tier)

## Portable

`kotoba.grammar` is `.cljc` and runs on the JVM and on nbb. The catalog is
compiled in rather than read at runtime — there is no portable `io/resource`,
and reading `resources/<path>` relative to the working directory is right only
while this library is the root project. Edit the EDN; regenerate the
projection; `--check` refuses to let them drift.

```bash
nbb tools/gen-embedded.cljs           # after editing the EDN
nbb tools/gen-embedded.cljs --check   # gate: exit 1 stale, 2 cannot tell
```

One thing did NOT become portable, deliberately: the live host-import surface.
`admitted-heads` unions the catalog with the ops on the runtime capability
contract, and that contract is read by `kotoba.core.contracts`, which defines
the reader under `#?(:clj …)`. It is 110 ops expanding to 220 of 402 admitted
heads, so pretending it is empty off the JVM would report every capability
call in a guest module as a grammar violation. Instead the ops are an explicit
argument (`strict-problems` and `admitted-heads` both take them), and the
no-argument forms answer nil off the JVM — a strict check with no host surface
returns one `:grammar-unavailable` problem rather than a list of invented ones.

## Test

```bash
clojure -M:test                       # JVM

# nbb has no dependency resolution, so name the pinned git dep explicitly
CC=~/.gitlibs/libs/io.github.kotoba-lang/kotoba-core-contracts/<sha>/src
nbb --classpath "src:test:$CC" test/run_portable.cljs

# prove the suite can fail — run the table under both runtimes
nbb tools/check-mutations.cljs
nbb tools/mutate.cljs
MUTATE_CMD="nbb --classpath src:test:$CC test/run_portable.cljs" nbb tools/mutate.cljs
```
