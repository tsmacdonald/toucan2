(ns toucan2.select
  "Table-aware methods for fetching data from the database. `select` and related methods."
  (:refer-clojure :exclude [count])
  (:require [clojure.spec.alpha :as s]
            [methodical.core :as m]
            [methodical.impl.combo.threaded :as m.combo.threaded]
            [toucan2.build-query :as build-query]
            [toucan2.connectable :as conn]
            [toucan2.connectable.current :as conn.current]
            [toucan2.log :as log]
            [toucan2.query :as query]
            [toucan2.queryable :as queryable]
            [toucan2.realize :as realize]
            [toucan2.select :as select]
            [toucan2.specs :as specs]
            [toucan2.tableable :as tableable]
            [toucan2.util :as u]))

;; TODO -- consider whether this should be moved to `query`
(defn reducible-query-as
  ([tableable queryable]
   (reducible-query-as (conn.current/current-connectable tableable) tableable queryable nil))

  ([connectable tableable queryable]
   (reducible-query-as connectable tableable queryable nil))

  ([connectable tableable queryable options]
   (let [[connectable options] (conn.current/ensure-connectable connectable tableable options)]
     (query/reducible-query connectable tableable queryable options))))

(m/defmulti select*
  {:arglists '([connectableᵈ tableableᵈ queryᵈᵗ options])}
  u/dispatch-on-first-three-args
  :combo (m.combo.threaded/threading-method-combination :third))

(m/defmethod select* :default
  [connectable tableable query options]
  (let [query (build-query/maybe-buildable-query connectable tableable query :select options)]
    (reducible-query-as connectable tableable query options)))

(m/defmethod select* [:default :default nil]
  [connectable tableable _ options]
  (next-method connectable tableable {} options))

(m/defmulti parse-select-args*
  {:arglists '([connectableᵈ tableableᵈ argsᵈᵗ options])}
  u/dispatch-on-first-two-args
  :combo (m.combo.threaded/threading-method-combination :third))

(defn select-args-spec [connectable tableable]
  (letfn [(query? [x] (or (map? x) (queryable/queryable? connectable tableable x)))]
    ;; TODO -- rename these keys, since query is not necessarily a map.
    (s/cat :query   (s/alt :map     (s/cat :pk         (s/? ::specs/pk)
                                           :conditions ::specs/kv-conditions
                                           :query      (s/? query?))
                           :non-map (s/cat :query (s/? (complement query?))))
           :options (s/? ::specs/options))))

(m/defmethod parse-select-args* :default
  [connectable tableable args _]
  (let [spec   (select-args-spec connectable tableable)
        parsed (log/with-trace ["parse-select-args* :default"]
                 (s/conform spec args))]
    (when (= parsed :clojure.spec.alpha/invalid)
      (throw (ex-info (format "Don't know how to interpret select args: %s" (s/explain-str spec args))
                      {:args args})))
    (let [{[_ {:keys [pk query conditions]}] :query, :keys [options]} parsed
          conditions                                                  (when (seq conditions)
                                                                        (zipmap (map :k conditions) (map :v conditions)))
          conditions                                                  (if-not pk
                                                                        conditions
                                                                        (build-query/merge-primary-key connectable tableable conditions pk options))]
      {:conditions conditions
       ;; TODO -- should probably be `:queryable` instead of `:query` for clarity.
       :query      query
       :options    options})))

;; TODO -- I think this should just take `& options` and do the `parse-connectable-tableable` stuff inside this fn.
(defn parse-select-args
  "Parse args to the `select` family of functions. Returns a map with the parsed/combined `:query` and parsed
  `:options`."
  [connectable tableable args options-1]
  (log/with-trace ["Parsing select args for %s %s" tableable args]
    (let [[connectable options-1]            (conn.current/ensure-connectable connectable tableable options-1)
          {:keys [conditions query options]} (parse-select-args* connectable tableable args options-1)
          options                            (u/recursive-merge options-1 options)
          query                              (build-query/maybe-buildable-query connectable tableable query :select options)
          query                              (cond-> query
                                               (not (build-query/table* query)) (build-query/with-table* tableable options)
                                               (seq conditions)                 (build-query/merge-kv-conditions* conditions options))]

      (assert (some? query) "Query should not be nil")
      (when (seqable? query)
        (assert (seq query) (format "Query should not be empty. Got: %s" (binding [*print-meta* true] (pr-str query)))))
      {:query query, :options options})))

(defn select-reducible
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        [connectable options]   (conn.current/ensure-connectable connectable tableable nil)
        {:keys [query options]} (parse-select-args connectable tableable args options)]
    (select* connectable tableable query options)))

(defn select
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (let [result (realize/realize (apply select-reducible args))]
    (assert (not (instance? clojure.core.Eduction result)))
    result))

(defn select-one
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (query/reduce-first (map realize/realize) (apply select-reducible args)))

(defn select-fn-reducible
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [f & args]
  (eduction
   (map f)
   (apply select-reducible args)))

(defn select-fn-set
  "Like `select`, but returns a set of values of `(f instance)` for the results. Returns `nil` if the set is empty."
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (not-empty (reduce conj #{} (apply select-fn-reducible args))))

(defn select-fn-vec
  "Like `select`, but returns a vector of values of `(f instance)` for the results. Returns `nil` if the vector is
  empty."
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (not-empty (reduce conj [] (apply select-fn-reducible args))))

(defn select-one-fn
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (query/reduce-first (apply select-fn-reducible args)))

(defn select-pks-fn [connectable tableable]
  (let [pk-keys (tableable/primary-key-keys connectable tableable)]
    (if (= (clojure.core/count pk-keys) 1)
      (first pk-keys)
      (apply juxt pk-keys))))

(defn select-pks-reducible
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        f                       (select-pks-fn connectable tableable)]
    (apply select-fn-reducible f [connectable tableable] args)))

(defn select-pks-set
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (not-empty (reduce conj #{} (apply select-pks-reducible args))))

(defn select-pks-vec
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (not-empty (reduce conj [] (apply select-pks-reducible args))))

(defn select-one-pk
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [& args]
  (query/reduce-first (apply select-pks-reducible args)))

(defn select-fn->fn
  {:arglists '([f1 f2 connectable-tableable pk? & conditions? queryable? options?])}
  [f1 f2 & args]
  (not-empty
   (into
    {}
    (map (juxt f1 f2))
    (apply select-reducible args))))

(defn select-fn->pk
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [f connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        pks-fn                  (select-pks-fn connectable tableable)]
    (apply select-fn->fn f pks-fn [connectable tableable] args)))

(defn select-pk->fn
  {:arglists '([f connectable-tableable pk? & conditions? queryable? options?])}
  [f connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        pks-fn                  (select-pks-fn connectable tableable)]
    (apply select-fn->fn pks-fn f [connectable tableable] args)))

(m/defmulti count*
  {:arglists '([connectableᵈ tableableᵈ queryableᵈᵗ options])}
  u/dispatch-on-first-three-args
  :combo (m.combo.threaded/threading-method-combination :third))

(m/defmethod count* :default
  [connectable tableable query options]
  (log/tracef "No efficient implementation of count* for %s, doing select-reducible and counting the rows..."
              (u/dispatch-value query))
  (reduce
   (fn [acc _]
     (inc acc))
   0
   (select* connectable tableable query options)))

(defn count
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        [connectable options]   (conn.current/ensure-connectable connectable tableable nil)
        {:keys [query options]} (parse-select-args connectable tableable args options)]
    (count* connectable tableable query options)))

(m/defmulti exists?*
  {:arglists '([connectableᵈ tableableᵈ queryableᵈᵗ options])}
  u/dispatch-on-first-three-args
  :combo (m.combo.threaded/threading-method-combination :third))

(m/defmethod exists?* :default
  [connectable tableable query options]
  (log/tracef "No efficient implementation of exists?* for %s, doing select-reducible and seeing if it returns a row..."
              (u/dispatch-value query))
  (transduce
   (take 1)
   (fn
     ([acc]
      acc)
     ([_ _]
      true))
   false
   (select/select* connectable tableable query options)))

(defn exists?
  {:arglists '([connectable-tableable pk? & conditions? queryable? options?])}
  [connectable-tableable & args]
  (let [[connectable tableable] (conn/parse-connectable-tableable connectable-tableable)
        [connectable options]   (conn.current/ensure-connectable connectable tableable nil)
        {:keys [query options]} (parse-select-args connectable tableable args options)]
    (exists?* connectable tableable query options)))