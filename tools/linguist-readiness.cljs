#!/usr/bin/env nbb
;; Answer "can Kotoba be submitted to github-linguist yet" with a measurement.
;;
;;   nbb tools/linguist-readiness.cljs            # measure
;;   nbb tools/linguist-readiness.cljs --self-test
;;
;; Linguist, CONTRIBUTING.md, "Language extension and filename usage
;; requirements":
;;
;;   - at least 2000 files per extension indexed in the last year, excluding
;;     forks, for extensions expected to occur more than once per repo;
;;   - at least 200 for extensions expected to occur only once per repo,
;;     like a Makefile;
;;   - the results should show a reasonable distribution across unique
;;     :user/:repo. "If particular users are showing a high proportion of the
;;     results, for example the primary language owner, we will filter out
;;     those users using -user:<username>."
;;
;; That last sentence is the whole reason this script exists rather than a
;; bookmark to the search page. The number a reviewer assesses is the
;; OWNER-EXCLUDED one, and for a language whose sources all live in its own
;; org that number is not "a bit lower" -- it is a different order of
;; magnitude.
;;
;; Which tier applies is not settled. Kotoba's shape is one decision core per
;; repo, so the 200 tier is arguable; nobody has argued it to Linguist yet, so
;; `threshold` stays at 2000 rather than assuming the answer to an open
;; question. Raising it in a Linguist discussion is cheaper than filing a PR
;; and finding out.
;;
;; Exit codes are three-valued on purpose. A run that could not reach the API
;; must not return what a run that measured and found the corpus healthy
;; returns:
;;
;;   0  measured, threshold met      -> submit
;;   1  measured, threshold not met  -> do not submit; a closed PR is worse
;;                                      than a late one
;;   2  usage error
;;   3  UNVERIFIED, nothing measured -> not evidence of anything
;;
;; The 2026-08-26 revision exists because exit 1 was being returned for a run
;; that had measured nothing. The self-test checked that the query TEXT was
;; well formed; it could not check that the answer came from the corpus. It
;; does not: GitHub's code search index does not cover this org's repositories.
;; kotoba-lang/murakumo is public, not a fork, pushed the same day, and carries
;; 36 .kotoba files on its default branch -- and `repo:kotoba-lang/murakumo
;; extension:kotoba` returns 0. The API does not error; it returns a number,
;; and a number that small is indistinguishable from "the language is unused".
;; So the run now has to prove the index can see a corpus it is known to
;; contain before it is allowed to report a shortfall.

(require '["node:child_process" :as cp]
         '[clojure.string :as str])

(def extension "kotoba")
(def threshold 2000)

;; Accounts that own the language. Reviewers discount these; so do we.
(def owner-users
  ["kotoba-lang" "com-junkawasaki" "etzhayyim" "gftdcojp"
   "cloud-itonami" "net-kotobase" "network-awai"])

;; A repository whose .kotoba files are counted straight off the git tree, so
;; the control is derived rather than asserted. If it ever stops carrying the
;; extension the run refuses instead of quietly losing its control.
(def control-repo "kotoba-lang/murakumo")

(def raw-query (str "extension:" extension))
(def owner-excluded-query
  (str raw-query " " (str/join " " (map #(str "-user:" %) owner-users))))
(def control-query (str "repo:" control-repo " " raw-query))

(defn- gh
  "stdout of `gh <args>`, or nil if the call failed. nil is never coerced to a
  number -- 'nobody could look' and 'there are none' are opposite answers."
  [args]
  (try
    (str (cp/execFileSync "gh" (clj->js args)
                          #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]}))
    (catch :default _ nil)))

(defn- count-of [out]
  (when out
    (let [n (js/parseInt (str/trim out))]
      (when-not (js/isNaN n) n))))

(defn- search
  "Total files GitHub Search reports for `q`, or nil if it could not be obtained."
  [q]
  (count-of (gh ["api" "-X" "GET" "search/code" "-f" (str "q=" q)
                 "--jq" ".total_count"])))

(defn- tree-count
  "How many paths ending in .<extension> the default branch of `repo` actually
  holds. This is ground truth: it reads the tree, not an index of it."
  [repo]
  (count-of (gh ["api" (str "repos/" repo "/git/trees/HEAD?recursive=1")
                 "--jq" (str "[.tree[].path|select(endswith(\"." extension "\"))]|length")])))

(defn- unverified [& lines]
  (println "UNVERIFIED  nothing was measured. This is not evidence of zero usage.")
  (doseq [l lines] (println (str "            " l)))
  (set! (.-exitCode js/process) 3))

(defn- report
  "search-fn and tree-fn are injected so --self-test can drive the refusal paths
  without reaching the network."
  ([] (report search tree-count))
  ([search-fn tree-fn]
   (let [on-disk (tree-fn control-repo)
         seen    (search-fn control-query)]
     (cond
       (nil? on-disk)
       (unverified (str "Could not read the tree of " control-repo ".")
                   "Needs an authenticated `gh` (gh auth status).")

       (zero? on-disk)
       (unverified (str control-repo " no longer carries ." extension " files,")
                   "so it cannot serve as the reachability control."
                   "Point `control-repo` at a repository that does.")

       (nil? seen)
       (unverified "GitHub code search did not answer."
                   "Needs an authenticated `gh` (gh auth status).")

       (zero? seen)
       (unverified (str "GitHub's code search index does not cover " control-repo ",")
                   (str "which carries " on-disk " ." extension " files on its default branch")
                   "and is public, not a fork, and recently pushed."
                   ""
                   "A global count taken through an index that cannot see a corpus"
                   "it is known to contain measures the index, not the language."
                   "Assess through the web search UI a reviewer actually uses:"
                   (str "  https://github.com/search?type=code&q=" raw-query))

       :else
       (let [raw   (search-fn raw-query)
             owned (search-fn owner-excluded-query)]
         (if (or (nil? raw) (nil? owned))
           (unverified "GitHub code search stopped answering mid-run.")
           (do
             (println (str "extension       ." extension))
             (println (str "threshold       " threshold
                           " files indexed in the last year, excluding forks"))
             (println (str "control         " control-repo ": " on-disk " on disk, " seen " indexed"))
             (println (str "raw             " raw "   (" raw-query ")"))
             (println (str "owner-excluded  " owned
                           "   <- the number a reviewer assesses"))
             (println)
             (if (>= owned threshold)
               (do (println "READY   owner-excluded usage meets the threshold.")
                   (println "        Confirm distribution across unique :user/:repo by")
                   (println "        clicking through the results, then file")
                   (println "        linguist/PULL_REQUEST.md.")
                   (set! (.-exitCode js/process) 0))
               (do (println (str "NOT READY   owner-excluded usage is " owned "/" threshold "."))
                   (println "            CONTRIBUTING.md: \"we do not accept PRs for very new")
                   (println "            or hobby languages, and will close any such PRs that")
                   (println "            attempt to add them.\" A closed PR is harder to reopen")
                   (println "            than a late one is to file.")
                   (set! (.-exitCode js/process) 1))))))))))

(defn- self-test []
  (let [ok    (atom true)
        check (fn [label pred]
                (when-not pred (reset! ok false))
                (println (str "  " (if pred "ok  " "FAIL") " " label)))
        ;; Drive `report` with stubbed answers and read back the exit code it
        ;; chose. Asserting on the code alone would let a refusal for the wrong
        ;; reason count as a pass, so each case also fixes what the stub said.
        exit-for (fn [tree searches]
                   (set! (.-exitCode js/process) 0)
                   (report (fn [q] (get searches q :missing)) (constantly tree))
                   (.-exitCode js/process))]
    (check "raw query names the extension"
           (= raw-query "extension:kotoba"))
    (check "every owner account is excluded in the assessed query"
           (every? #(str/includes? owner-excluded-query (str "-user:" %)) owner-users))
    (check "the assessed query is a strict narrowing of the raw one"
           (str/starts-with? owner-excluded-query raw-query))
    (check "the control query is scoped to the control repo"
           (and (str/includes? control-query (str "repo:" control-repo))
                (str/includes? control-query raw-query)))
    (check "threshold matches CONTRIBUTING.md for a >1-per-repo extension"
           (= threshold 2000))
    (println)
    ;; The refusals, exercised. Each of these returned exit 1 before 2026-08-26.
    (check "an index blind to the control corpus refuses instead of reporting a shortfall"
           (= 3 (exit-for 36 {control-query 0 raw-query 27 owner-excluded-query 0})))
    (check "an unreadable control tree refuses"
           (= 3 (exit-for nil {control-query 0})))
    (check "a control repo that lost the extension refuses"
           (= 3 (exit-for 0 {control-query 0})))
    (check "search going silent refuses"
           (= 3 (exit-for 36 {control-query nil})))
    (check "a visible control still lets a real shortfall report as one"
           (= 1 (exit-for 36 {control-query 36 raw-query 27 owner-excluded-query 0})))
    (check "a visible control still lets the threshold be met"
           (= 0 (exit-for 36 {control-query 36 raw-query 9000 owner-excluded-query 2500})))
    (println)
    (println (if @ok "self-test: PASS" "self-test: FAIL"))
    (set! (.-exitCode js/process) (if @ok 0 1))))

(if (some #{"--self-test"} (vec (drop 3 (js->clj js/process.argv))))
  (self-test)
  (report))
