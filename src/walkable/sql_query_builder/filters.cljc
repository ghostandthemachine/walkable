(ns walkable.sql-query-builder.filters
  (:require [clojure.spec.alpha :as s]))

(s/def ::operators
  #{:nil? :not-nil?
    := :< :> :<= :>= :<> :like
    :not= :not-like
    :between :not-between
    :in :not-in})

(defn count-params
  [operator]
  (case operator
    (:nil? :not-nil?) 0
    (:= :< :> :<= :>= :like :not= :<> :!= :not-like) 1
    (:between :not-between) 2
    ;; default
    nil))

(s/def ::condition-value
  #(or (number? %) (string? %) (boolean? %)))

(s/def ::condition
  (s/&
    (s/cat
      :operator ::operators
      :params
      (s/alt
        :params (s/* ::condition-value)
        :params (s/coll-of ::condition-value)))
    #(if-let [n (count-params (:operator %))]
       (= n (count (second (:params %))))
       true)))

(s/def ::conditions
  (s/or
    :condition
    ::condition

    :conditions
    (s/cat
      :combinator (s/? #{:and :or})
      :conditions (s/+ ::conditions))))

(defn combine
  [operator conditions]
  (if (= 1 (count conditions))
    conditions
    (concat
      ["("]
      (interpose (if (= :or operator)
                   " OR "
                   " AND ")
        conditions)
      [")"])))

(defn combination-match?
  ([k x]
   (and
     (map? x)
     (contains? x k))))

(defn match?
  ([x]
   (and (vector? x)
     (= 2 (count x))
     (keyword? (first x))))
  ([k x]
   (and (vector? x)
     (= 2 (count x))
     (= k (first x)))))

(s/def ::clauses
  (s/or
    :clauses (s/coll-of (s/cat
                          :key #(and (keyword? %) (namespace %))
                          :conditions ::conditions)
               :into [])
    :clauses (s/cat
               :combinator (s/? #{:and :or})
               :clauses (s/+ ::clauses))))

(defn clauses? [x]
  (and (vector? x)
    (= 2 (count x))
    (= :clauses (first x))))

(defn parameterize-set [n]
  (str
    "("
    (clojure.string/join ", "
      (repeat n \?))
    ")"))

(defn parameterize-operator
  [operator key params]
  (case operator
    :nil?
    (str key " IS NULL")

    :not-nil?
    (str key " IS NOT NULL")

    (:= :< :> :<= :>= :like :<> :!=)
    (str key " " (name operator) " ?")

    :not-like
    (str key  " NOT LIKE ?")

    :not=
    (str key " != ?")

    :between
    (str key " BETWEEN ? AND ?")

    :not-between
    (str key " NOT BETWEEN ? AND ?")

    :in
    (str key " IN " (parameterize-set (count params)))

    :not-in
    (str key " NOT IN " (parameterize-set (count params)))))

(declare process-multi)
(declare process-clauses)

(defn process-multi
  [{:keys [key keymap] :as env} combinator x]
  (if (and (vector? x)
        (not (match? x)))
    (combine combinator (map #(process-clauses env %) x))
    (process-clauses env x)))

(defn process-clauses
  [{:keys [key keymap] :as env} x]
  (cond
    (match? x)
    (let [coll (second x)]
      (process-multi env :and coll))

    (combination-match? :clauses x)
    (let [{:keys [combinator clauses] k :key} x]
      (process-multi (assoc env :key (or k key))
        combinator clauses))

    (combination-match? :conditions x)
    (let [{:keys [combinator conditions] k :key} x]
      (process-multi (assoc env :key (or k key))
        combinator conditions))

    (combination-match? :operator x)
    (let [{:keys [operator params]} x
          params                    (second params)]
      {:condition (parameterize-operator operator
                    (get keymap key) params)
       :params    params})))

(defn parameterize
  [env clauses]
  (let [all              (flatten (process-clauses env
                                    (s/conform ::clauses clauses)))
        condition-string (apply str
                           (map #(if (map? %) (:condition %) %) all))
        parameters       (flatten (->> all (filter map?) (map :params)))]
    [condition-string parameters]))

(s/def ::order-by
  (s/coll-of (s/or
               :column keyword?
               :column+type (s/tuple keyword? #{:asc :desc}))
    :min-count 1
    :distinct true))

(defn standardize-order-by-form [coll]
  (map (fn [[type value]]
         (if (= type :column)
           {:column value}
           (let [[column asc|desc] value]
             {:column column
              :kind   asc|desc})))
    coll))

(defn ->order-by-string [column-names order-by]
  (let [form (s/conform ::order-by order-by)]
    (when-not (= :clojure.spec.alpha/invalid form)
      (let [form (->> (standardize-order-by-form form)
                   (filter #(contains? column-names (:column %))))]
        (when (seq form)
          (->> form
            (map (fn [{:keys [column kind]}]
                   (str (get column-names column)
                     ({:asc  " ASC"
                       :desc " DESC"} kind))))
            (clojure.string/join ", " )))))))