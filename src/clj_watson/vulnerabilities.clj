(ns clj-watson.vulnerabilities
  (:import
   (org.owasp.dependencycheck.utils DependencyVersion)))

(def ^:private version-operators
  {:version-end-excluding   >
   :version-end-including   >=
   :version-start-excluding <
   :version-start-including <=})

(defn ^:private cvssv2 [vulnerability]
  (try
    (some-> vulnerability .getCvssV2 .getScore)
    (catch Exception _
      nil)))

(defn ^:private cvssv3 [vulnerability]
  (try
    (some-> vulnerability .getCvssV3 .getBaseScore)
    (catch Exception _
      nil)))

(defn ^:private cwes [vulnerability]
  (some->> vulnerability .getCwes .getFullCwes keys set))

(defn ^:private compare-cpe-version-with-version [version current-version]
  (let [version (DependencyVersion. version)
        current-version (DependencyVersion. current-version)]
    (cond
      (= version current-version) true
      (= version "-") false
      :default false)))

(defn ^:private compare-version-with-current-version [version current-version operator]
  (let [version (DependencyVersion. version)
        current-version (DependencyVersion. current-version)]
    (operator (.compareTo version current-version) 0)))

(defn ^:private versions [vulnerability]
  (let [vulnerable-software (.getMatchedVulnerableSoftware vulnerability)]
    {:version-end-excluding   (.getVersionEndExcluding vulnerable-software)
     :version-start-excluding (.getVersionStartExcluding vulnerable-software)
     :version-end-including   (.getVersionEndIncluding vulnerable-software)
     :version-start-including (.getVersionStartIncluding vulnerable-software)}))

(defn ^:private vulnerable? [current-version cpe-version versions]
  (let [contains-versions? (->> versions vals (some string?) boolean)]
    (or (cond
          (= cpe-version "-") false
          (and (not contains-versions?)
               (compare-cpe-version-with-version cpe-version current-version)) false)
        (reduce-kv (fn [result version-kind version-value]
                     (if (and contains-versions? version-value)
                       (let [operator (version-kind version-operators)
                             compare-result (compare-version-with-current-version version-value current-version operator)]
                         (if (or (= version-kind :version-start-including)
                                 (= version-kind :version-start-excluding))
                           (and compare-result result)
                           compare-result))
                       result))
                   false versions))))

(defn get-details
  [current-version vulnerability]
  (let [versions (versions vulnerability)
        cpe-version (-> vulnerability .getMatchedVulnerableSoftware .getVersion)]
    (when (vulnerable? current-version cpe-version versions)
      (-> (assoc {} :name (.getName vulnerability))
          (assoc :cvssv2 (cvssv2 vulnerability))
          (assoc :cvssv3 (cvssv3 vulnerability))
          (assoc :cwes (cwes vulnerability))
          (assoc :cpe-version cpe-version)
          (merge versions)))))