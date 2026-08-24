# Draft — do not file until `linguist-readiness.cljs` exits 0

```bash
nbb tools/linguist-readiness.cljs
```

`0` file it · `1` not yet, the number is in the output · `3` the run measured
nothing, which is not the same as either.

**Measured 2026-08-24: raw 27, owner-excluded 0, threshold 2000.** Filing today
gets the PR closed, and CONTRIBUTING.md says so in as many words: *"we do not
accept PRs for very new or hobby languages, and will close any such PRs that
attempt to add them."* A closed PR is harder to reopen than a late one is to
file.

---

## Description

Adds **Kotoba**, a capability-safe application language with an S-expression
surface. Kotoba compiles ahead-of-time to WebAssembly, restricted ESM and
native code. It has no ambient authority — no `eval`, `require`, `atom`,
interop or macros — and every external effect passes a declared, typed
capability.

- Language: https://github.com/kotoba-lang/kotoba
- Compiler: https://github.com/kotoba-lang/amu
- Grammar (this PR's `tm_scope`): https://github.com/kotoba-lang/grammar — MIT

## Checklist

- [ ] `languages.yml` entry added — see `linguist/languages.yml.entry`
- [ ] `script/add-grammar https://github.com/kotoba-lang/grammar`
- [ ] Samples added under `samples/Kotoba/` — see `linguist/samples.edn` for
      repo, path and commit of each
- [ ] `script/update-ids`
- [ ] Search results and counts filled in below

## Usage evidence

| query | files |
|---|---|
| [`extension:kotoba`](https://github.com/search?q=extension%3Akotoba&type=code) | _fill in_ |
| [`extension:kotoba -user:kotoba-lang`](https://github.com/search?q=extension%3Akotoba+-user%3Akotoba-lang&type=code) | _fill in_ |

Paste the count shown at the top of each result page. The second row is the
one reviewers assess — CONTRIBUTING.md: *"If particular users are showing a
high proportion of the results, for example the primary language owner, we
will filter out those users using `-user:<username>`."*

## Sample licensing

All four samples are **Apache-2.0**, taken verbatim from `kotoba-lang`
repositories, and are production sources rather than tutorial code.
`linguist/samples.edn` records the exact repo, path and commit for each, plus
what each one demonstrates.

## Extension conflicts

None. `.kotoba` is not claimed by any language in `languages.yml`, so no
disambiguating heuristic is required.
