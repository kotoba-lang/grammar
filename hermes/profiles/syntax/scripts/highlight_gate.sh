#!/usr/bin/env bash
# syntax-bot: Kotoba highlighting health check across all surfaces.
# Exit 0 = all monitored surfaces healthy (or reported); non-zero = a check failed hard.
#
# 2026-09-13: tools were cut over from nbb .cljs to kbb .cljk (ADR-2609112000).
# This script previously called `nbb tools/*.cljs`, which ENOENT'd after the
# rename — making step 1 a false "ok" (generator produced nothing, diff equal)
# and step 2 false FAILs. Now calls `kbb --backend sci tools/*.cljk`.
set -u
ROOT="~/github/com-junkawasaki"
GRAM="$ROOT/orgs/kotoba-lang/grammar"
CLOUD="$ROOT/orgs/kotoba-lang/app-kotoba-cloud"
KBB="kbb --backend sci"
fail=0

echo "== 1. grammar repo: GENERATED files match generators =="
cd "$GRAM" || exit 1
git fetch origin main --quiet 2>/dev/null
if git merge-base --is-ancestor HEAD origin/main 2>/dev/null; then
  git merge --ff-only origin/main 2>&1 | tail -1
else
  echo "WARN: grammar checkout diverged; checking local state only"
fi
# NOTE: the check must FAIL when the generator errors (e.g. missing file),
# not silently pass because nothing changed. Run generator to a scratch copy
# and require it to exit 0 AND produce identical bytes.
rm -rf /tmp/gate-gen && mkdir -p /tmp/gate-gen
cp syntaxes/kotoba.tmLanguage.json /tmp/gate-gen/before.json
if $KBB tools/gen-tmlanguage.cljk --check >/tmp/gate-gen/check.out 2>&1; then
  echo "ok  - tmLanguage.json matches generator output (--check FRESH)"
else
  rc=$?
  echo "FAIL- gen-tmlanguage --check exit=$rc (see /tmp/gate-gen/check.out)"
  head -5 /tmp/gate-gen/check.out
  fail=1
fi

echo "== 2. grammar gate: verify-tmlanguage + linguist readiness =="
SAMPLE=$(find "$ROOT/orgs" -name "*.kotoba" -not -path "*/node_modules/*" 2>/dev/null | head -1)
if [ -n "$SAMPLE" ]; then
  if timeout 240 $KBB tools/verify-tmlanguage.cljk "$SAMPLE" >/tmp/gate-verify.out 2>&1; then
    echo "ok  - verify-tmlanguage passes (sample: $(basename "$SAMPLE")): $(grep -m1 PASS /tmp/gate-verify.out)"
  else
    echo "FAIL- verify-tmlanguage failed on $SAMPLE"; fail=1
  fi
else
  echo "FAIL- no .kotoba sample found for verify-tmlanguage"; fail=1
fi
timeout 200 $KBB tools/linguist-readiness.cljk >/dev/null 2>&1
RD=$?
if [ $RD -eq 0 ]; then echo "ok  - linguist readiness exits 0 (PR can be filed)"
elif [ $RD -eq 3 ]; then
  if [ -f /tmp/linguist-readiness-counts.txt ]; then
    echo "MANUAL- readiness needs fresh github-search counts (see /tmp/linguist-readiness-counts.txt); last: $(cat /tmp/linguist-readiness-counts.txt)"
  else
    echo "MANUAL- readiness exit=3: open the two github search URLs in the readiness output, read the file counts, pass them back via --raw N --assessed N"
  fi
else echo "FAIL- linguist readiness exit=$RD (1=not ready)"; fail=1; fi

echo "== 3. GitHub surface: .kotoba language detection =="
# .kotoba language on github.com only after the linguist PR lands; report state:
PR=$(gh api "search/issues?q=repo:github-linguist/linguist+type:pr+kotoba+in:title" --jq '.total_count' 2>/dev/null)
if [ "${PR:-0}" != "0" ]; then
  echo "ok  - linguist PR exists (#$PR)"
else
  echo "MISS- linguist PR NOT filed yet — .kotoba files show as unrecognized on github.com"
fi

echo "== 4. kotoba.cloud surface: highlighter presence =="
HTTP=$(curl -sS -o /tmp/kc.html -w "%{http_code}" --max-time 20 https://kotoba.cloud 2>/dev/null)
if [ "$HTTP" = "200" ]; then
  if grep -q "kc-command" /tmp/kc.html; then
    # real highlight markup = shiki/hljs/prism assets OR <span class="..."> INSIDE
    # the <pre> blocks. Word matches in prose are false positives.
    if grep -qE 'shiki|hljs|prism|highlight\.js' /tmp/kc.html \
       || grep -A2 'class="kc-command"' /tmp/kc.html | grep -qE '<span class='; then
      echo "ok  - kotoba.cloud code blocks carry highlight markup"
    else
      echo "MISS- kotoba.cloud serves <pre class=kc-command> as PLAIN text (no shiki/hljs/span tokens)"
      echo "      -> remediation: wire kotoba.grammar.highlight (portable cljc tokenizer)"
    fi
  else
    echo "WARN- kotoba.cloud HTML has no kc-command blocks (page changed? re-survey)"
  fi
else
  echo "FAIL- kotoba.cloud unreachable (HTTP $HTTP)"; fail=1
fi

echo "== 5. wiki surface: kotobase code blocks =="
HTTP2=$(curl -sS -o /tmp/wiki.html -w "%{http_code}" --max-time 20 https://wiki.yataverse.com/ 2>/dev/null)
if [ "$HTTP2" = "200" ]; then
  echo "ok  - wiki.yataverse.com up (highlight audit needs an article with kotoba code: manual or next iteration)"
else
  echo "FAIL- wiki.yataverse.com unreachable (HTTP $HTTP2)"; fail=1
fi

echo "== summary =="
if [ $fail -ne 0 ]; then echo "HARD-FAIL (infra)"; exit 1
else echo "checks complete"; exit 0; fi
