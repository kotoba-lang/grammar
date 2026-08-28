(ns kotoba.grammar.highlight
  "Portable, dependency-free Kotoba source classification.

  The default tokenizer reads only the generated embedded grammar catalog. It
  performs no file, JVM, editor, or host-runtime access, so the same API works
  in Clojure, ClojureScript, nbb, browsers, documentation generators, and
  editor adapters. Tokens preserve the source byte-for-byte when their `:text`
  values are concatenated."
  (:require [clojure.string :as str]
            [kotoba.grammar.embedded :as embedded]))

(def scopes
  {:comment    "comment.line.semicolon.kotoba"
   :string     "string.quoted.double.kotoba"
   :number     "constant.numeric.kotoba"
   :literal    "constant.language.kotoba"
   :keyword    "constant.other.keyword.kotoba"
   :forbidden  "invalid.illegal.forbidden-head.kotoba"
   :special    "keyword.control.kotoba"
   :sugar      "keyword.control.sugar.kotoba"
   :host-op    "support.function.host-op.kotoba"
   :builtin    "support.function.builtin.kotoba"
   :definition "entity.name.function.kotoba"
   :delimiter  "punctuation.section.parens.kotoba"
   :symbol     "variable.other.symbol.kotoba"})

(defn ->names
  "Normalize a catalog collection or map keys to source-spelled strings."
  [x]
  (->> (cond (map? x) (keys x) (set? x) x (sequential? x) x :else [x])
       (map #(if (keyword? %) (name %) (str %)))
       (remove str/blank?)
       set))

(defn sugar-heads
  "Return real source heads from the catalog's sugar feature table."
  [sugar]
  (reduce (fn [acc [k v]]
            (let [forms (:forms v)]
              (if (and (seq forms) (every? symbol? forms))
                (into acc (map str forms))
                (conj acc (name k)))))
          #{} sugar))

(defn vocabulary
  "Build the highlighting vocabulary from a grammar catalog."
  ([] (vocabulary embedded/catalog))
  ([authority]
   {:special   (->names (:core-special-forms authority))
    :sugar     (sugar-heads (:sugar authority))
    :forbidden (->names (:forbidden-heads authority))
    :host-op   (into (->names (:string-head-host-ops authority))
                     (->names (:data-head-host-ops authority)))
    :builtin   (reduce into #{} [(->names (:arithmetic authority))
                                 (->names (:comparisons authority))
                                 (->names (:predicates authority))
                                 (->names (:admitted-builtins authority))])}))

(def ^:private delimiter-chars
  #{\( \) \[ \] \{ \} \" \, \; \' \` \@ \^ \~})

(def ^:private definition-heads
  #{"defn" "def" "defrecord" "defprotocol" "definterface"})

(defn- whitespace? [ch]
  (boolean (and ch (re-matches #"\s" (str ch)))))

(defn- boundary? [ch]
  (or (nil? ch) (whitespace? ch) (contains? delimiter-chars ch)))

(defn- scan-while [source start pred]
  (let [n (count source)]
    (loop [i start]
      (if (and (< i n) (pred (.charAt source i)))
        (recur (inc i))
        i))))

(defn- scan-string [source start]
  (let [n (count source)]
    (loop [i (inc start) escaped? false]
      (if (>= i n)
        n
        (let [ch (.charAt source i)]
          (cond
            escaped? (recur (inc i) false)
            (= ch \\) (recur (inc i) true)
            (= ch \u0022) (inc i)
            :else (recur (inc i) false)))))))

(defn- numeric? [s]
  (boolean (re-matches #"[-+]?(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)" s)))

(defn- keyword?* [s]
  (boolean (re-matches #"::?[a-zA-Z0-9*+!_'?<>=/.-]+" s)))

(defn- symbol?* [s]
  (boolean (re-matches #"[a-zA-Z*+!_'?<>=-].*" s)))

(defn- symbol-scope [vocab text definition-next?]
  (cond
    definition-next?                    (:definition scopes)
    (contains? (:forbidden vocab) text) (:forbidden scopes)
    (contains? (:special vocab) text)   (:special scopes)
    (contains? (:sugar vocab) text)     (:sugar scopes)
    (contains? (:host-op vocab) text)   (:host-op scopes)
    (contains? (:builtin vocab) text)   (:builtin scopes)
    (contains? #{"true" "false" "nil"} text) (:literal scopes)
    (numeric? text)                     (:number scopes)
    (keyword?* text)                    (:keyword scopes)
    (symbol?* text)                     (:symbol scopes)
    :else nil))

(defn- append-token [tokens text scope]
  (if (and (seq tokens) (= scope (:scope (peek tokens))))
    (conj (pop tokens) (update (peek tokens) :text str text))
    (conj tokens {:text text :scope scope})))

(defn tokenize
  "Classify SOURCE into ordered `{:text ... :scope ...}` tokens.

  The two-argument arity is useful for deterministic tests and consumers that
  pin an explicit catalog-derived vocabulary."
  ([source] (tokenize source (vocabulary)))
  ([source vocab]
   (let [source (str source)
         n (count source)]
     (loop [i 0 tokens [] after-open? false definition-next? false]
       (if (>= i n)
         tokens
         (let [ch (.charAt source i)]
           (cond
             (whitespace? ch)
             (let [end (scan-while source i whitespace?)]
               (recur end (append-token tokens (subs source i end) nil)
                      after-open? definition-next?))

             (= ch \;)
             (let [end (scan-while source i #(not (contains? #{\newline \return} %)))]
               (recur end (append-token tokens (subs source i end) (:comment scopes))
                      after-open? definition-next?))

             (= ch \u0022)
             (let [end (scan-string source i)]
               (recur end (append-token tokens (subs source i end) (:string scopes))
                      false false))

             (contains? #{\( \) \[ \] \{ \}} ch)
             (recur (inc i)
                    (append-token tokens (str ch) (:delimiter scopes))
                    (= ch \() false)

             (contains? delimiter-chars ch)
             (recur (inc i) (append-token tokens (str ch) nil) false false)

             :else
             (let [end (scan-while source i #(not (boundary? %)))
                   text (subs source i end)
                   scope (symbol-scope vocab text definition-next?)
                   def-head? (and after-open? (contains? definition-heads text))]
               (recur end (append-token tokens text scope) false def-head?)))))))))

(defn scope-coverage
  "Fraction of non-blank token text carrying a Kotoba scope (0.0 to 1.0)."
  [tokens]
  (let [visible (remove #(str/blank? (:text %)) tokens)
        scoped (filter :scope visible)]
    (if (seq visible) (/ (count scoped) (count visible)) 1.0)))
