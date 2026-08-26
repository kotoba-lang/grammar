# Kotoba for VS Code

Syntax highlighting for `.kotoba`, from the same grammar this repository
generates for github-linguist.

It also scopes the heads Kotoba refuses. `(atom x)`, `(eval x)`, `(swap! …)` —
the `:forbidden-heads` in `resources/kotoba/lang/guest-grammar.edn` — get
`invalid.illegal.forbidden-head.kotoba` rather than the plain function-call
scope Clojure's grammar would give them. Every theme renders that as an error,
so the no-ambient-authority invariant shows up while you type instead of when
the compiler fails closed on it.

## What it does not do

**It does not make GitHub display Kotoba.** GitHub reads
`lib/linguist/languages.yml`, not an editor extension, and Kotoba is not in it
yet — see [Linguist status](../../README.md#linguist-status) for the measured
gap and why `.gitattributes` cannot stand in.

It also has no language server behind it: no completion, no go-to-definition,
no diagnostics. Grammar only.

## Install from source

```bash
nbb tools/gen-vscode-grammar.cljs     # from the repository root
ln -s "$PWD/editors/vscode" ~/.vscode/extensions/kotoba
```

Reload the window. `.kotoba` files report `kotoba` in the language picker.

## The grammar here is generated

`syntaxes/kotoba.tmLanguage.json` is a copy — VS Code resolves
`contributes.grammars[].path` inside the extension folder, so it cannot point
at the one in the repository root. `tools/gen-vscode-grammar.cljs --check`
refuses to let the copy drift, and checks two things a copy check would not:

- the `scopeName` in `package.json` against the one inside the grammar. A typo
  there produces an extension that installs, activates, opens `.kotoba` files
  and highlights nothing, because VS Code looks the grammar up by scope and
  finds no match. No error is raised anywhere.
- the declared extensions against `linguist/languages.yml.entry`, so the editor
  and the pending Linguist submission cannot disagree about which files are
  Kotoba.

Do not edit anything in `syntaxes/` here. Edit the EDN authority, regenerate.

## Not published

There is no Marketplace listing. Publishing needs a `vsce` publisher identity,
which is an account decision rather than a code one.
