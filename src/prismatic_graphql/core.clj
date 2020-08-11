(ns prismatic-graphql.core
  (:require [schema.core :as s]
            [alumbra.analyzer :as analyzer]
            [alumbra.parser :as parser])
  (:import [java.time LocalDateTime LocalDate LocalTime]))

;; Type Definitions

(s/defschema Schema (s/pred (partial satisfies? s/Schema)))

(s/defschema ScalarMapping
  {s/Keyword Schema})

(s/defschema EnumBuilder
  (s/=> Schema [#{s/Any}]))

(s/defschema Options
  {:scalars ScalarMapping
   :enum-fn EnumBuilder})

;; Internal

(def ^:private default-scalars
  {:Uuid       s/Uuid
   :Int        s/Int
   :BigDecimal BigDecimal
   :Double     s/Num
   :Float      s/Num
   :String     s/Str
   :ID         s/Str
   :Boolean    s/Bool
   :Date       LocalDate
   :Time       LocalTime
   :DateTime   LocalDateTime})

(defn- parse-schema*
  [schema-string]
  (analyzer/analyze-schema schema-string parser/parse-schema))
(def ^:private parse-schema (memoize parse-schema*))

(defn- parse-query*
  [query-string]
  (parser/parse-document query-string))
(def ^:private parse-query (memoize parse-query*))

(defn- nullability
  [type non-null?]
  (if non-null? type (s/maybe type)))

(declare graphql-type->schema-type)
(defn- type-description->schema-type
  [as type-description options]
  (if (:type-description type-description)
    (nullability [(type-description->schema-type as (:type-description type-description) options)] (:non-null? type-description))
    (nullability (graphql-type->schema-type as (:type-name type-description) options) (:non-null? type-description))))

(defn- get-scalar-type
  [scalar-type {:keys [scalars]}]
  (get (merge default-scalars scalars) (keyword scalar-type)))

(defn- type->kind
  [analyzed-schema gql-type]
  (get (:type->kind analyzed-schema) gql-type))

(defn- get-enum-type
  [analyzed-schema gql-type {:keys [enum-fn]}]
  (-> (get (:enums analyzed-schema) gql-type)
      :enum-values
      set
      enum-fn))

(defn- get-input-type
  [analyzed-schema gql-type options]
  (->> (get (:input-types analyzed-schema) gql-type)
       :fields
       (reduce
        (fn [acc [k v]]
          (assoc acc (keyword k) (type-description->schema-type analyzed-schema (:type-description v) options)))
        {})))

(defn- graphql-type->schema-type
  [analyzed-schema gql-type options]
  (case (type->kind analyzed-schema gql-type)
    :input-type
    (get-input-type analyzed-schema gql-type options)

    :scalar
    (get-scalar-type gql-type options)

    :enum
    (get-enum-type analyzed-schema gql-type options)))

(declare reduce-schema)
(defn- inner-list
  [as {:keys [field-spec]} options]
  (let [{:keys [selection-set non-null? type-name field-type]} field-spec]
    [(nullability (if (= :object field-type)
                    (reduce-schema as selection-set options)
                    (graphql-type->schema-type as type-name options))
                  non-null?)]))

(defn- resolve-type-name
  [base-schema type-name {:keys [type->kind unions interfaces]}]
  (->> (cond
         (= :interface (type->kind type-name))
         (do
           (assert (:__typename base-schema) "Interfaces Types Require __typename on query")
           (apply s/enum (:implemented-by (get interfaces type-name))))

         (= :union (type->kind type-name))
         (do
           (assert (:__typename base-schema) "Unions Types Require __typename on query")
           (apply s/enum (:union-types (get unions type-name))))

         :else
         (s/eq type-name))
       (assoc base-schema :__typename)))

(defn- graphql-interfaces->schema
  [analyzed-schema base-schema interface-types options]
  (apply s/conditional
         (concat
          (mapcat
           (fn [[type-condition variation-type]]
             [(comp type-condition :__typename)
              (merge (reduce-schema analyzed-schema (:selection-set (first variation-type)) options)
                     base-schema)])
           interface-types)
          [:else base-schema])))

(defn- graphql-object->schema
  [analyzed-schema {:keys [type-name selection-set]} options]
  (let [grouped-types   (group-by :type-condition selection-set)
        base-schema     (-> (reduce-schema analyzed-schema (get grouped-types nil) options)
                            (resolve-type-name type-name analyzed-schema))
        interface-types (filter (comp some? key) grouped-types)]
    (if (seq interface-types)
      (graphql-interfaces->schema analyzed-schema base-schema interface-types options)
      base-schema)))

(defn- reduce-schema
  [analyzed-schema selection-set options]
  (reduce
   (fn [acc {:keys [field-type field-alias non-null? type-name] :as ca}]
     (-> (case field-type
           :object
           (graphql-object->schema analyzed-schema ca options)

           :list
           (inner-list analyzed-schema ca options)

           :leaf
           (graphql-type->schema-type analyzed-schema type-name options))
         (nullability non-null?)
         (->> (assoc acc (keyword field-alias)))))
   {} selection-set))

;; Public API

(def default-options
  {:enum-fn #(apply s/enum %)})

(defn query->data-schema
  ([schema-string query-string]
   (query->data-schema schema-string query-string default-options))
  ([schema-string query-string options]
   (let [schema (parse-schema schema-string)
         query  (parse-query query-string)]
     (with-redefs [alumbra.canonical.arguments/resolve-arguments (fn [_ _ _] nil)
                   alumbra.canonical.variables/resolve-variables (fn [opts _] opts)]
       (as-> (analyzer/canonicalize-operation schema query) $
             (reduce-schema schema (:selection-set $) (merge default-options options)))))))

(defn query->variables-schema
  ([schema-string query-string]
   (query->variables-schema schema-string query-string default-options))
  ([schema-string query-string options]
   (let [schema (parse-schema schema-string)
         query  (parse-query query-string)]
     (->> query
          :alumbra/operations
          first
          :alumbra/variables
          (reduce
           (fn [acc var]
             (let [type-name (-> var :alumbra/type :alumbra/type-name)
                   non-null? (-> var :alumbra/type :alumbra/non-null?)]
               (->> (nullability (graphql-type->schema-type schema type-name (merge default-options options)) non-null?)
                    (assoc acc (:alumbra/variable-name var)))))
           {})))))
