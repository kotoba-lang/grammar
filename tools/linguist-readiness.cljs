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
;;   - the results should show a reasonable distribution across unique
;;     :user/:repo. "If particular users are showing a high proportion of the
;;     results, for example the primary language owner, we will filter out
;;     those users using -user:<username>."
;;
;; That last sentence is the whole reason this script exists rather than a
;; bookmark to the search page. The number a reviewer assesses is the
;; OWNER-EXCLUDED one, and for a language whose sources all live in its own
;; org that number is not "a bit lower" -- it is a different order of
;; magnitude. Measured 2026-08-24: raw 27, owner-excluded 0.
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

(require '["node:child_process" :as cp]
         '[clojure.string :as str])

(def extension "kotoba")
(def threshold 2000)

;; Accounts that own the language. Reviewers discount these; so do we.
(def owner-users
  ["kotoba-lang" "com-junkawasaki" "etzhayyim" "gftdcojp"
   "cloud-itonami" "net-kotobase" "network-awai"])

(def raw-query (str "extension:" extension))
(def owner-excluded-query
  (str raw-query " " (str/join " " (map #(str "-user:" %) owner-users))))

(defn- search
  "Total files GitHub Search reports for `q`, or nil if the count could not be
  obtained. nil is never coerced to 0 -- 'nobody could look' and 'there are
  none' are opposite answers here."
  [q]
  (try
    (let [out (str (cp/execFileSync "gh"
                                    #js ["api" "-X" "GET" "search/code"
                                         "-f" (str "q=" q)
                                         "--jq" ".total_count"]
                                    #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]}))
          n (js/parseInt (str/trim out))]
      (when-not (js/isNaN n) n))
    (catch :default _ nil)))

(defn- self-test []
  ;; The query text is the thing most likely to rot, and a typo'd -user: would
  ;; silently report a healthier number than the truth.
  (let [ok (atom true)
        check (fn [label pred]
                (when-not pred (reset! ok false))
                (println (str "  " (if pred "ok  " "FAIL") " " label)))]
    (check "raw query names the extension"
           (= raw-query "extension:kotoba"))
    (check "every owner account is excluded in the assessed query"
           (every? #(str/includes? owner-excluded-query (str "-user:" %)) owner-users))
    (check "the assessed query is a strict narrowing of the raw one"
           (str/starts-with? owner-excluded-query raw-query))
    (check "threshold matches CONTRIBUTING.md for a >1-per-repo extension"
           (= threshold 2000))
    (println (if @ok "self-test: PASS" "self-test: FAIL"))
    (set! (.-exitCode js/process) (if @ok 0 1))))

(defn- report []
  (let [raw (search raw-query)
        owned (search owner-excluded-query)]
    (if (or (nil? raw) (nil? owned))
      (do (println "UNVERIFIED  GitHub code search did not answer.")
          (println "            Needs an authenticated `gh` (gh auth status).")
          (println "            This is not a measurement of zero usage.")
          (set! (.-exitCode js/process) 3))
      (do
        (println (str "extension       ." extension))
        (println (str "threshold       " threshold
                      " files indexed in the last year, excluding forks"))
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
              (set! (.-exitCode js/process) 1)))))))

(if (some #{"--self-test"} (vec (drop 3 (js->clj js/process.argv))))
  (self-test)
  (report))
