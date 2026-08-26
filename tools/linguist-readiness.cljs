#!/usr/bin/env nbb
;; Answer "can Kotoba be submitted to github-linguist yet" with a measurement.
;;
;;   nbb tools/linguist-readiness.cljs                    # print the queries to run
;;   nbb tools/linguist-readiness.cljs --raw 58 --assessed 0
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
;; That last sentence is why the assessed number is not the raw one. For a
;; language whose sources all live in its own org the two are not "a bit
;; different" -- they are 58 and 0.
;;
;; Which tier applies is not settled. Kotoba's shape is one decision core per
;; repo, so the 200 tier is arguable; nobody has argued it to Linguist yet, so
;; `threshold` stays at 2000 rather than assuming the answer to an open
;; question.
;;
;; ## Why this script no longer counts anything itself
;;
;; It used to call `gh api search/code` and report the total. Two revisions
;; found two different ways for that to be wrong, and the second one is fatal
;; to the whole approach.
;;
;; 2026-08-26 (a): the run reported "measured, threshold not met" for a run
;; that had measured nothing. GitHub's REST code search index does not cover
;; this org. kotoba-lang/murakumo is public, not a fork, pushed the same day,
;; and carries 36 .kotoba files on its default branch -- and
;; `repo:kotoba-lang/murakumo extension:kotoba` returns 0, as does every other
;; query scoped to that repo. Across 19 sampled repositories that hold
;; .kotoba, exactly one was indexed at all. The API does not error; it returns
;; a small number, and a small number is indistinguishable from "the language
;; is unused".
;;
;; 2026-08-26 (b): the reviewer does not use that index, and cannot. The two
;; search surfaces do not even share a query language:
;;
;;   github.com/search?type=code   extension:kotoba -> "Unrecognized
;;                                 qualifier. Looking for a file extension?
;;                                 Try using the path qualifier", 0 files
;;                                 path:*.kotoba    -> 58 files
;;   api.github.com search/code    extension:kotoba -> 27
;;                                 path:*.kotoba    -> 0
;;
;; Neither number is the other's. CONTRIBUTING asks the PR to link a GitHub
;; search result, which is the web one, and the web one has no API. So a
;; script that queries the REST endpoint is not a cheap approximation of the
;; assessment -- it is a different measurement wearing its clothes.
;;
;; What is left for a script to do honestly: hold the queries, hold the
;; threshold, and evaluate a number someone actually read off the page. With
;; no number supplied it refuses. Exit codes stay three-valued:
;;
;;   0  measured, threshold met      -> submit
;;   1  measured, threshold not met  -> do not submit; a closed PR is worse
;;                                      than a late one
;;   2  usage error
;;   3  UNVERIFIED, nothing measured -> not evidence of anything

(require '[clojure.string :as str])

(def extension "kotoba")
(def threshold 2000)

;; Accounts that own the language. Reviewers discount these; so do we.
(def owner-users
  ["kotoba-lang" "com-junkawasaki" "etzhayyim" "gftdcojp"
   "cloud-itonami" "net-kotobase" "network-awai"])

;; The web code search rejects `extension:`. `path:*.ext` is the qualifier it
;; documents in that rejection, and the one that returns the corpus. `NOT
;; is:fork` is not decoration: the threshold is stated per extension "excluding
;; forks", and CONTRIBUTING's own worked example is
;; `NOT is:fork path:*.boot`. It happens not to move this corpus -- 58 either
;; way, 2026-08-26 -- but a query that agrees with the requirement by accident
;; stops agreeing the first time somebody forks a repository.
(def raw-query (str "NOT is:fork path:*." extension))
(def assessed-query
  (str raw-query " " (str/join " " (map #(str "-user:" %) owner-users))))

(defn- search-url [q]
  (str "https://github.com/search?type=code&q="
       (js/encodeURIComponent q)))

(defn- parse-count [s]
  (when s
    (let [n (js/parseInt (str/replace (str/trim s) "," ""))]
      (when-not (js/isNaN n) n))))

(defn- arg [argv flag]
  (second (drop-while #(not= % flag) argv)))

(defn- how-to-measure []
  (println "Open each query, read the file count above the results, and pass")
  (println "both back. There is no API for this index -- see the header.")
  (println)
  (println (str "  raw       " raw-query))
  (println (str "            " (search-url raw-query)))
  (println)
  (println (str "  assessed  " assessed-query))
  (println (str "            " (search-url assessed-query)))
  (println)
  (println "  nbb tools/linguist-readiness.cljs --raw <n> --assessed <n>"))

(defn- report [raw assessed]
  (cond
    (or (nil? raw) (nil? assessed))
    (do (println "UNVERIFIED  nothing was measured. This is not evidence of zero usage.")
        (println)
        (how-to-measure)
        3)

    :else
    (do
      (println (str "extension       ." extension))
      (println (str "threshold       " threshold
                    " files indexed in the last year, excluding forks"))
      (println (str "raw             " raw "   (" raw-query ")"))
      (println (str "assessed        " assessed
                    "   <- owner-excluded; the number a reviewer reads"))
      (println)
      (if (>= assessed threshold)
        (do (println "READY   owner-excluded usage meets the threshold.")
            (println "        Confirm distribution across unique :user/:repo by")
            (println "        clicking through the results, then file")
            (println "        linguist/PULL_REQUEST.md.")
            0)
        (do (println (str "NOT READY   owner-excluded usage is " assessed "/" threshold "."))
            (println "            CONTRIBUTING.md: \"we do not accept PRs for very new")
            (println "            or hobby languages, and will close any such PRs that")
            (println "            attempt to add them.\" A closed PR is harder to reopen")
            (println "            than a late one is to file.")
            (when (pos? raw)
              (println)
              (println (str "            raw is " raw ", so the index can see the corpus."))
              (println "            What is missing is usage outside the owning org, and")
              (println "            more repositories under it do not move this number."))
            1)))))

(defn- self-test []
  (let [ok    (atom true)
        check (fn [label pred]
                (when-not pred (reset! ok false))
                (println (str "  " (if pred "ok  " "FAIL") " " label)))]
    (check "the raw query uses the qualifier the web search accepts"
           (= raw-query "NOT is:fork path:*.kotoba"))
    (check "the raw query excludes forks, as the threshold is stated"
           (str/includes? raw-query "NOT is:fork"))
    (check "it does not use the qualifier the web search rejects"
           (not (str/includes? raw-query "extension:")))
    (check "every owner account is excluded in the assessed query"
           (every? #(str/includes? assessed-query (str "-user:" %)) owner-users))
    (check "the assessed query is a strict narrowing of the raw one"
           (str/starts-with? assessed-query raw-query))
    (check "threshold matches CONTRIBUTING.md for a >1-per-repo extension"
           (= threshold 2000))
    (check "the printed URL survives escaping of * and :"
           (= (search-url "path:*.kotoba")
              "https://github.com/search?type=code&q=path%3A*.kotoba"))
    (println)
    ;; The refusal, exercised. Reporting a shortfall from no measurement is the
    ;; defect this script was rewritten twice to stop doing.
    (check "no measurement refuses rather than reporting a shortfall"
           (= 3 (report nil nil)))
    (check "a raw number alone is still not a measurement"
           (= 3 (report 58 nil)))
    (check "a measured shortfall reports as one"
           (= 1 (report 58 0)))
    (check "a measured pass reports as one"
           (= 0 (report 9000 2500)))
    (check "zero raw with zero assessed is still a shortfall, not a refusal"
           (= 1 (report 0 0)))
    (println)
    (println (if @ok "self-test: PASS" "self-test: FAIL"))
    (set! (.-exitCode js/process) (if @ok 0 1))))

(let [argv (vec (drop 3 (js->clj js/process.argv)))]
  (if (some #{"--self-test"} argv)
    (self-test)
    (set! (.-exitCode js/process)
          (report (parse-count (arg argv "--raw"))
                  (parse-count (arg argv "--assessed"))))))
