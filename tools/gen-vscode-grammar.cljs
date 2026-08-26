#!/usr/bin/env nbb
;; Project the TextMate grammar into the VS Code extension, and check that the
;; extension still agrees with the two things it has to agree with.
;;
;;   nbb tools/gen-vscode-grammar.cljs           # after regenerating the grammar
;;   nbb tools/gen-vscode-grammar.cljs --check   # gate: 1 stale/disagrees, 2 cannot tell
;;
;; VS Code resolves `contributes.grammars[].path` inside the extension folder,
;; so the extension needs its own copy of syntaxes/kotoba.tmLanguage.json.
;; A copy is exactly the thing this repository has already paid for once
;; (linguist/samples.edn records provenance rather than duplicating sources),
;; so the copy is generated and `--check` refuses to let it drift -- the same
;; arrangement as gen-embedded.cljs and gen-tmlanguage.cljs.
;;
;; Three things can disagree here, and only the first is a copy:
;;
;;   1. the extension's grammar vs syntaxes/kotoba.tmLanguage.json
;;   2. the extension's declared scopeName vs the one inside the grammar
;;   3. the extension's declared file extensions vs linguist/languages.yml.entry
;;
;; 2 and 3 fail silently in a way 1 does not. A scopeName typo produces an
;; extension that installs, activates, opens .kotoba files and highlights
;; nothing, because VS Code looks up the grammar by scope and finds no match --
;; there is no error anywhere, just plain text. A drifted extension list means
;; the editor and GitHub disagree about which files are Kotoba at all.
;;
;; Exit codes match the sibling generators: 0 fresh, 1 stale or disagreeing,
;; 2 could not tell. A run that cannot read an input must not return what a run
;; that read it and found it current returns.

(require '["node:fs" :as fs]
         '[clojure.string :as str])

(def grammar-path "syntaxes/kotoba.tmLanguage.json")
(def ext-dir "editors/vscode")
(def ext-grammar-path (str ext-dir "/syntaxes/kotoba.tmLanguage.json"))
(def package-path (str ext-dir "/package.json"))
(def linguist-entry-path "linguist/languages.yml.entry")

(defn- slurp* [p] (when (fs/existsSync p) (fs/readFileSync p "utf8")))

(defn- cannot-tell [msg]
  (println (str "cannot tell: " msg))
  (set! (.-exitCode js/process) 2)
  nil)

(defn- linguist-extensions
  "The extensions block of the languages.yml entry, as a set of strings. The
  entry is YAML-shaped but this only needs the `- \".kotoba\"` lines under
  `extensions:`, so it is read line-wise rather than by pulling in a parser."
  [text]
  (let [lines (str/split-lines text)
        after (drop-while #(not (re-find #"^\s*extensions:\s*$" %)) lines)]
    (->> (rest after)
         (take-while #(re-find #"^\s*-\s" %))
         (keep #(second (re-find #"\"([^\"]+)\"" %)))
         set)))

(defn -main [args]
  (let [check? (some #{"--check"} args)
        grammar (slurp* grammar-path)
        pkg-text (slurp* package-path)
        entry (slurp* linguist-entry-path)]
    (cond
      (nil? grammar) (cannot-tell (str "cannot read " grammar-path))
      (nil? pkg-text) (cannot-tell (str "cannot read " package-path))
      (nil? entry) (cannot-tell (str "cannot read " linguist-entry-path))
      :else
      (let [pkg (js->clj (js/JSON.parse pkg-text) :keywordize-keys true)
            grammar-scope (get (js->clj (js/JSON.parse grammar) :keywordize-keys true)
                               :scopeName)
            contributed (get-in pkg [:contributes :grammars])
            declared-scope (some-> contributed first :scopeName)
            declared-exts (set (get-in pkg [:contributes :languages 0 :extensions]))
            entry-exts (linguist-extensions entry)
            problems
            (cond-> []
              (str/blank? grammar-scope)
              (conj (str grammar-path " declares no scopeName"))

              (empty? entry-exts)
              (conj (str "read no extensions out of " linguist-entry-path
                         " -- refusing to compare against an empty set"))

              (not= declared-scope grammar-scope)
              (conj (str "scopeName disagrees: " package-path " says "
                         (pr-str declared-scope) ", " grammar-path " says "
                         (pr-str grammar-scope)
                         " -- VS Code would highlight nothing, silently"))

              (not= declared-exts entry-exts)
              (conj (str "extensions disagree: " package-path " says "
                         (pr-str (sort declared-exts)) ", " linguist-entry-path
                         " says " (pr-str (sort entry-exts)))))]
        (if (seq problems)
          (do (doseq [p problems] (println (str "DISAGREES " p)))
              (set! (.-exitCode js/process) 1))
          (if check?
            (let [current (slurp* ext-grammar-path)]
              (if (= current grammar)
                (println (str "FRESH " ext-grammar-path
                              " (scope " grammar-scope ", extensions "
                              (str/join " " (sort entry-exts)) ")"))
                (do (println (str "STALE " ext-grammar-path
                                  " — regenerate: nbb tools/gen-vscode-grammar.cljs"))
                    (set! (.-exitCode js/process) 1))))
            (do (fs/mkdirSync (str ext-dir "/syntaxes") #js {:recursive true})
                (fs/writeFileSync ext-grammar-path grammar)
                (println (str "wrote " ext-grammar-path))
                (println (str "  scope      " grammar-scope))
                (println (str "  extensions " (str/join " " (sort entry-exts)))))))))))

(-main (vec (drop 3 (js->clj js/process.argv))))
