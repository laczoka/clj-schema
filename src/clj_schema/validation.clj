(ns clj-schema.validation
  (:require [clojure.set :as set]
            [clj-schema.schema :as s]
            [clj-schema.internal.utils :as u]))


(defprotocol ErrorReporter
  "Factory functions to generate each type of validation error.

   First arg 'state' is a map with 4 keys:
     :data-under-validation - the data being validated
     :schema - the schema being used to validate
     :parent-path - the path if any to the current map, from a map that contained this one
     :full-path - the full path in a nested map structure
     :all-wildcard-paths - any path that includes a wildcard
     :schema-without-wildcard-paths - a version of the schema with wildcard paths removed"
  (constraint-error [this state constraint]
    "Caused by a constraint predicate failing against the entire data structure or value")
  (extraneous-path-error [this state xtra-path]
    "Caused by finding a path that doesn't exist in the schema.  This only applies to schemas that are not loose")
  (missing-path-error [this state missing-path]
    "Caused by not finding a path mentioned in the schema")
  (predicate-fail-error [this state val-at-path pred]
    "Caused by a predicate schema returning false or nil")
  (instance-of-fail-error [this state val-at-path expected-class]
    "Caused by the value not being the expected Class, and not being a subtype of the expected Class"))

(deftype StringErrorReporter []
  ErrorReporter
  (constraint-error [_ {:keys [parent-path data-under-validation]} constraint]
    (if (empty? parent-path)
      (format "Constraint failed: '%s'"
              (:source constraint) (pr-str data-under-validation))
      (format "At parent path %s, constraint failed: '%s'"
              parent-path (:source constraint) (pr-str data-under-validation))))

  (extraneous-path-error [_ _ xtra-path]
    (format "Path %s was not specified in the schema." xtra-path))

  (missing-path-error [_ _ missing-path]
    (format "Map did not contain expected path %s." missing-path))

  (predicate-fail-error [_ {:keys [full-path schema]} val-at-path pred]
    (if (empty? full-path)
      (format "Value %s did not match predicate '%s'."
              (pr-str val-at-path) (u/pretty-fn-str pred))
      (format "Value %s, at path %s, did not match predicate '%s'."
              (pr-str val-at-path) full-path (u/pretty-fn-str pred))))

  (instance-of-fail-error [_ {:keys [full-path schema]} val-at-path expected-class]
    (if (empty? full-path)
      (format "Expected value %s to be an instance of class %s, but was %s"
        (pr-str val-at-path) (pr-str expected-class) (pr-str (class val-at-path)))
      (format "Expected value %s, at path %s, to be an instance of class %s, but was %s"
        (pr-str val-at-path) full-path (pr-str expected-class) (pr-str (class val-at-path))))))

;; used to hold state of one `validation-errors` calculation
(def ^{:private true :dynamic true} *error-reporter* nil)
(def ^{:private true :dynamic true} *data-under-validation* nil)
(def ^{:private true :dynamic true} *schema* nil)
(def ^{:private true :dynamic true} *parent-path* nil)
(def ^{:private true :dynamic true} *all-wildcard-paths* nil)
(def ^{:private true :dynamic true} *schema-without-wildcard-paths* nil)

(defn- state-map-for-reporter [full-path]
  {:data-under-validation *data-under-validation*
   :schema *schema*
   :parent-path *parent-path*
   :full-path full-path
   :all-wildcard-paths *all-wildcard-paths*
   :schema-without-wildcard-paths *schema-without-wildcard-paths*})

(declare validation-errors valid?)

(defn- safe-keys [x]
  (when (map? x)
    (keys x)))

(defn- wildcard-path->concrete-paths [m [path-first & path-rest :as the-wildcard-path]]
  (if (empty? the-wildcard-path)
    [[]]
    (let [keys-that-match-schema (if (s/wildcard? path-first)
                                      (filter #(valid? (:schema path-first) %) (safe-keys m))
                                      [path-first])]
      (for [k-that-matches-schema keys-that-match-schema
            one-of-the-concrete-path-ends (wildcard-path->concrete-paths (get m k-that-matches-schema) path-rest)]
        (vec (cons k-that-matches-schema one-of-the-concrete-path-ends))))))

(defn- errors-for-concrete-path [schema-path schema]
  (let [val-at-path (get-in *data-under-validation* schema-path ::not-found)
        contains-path? (not= ::not-found val-at-path)
        full-path (into *parent-path* schema-path)]
    (cond (and (not contains-path?) (s/optional-path? schema-path))
          []

          (not contains-path?)
          [(missing-path-error *error-reporter* (state-map-for-reporter full-path) full-path)]

          :else
          (validation-errors *error-reporter* full-path schema val-at-path))))

(defn- errors-for-possibly-wildcard-path [schema-path schema]
  (if (s/wildcard-path? schema-path)
    (let [concrete-paths (wildcard-path->concrete-paths *data-under-validation* schema-path)
          ;; TODO ALex - Sep 15, 2012 - this is here because metadata lost - add abstraction to keep metadata for schemas across a translation
          concrete-paths (if (s/optional-path? schema-path) (map s/optional-path concrete-paths) concrete-paths)]
      (mapcat #(errors-for-concrete-path % schema) concrete-paths))
    (errors-for-concrete-path schema-path schema)))


(defn- path-content-errors []
  (->> (s/schema-rows *schema*)
       (mapcat (fn [[schema-path schema]]
                 (errors-for-possibly-wildcard-path schema-path schema)))
       set))

(defn- shorten-to-schema-path-set
  "Since the result of calling 'paths' will be paths that go deeper into
   the map than our schema may specify, we truncate the result of calling
   'paths' to the longest version of them that is included in the schema's path set."
  [all-paths schema-path-set]
  (set (for [path all-paths]
         (if-let [validated-subpath (last (filter schema-path-set (u/subpaths path)))]
           validated-subpath
           path))))

(defn- remove-subpaths [paths]
  (let [all-subpaths (distinct (mapcat u/subpaths paths))
        any-of-all-subpaths-is-super-path? (fn [p]
                                             (some #(and (u/subpath? p %) (not= p %))
                                                   all-subpaths))]
    (remove any-of-all-subpaths-is-super-path? paths)))

(defn- extraneous-paths []
  (let [schema-paths (set (remove-subpaths (s/schema-path-set *schema-without-wildcard-paths*)))
        shortened (shorten-to-schema-path-set (u/paths *data-under-validation*) schema-paths)]
    (set/difference shortened schema-paths)))

(defn- covered-by-wildcard-path? [[path-first & path-rest :as path-to-check] [wildcard-first & wildcard-rest :as wildcard-path]]
  (let [path-to-check-count (count path-to-check)]
    (cond (not= path-to-check-count (count wildcard-path))
          false

          (zero? path-to-check-count)
          true

          (s/wildcard? wildcard-first)
          (if (valid? (:schema wildcard-first) path-first)
            (covered-by-wildcard-path? path-rest wildcard-rest)
            false)

          :else
          (covered-by-wildcard-path? path-rest wildcard-rest))))

(defn- matches-any-wildcard-path? [path]
  (some (partial covered-by-wildcard-path? path) *all-wildcard-paths*))

(defn- extraneous-paths-errors []
  (set (for [xtra-path (extraneous-paths)
             :when (not-any? matches-any-wildcard-path? (u/subpaths xtra-path))]
         (extraneous-path-error *error-reporter*
                                (state-map-for-reporter *parent-path*)
                                (into *parent-path* xtra-path)))))

(defn- constraint-errors []
  (set (for [c (:constraints *schema*)
             :when (not (valid? c *data-under-validation*))]
         (constraint-error *error-reporter* (state-map-for-reporter []) c))))

(defmacro ^{:private true} with-map-bindings [& body]
  `(binding [*all-wildcard-paths* (s/wildcard-path-set *schema*)
             *schema-without-wildcard-paths* (s/subtract-wildcard-paths *schema*)]
     ~@body))

(defn- map-loose-validation-errors [_parent-path_ _schema_ _m_]
  (with-map-bindings
    (path-content-errors)))

(defn- map-strict-validation-errors [_parent-path_ _schema_ _m_]
  (with-map-bindings
    (set/union (path-content-errors)
               (extraneous-paths-errors))))

(defn- coll-validation-errors [path-idx-fn parent-path schema xs]
  (let [schema (:schema-spec schema)]
    (set (mapcat (fn [idx x]
                   (validation-errors *error-reporter* (conj parent-path (path-idx-fn idx)) schema x))
                 (range)
                 xs))))

(def ^{:private true} set-validation-errors (partial coll-validation-errors (constantly :*)))
(def ^{:private true} seq-validation-errors (partial coll-validation-errors identity))

(defn- seq-layout-validation-errors [parent-path schema xs]
  (let [layout (:schema-spec schema)]
    (set (mapcat (fn [idx schema-x x]
                   (validation-errors *error-reporter* (conj parent-path idx) schema-x x))
                 (range)
                 layout
                 xs))))

(defn- class-validation-errors [parent-path schema x]
  (let [expected-class (:schema-spec schema)]
    (if-not (instance? expected-class x)
      #{(instance-of-fail-error *error-reporter* (state-map-for-reporter parent-path) x expected-class)}
      #{})))

(defn- or-statement-validation-errors [parent-path schema x]
  (let [schemas (:schema-spec schema)
        error-msg-batches (map #(validation-errors *error-reporter* parent-path % x) schemas)
        error-msgs        (set (apply concat error-msg-batches))
        non-empty-batches (remove empty? error-msg-batches)]
    (if-not (< (count non-empty-batches)
               (count schemas))
      (first non-empty-batches)
      #{})))

(defn- and-statement-validation-errors [parent-path schema x]
  (let [schemas (:schema-spec schema)]
    (set (mapcat #(validation-errors *error-reporter* parent-path % x) schemas))))

(defn- predicate-validation-errors [parent-path schema x]
  (let [pred (:schema-spec schema)]
    (if-not ((u/fn->fn-thats-false-if-throws pred) x)  ;; keeps us safe from ClassCastExceptions
      #{(predicate-fail-error *error-reporter* (state-map-for-reporter parent-path) x pred)}
      #{})))

(defn- validation-fn [schema]
  (case [(:type schema) (boolean (:strict schema))]
    [:map false]           map-loose-validation-errors
    [:map true]            map-strict-validation-errors
    [:seq false]           seq-validation-errors
    [:seq-layout false]    seq-layout-validation-errors
    [:set false]           set-validation-errors
    [:class false]         class-validation-errors
    [:or-statement false]  or-statement-validation-errors
    [:and-statement false] and-statement-validation-errors
    [:predicate false]     predicate-validation-errors))

(defn validation-errors
  "Returns a set of all the validation errors found when comparing a given
   item x, against the supplied schema.
   If schema is not already a schema, it will attempt to make a simple schema from it."
  ([schema x]
     (validation-errors (StringErrorReporter.) [] schema x))
  ([error-reporter schema x]
     (validation-errors error-reporter [] schema x))
  ([error-reporter parent-path schema x]
     (binding [*error-reporter* error-reporter
               *data-under-validation* x
               *schema* schema
               *parent-path* parent-path]
       (let [schema (if (s/schema? schema)
                      schema
                      (s/simple-schema schema))]
         (if-let [c-errors (seq (constraint-errors))]
           (set c-errors)
           ((validation-fn schema) parent-path schema x))))))

(defn valid?
  "Returns true if calling `validation-errors` would return no errors"
  [schema m]
  (empty? (validation-errors (StringErrorReporter.) schema m)))

(defn validate-and-handle
  "Validates item x against a schema.
   If it passes, then calls success-handler-fn passing m to it.
   If it fails, then calls error-handler-fn passing m and any validation errors to it."
  ([error-reporter m schema success-handler-fn error-handler-fn]
    (if-let [errors (seq (validation-errors error-reporter schema m))]
      (error-handler-fn m errors)
      (success-handler-fn m)))
  ([m schema success-handler-fn error-handler-fn]
    (validate-and-handle (StringErrorReporter.) m schema success-handler-fn error-handler-fn)))