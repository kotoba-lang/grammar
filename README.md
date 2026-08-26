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
- `syntaxes/kotoba.tmLanguage.json (GENERATED projection for editors and github-linguist — do not edit)`

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

## The TextMate projection

`syntaxes/kotoba.tmLanguage.json` is the same catalog projected into the format
editors and [github-linguist](https://github.com/github-linguist/linguist)
read. Linguist requires a syntax grammar before GitHub can display Kotoba as a
language at all; `script/add-grammar` points at this repository.

```bash
nbb tools/gen-tmlanguage.cljs           # after editing the EDN
nbb tools/gen-tmlanguage.cljs --check   # gate: exit 1 stale, 2 cannot tell
```

It exists instead of aliasing `source.clojure` because Kotoba is Clojure-shaped
and Clojure's grammar says the wrong thing about it. `(atom x)`, `(eval x)`,
`(swap! …)` — the 31 heads in `:forbidden-heads` — are not ordinary calls the
standard library happens to lack; they are the no-ambient-authority invariant,
and the compiler fails closed on every one. This grammar scopes them
`invalid.illegal.forbidden-head.kotoba`, so the refusal shows up in the editor
before it shows up in a build.

`the-textmate-grammar-covers-every-forbidden-head` in the suite gates that
against the EDN with no dependencies. For a deeper check, `tools/verify-tmlanguage.cljs`
tokenizes real `.kotoba` source through the same engine VS Code and Linguist
use, and is a tool rather than a test so the library keeps its dependency-free
suite:

```bash
npm i --no-save vscode-textmate vscode-oniguruma
nbb tools/verify-tmlanguage.cljs path/to/*.kotoba
```

It earns the separation. An early generator escaped `-` and `/` as if they were
metacharacters outside a character class: every pattern still compiled,
`--check` still said FRESH, and every hyphenated head silently stopped
matching. Only tokenizing real source found it.

## Linguist status

Kotoba is **not yet in Linguist**, and `.gitattributes` cannot stand in:
`linguist-language=` only accepts names already in `languages.yml` and
silently ignores anything else.

Acceptance is gated on usage, not on paperwork.
[CONTRIBUTING.md](https://github.com/github-linguist/linguist/blob/main/CONTRIBUTING.md)
wants ≥2000 files per extension indexed in the last year excluding forks
(200 for extensions expected only once per repo, like a `Makefile`),
distributed across unique `:user/:repo` — and reviewers filter the primary
language owner's accounts out with `-user:` before assessing. For a language
whose sources all live in its own org, that filter is the whole story.

```bash
nbb tools/linguist-readiness.cljs   # 0 ready · 1 measured and short · 3 could not measure
```

As of 2026-08-26 it returns **3**, and the reason is worth stating plainly:
**GitHub's code search index does not cover this org's repositories.**
`kotoba-lang/murakumo` is public, not a fork, pushed the same day, and carries
36 `.kotoba` files on its default branch — and `repo:kotoba-lang/murakumo
extension:kotoba` returns 0. So the earlier line here, "measured 2026-08-24:
raw 27, owner-excluded 0", was not a measurement of the corpus. It was a
measurement of an index that cannot see the corpus, and the two are
indistinguishable from the outside: the API does not error, it returns a small
number. The script now proves the index can see a corpus it is known to contain
before it is allowed to report a shortfall, and refuses with 3 when it cannot.

What survives that correction is the part the filter decides: owner-excluded
usage is 0 because no account outside this org writes Kotoba yet. That is a
statement about adoption, not about indexing, and it is the one that gates the
submission. So the kit stays written and staged rather than filed —
[`linguist/PULL_REQUEST.md`](linguist/PULL_REQUEST.md),
[`linguist/languages.yml.entry`](linguist/languages.yml.entry) and
[`linguist/samples.edn`](linguist/samples.edn) are ready the day the meter
turns green. Filing before then gets the PR closed, and a closed PR is harder
to reopen than a late one is to file.

Which threshold applies is open. 1,183 of the 1,257 repositories holding
`.kotoba` hold exactly one — the shape of a decision core, not of a `.rb`
file — which is the 200 tier's own description. Nobody has put that to
Linguist, so `threshold` stays at 2000 rather than assuming the answer;
CONTRIBUTING invites the question as a discussion, which costs nothing and
cannot be closed the way a premature PR can.

The exit codes are three-valued deliberately: a run that could not reach the
corpus must not return what a run that measured and found it short returns.
`--self-test` checks the queries themselves, and drives each refusal path to
the exit code it claims — a typo'd `-user:` would report a healthier number
than the truth, and before this revision every refusal above returned 1.

`linguist/samples.edn` records provenance rather than copies — repo, path and
commit for each sample. A frozen duplicate here would be one more vendored copy
aging on its own schedule, which this repo family has already paid for once.

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
