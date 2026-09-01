(ns highlight-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.grammar.highlight :as highlight]))

(defn- token-for [source text]
  (first (filter #(= text (:text %)) (highlight/tokenize source))))

(deftest portable-tokenizer-covers-the-public-language-surface
  (let [source (str "; comment\n"
                    "(defn answer [x]\n"
                    "  (if true\n"
                    "    (+ x 0x2a)\n"
                    "    (http-fetch \"https://example.test\\\"/x\")))\n"
                    "(eval ::ambient)\n")
        tokens (highlight/tokenize source)]
    (is (= source (apply str (map :text tokens)))
        "tokenization must preserve the exact source")
    (doseq [[text scope] [["; comment" "comment.line.semicolon.kotoba"]
                          ["defn" "keyword.control.kotoba"]
                          ["answer" "entity.name.function.kotoba"]
                          ["if" "keyword.control.kotoba"]
                          ["true" "constant.language.kotoba"]
                          ["+" "support.function.builtin.kotoba"]
                          ["0x2a" "constant.numeric.kotoba"]
                          ["http-fetch" "support.function.host-op.kotoba"]
                          ["\"https://example.test\\\"/x\"" "string.quoted.double.kotoba"]
                          ["eval" "keyword.control.sugar.kotoba"]
                          ["::ambient" "constant.other.keyword.kotoba"]
                          ["(" "punctuation.section.parens.kotoba"]]]
      (is (= scope (:scope (token-for source text)))
          (str text " must carry its Kotoba scope")))
    (is (>= (highlight/scope-coverage tokens) 0.60))))

(deftest vocabulary-is-an-explicit-testable-input
  (let [source "(eval x)"
        default-vocab (highlight/vocabulary)
        weakened (update default-vocab :sugar disj "eval")]
    (is (= "keyword.control.sugar.kotoba"
           (:scope (token-for source "eval"))))
    (is (= "variable.other.symbol.kotoba"
           (:scope (first (filter #(= "eval" (:text %))
                                 (highlight/tokenize source weakened)))))
        "a mutation of the authority-derived vocabulary must be observable")))

(deftest all-vocabulary-families-have-live-examples
  (let [vocab (highlight/vocabulary)]
    (doseq [[family _] [[:forbidden "invalid.illegal.forbidden-head.kotoba"]
                         [:special "keyword.control.kotoba"]
                         [:sugar "keyword.control.sugar.kotoba"]
                         [:host-op "support.function.host-op.kotoba"]
                         [:builtin "support.function.builtin.kotoba"]]
            text (sort (get vocab family))]
      (let [scope (cond
                    (contains? (:forbidden vocab) text) "invalid.illegal.forbidden-head.kotoba"
                    (contains? (:special vocab) text) "keyword.control.kotoba"
                    (contains? (:sugar vocab) text) "keyword.control.sugar.kotoba"
                    (contains? (:host-op vocab) text) "support.function.host-op.kotoba"
                    :else "support.function.builtin.kotoba")]
        (is (= scope (:scope (token-for (str "(" text ")") text)))
          (str family " head " text " must be classified"))))))

(deftest unterminated-strings-and-comments-remain-safe-and-lossless
  (doseq [source ["\"unterminated" "; through eof" "(defn f [] \"x\\\""]]
    (let [tokens (highlight/tokenize source)]
      (is (= source (apply str (map :text tokens))))
      (is (every? #(and (string? (:text %)) (contains? % :scope)) tokens)))))
