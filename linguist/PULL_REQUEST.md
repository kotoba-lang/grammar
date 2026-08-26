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
| [`NOT is:fork path:*.kotoba`](https://github.com/search?type=code&q=NOT%20is%3Afork%20path%3A*.kotoba) | _fill in_ |
| [`NOT is:fork path:*.kotoba` with the owning accounts excluded](https://github.com/search?type=code&q=NOT%20is%3Afork%20path%3A*.kotoba%20-user%3Akotoba-lang%20-user%3Acom-junkawasaki%20-user%3Aetzhayyim%20-user%3Agftdcojp%20-user%3Acloud-itonami%20-user%3Anet-kotobase%20-user%3Anetwork-awai) | _fill in_ |

Paste the count shown at the top of each result page. The second row is the
one reviewers assess — CONTRIBUTING.md: *"If particular users are showing a
high proportion of the results, for example the primary language owner, we
will filter out those users using `-user:<username>`."*

> The qualifier is `path:*.kotoba`, not `extension:kotoba`. GitHub's code
> search rejects `extension:` outright — *"Unrecognized qualifier. Looking for
> a file extension? Try using the path qualifier"* — and renders **0 files**
> above the rejection. An earlier draft of this file linked the `extension:`
> form, so a reviewer following the link would have been shown zero usage for
> a language that had some. The REST API is the mirror image: it accepts
> `extension:` and returns 0 for `path:*.kotoba`. The two surfaces do not
> share an index or a query language, and only the web one is the assessment.
>
> `NOT is:fork` matches CONTRIBUTING's own worked example
> (`NOT is:fork path:*.boot`) and the requirement's wording, "excluding forks".
> It does not move this corpus today — 58 either way — but a query that agrees
> with the requirement by accident stops agreeing the first time somebody forks
> a repository.

Measured 2026-08-26: **58** and **0**. Not submittable — see
`tools/linguist-readiness.cljs`.

## Sample licensing

All four samples are **Apache-2.0**, taken verbatim from `kotoba-lang`
repositories, and are production sources rather than tutorial code.
`linguist/samples.edn` records the exact repo, path and commit for each, plus
what each one demonstrates.

## Extension conflicts

None. `.kotoba` is not claimed by any language in `languages.yml`, so no
disambiguating heuristic is required.
