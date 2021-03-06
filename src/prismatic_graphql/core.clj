(ns prismatic-graphql.core
  (:require [schema.core :as s]
            [alumbra.analyzer :as analyzer]
            [alumbra.parser :as parser]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [java.time LocalDateTime LocalDate LocalTime]))

;; Type Definitions

(s/defschema Schema (s/pred (partial satisfies? s/Schema)))

(s/defschema ScalarMapping
  {s/Keyword Schema})

(s/defschema EnumBuilder
  (s/=> Schema [#{s/Any}]))

(s/defschema Options
  {(s/optional-key :scalars) ScalarMapping
   (s/optional-key :enum-fn) EnumBuilder
   (s/optional-key :loose?)  s/Bool})

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

(def ^:private BaseGraphQLSchema "prismatic-graphql/BaseGraphQLSchema.graphql")

(defn- parse-schema*
  [schema-string]
  (->> {:base-schema (io/resource BaseGraphQLSchema)}
       (analyzer/analyze-schema schema-string parser/parse-schema)))
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
  (if-let [schema-type (get (merge default-scalars scalars) (keyword scalar-type))]
    schema-type
    (throw (ex-info (str "Scalar " scalar-type " do not have a mapped prismatic schema type.")
                    {:scalar-type scalar-type}))))

(defn- loose-schema
  [schema]
  (assoc schema s/Keyword s/Any))

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
  (cond-> (->> (get (:input-types analyzed-schema) gql-type)
               :fields
               (reduce
                (fn [acc [k v]]
                  (assoc acc (keyword k) (type-description->schema-type analyzed-schema (:type-description v) options)))
                {}))
    (:loose? options) loose-schema))

(defn- graphql-type->schema-type
  [analyzed-schema gql-type options]
  (case (type->kind analyzed-schema gql-type)
    :input-type
    (get-input-type analyzed-schema gql-type options)

    :scalar
    (get-scalar-type gql-type options)

    :enum
    (get-enum-type analyzed-schema gql-type options)))

(defn- resolve-into-concrete
  [{:keys [type->kind unions interfaces] :as schema} type-name]
  (case (type->kind type-name)
    :interface (set (mapcat (partial resolve-into-concrete schema) (:implemented-by (interfaces type-name))))
    :union (set (mapcat (partial resolve-into-concrete schema) (:union-types (unions type-name))))
    #{type-name}))

(defn- type-condition->concrete-types
  [schema type-condition]
  (set (mapcat (partial resolve-into-concrete schema) type-condition)))

(defn- type-name-condition [type-condition]
  #(some-> % :__typename name type-condition))

(declare reduce-schema)
(declare graphql-object->schema)
(defn- inner-list
  [as {:keys [field-spec]} options]
  (let [{:keys [non-null? type-name field-type] :as op} field-spec]
    [(nullability (if (= :object field-type)
                    (graphql-object->schema as op {} options)
                    (graphql-type->schema-type as type-name options))
                  non-null?)]))

(defn- graphql-interfaces->schema
  [analyzed-schema base-schema interface-types {:keys [enum-fn] :as options}]
  (assert (:__typename base-schema) "Unions and Interface Types Require __typename on query")
  (apply s/conditional
         (concat
          (mapcat
           (fn [[type-condition variation-type]]
             [(type-name-condition type-condition)
              (merge (graphql-object->schema analyzed-schema (first variation-type) base-schema options)
                     base-schema
                     {:__typename (enum-fn (type-condition->concrete-types analyzed-schema type-condition))})])
           interface-types)
          [:else (update base-schema :__typename #(enum-fn (set/difference (set (:vs %)) (set (mapcat key interface-types)))))])))

(defn- add-type-name
  [prismatic-schema analyzed-schema {:keys [type-name type-condition]} {:keys [enum-fn]}]
  (if-let [interface-types (seq (type-condition->concrete-types analyzed-schema type-condition))]
    (assoc prismatic-schema :__typename (enum-fn interface-types))
    (if (:__typename prismatic-schema)
      (assoc prismatic-schema :__typename (enum-fn (resolve-into-concrete analyzed-schema type-name)))
      prismatic-schema)))

(defn- reduce-selections
  [analyzed-schema selection-set options]
  (reduce
   (fn [current-schema {:keys [field-type field-alias non-null? type-name] :as ca}]
     (cond
       (= field-type :object)
       (-> (graphql-object->schema analyzed-schema ca {} options)
           (nullability non-null?)
           (->> (assoc current-schema (keyword field-alias))))

       (= field-type :list)
       (-> (inner-list analyzed-schema ca options)
           (nullability non-null?)
           (->> (assoc current-schema (keyword field-alias))))

       (= field-type :leaf)
       (-> (graphql-type->schema-type analyzed-schema type-name options)
           (nullability non-null?)
           (->> (assoc current-schema (keyword field-alias))))))
   {}
   selection-set))

(defn- base-object-schema
  [analyzed-schema selections operation current-schema options]
  (cond-> (-> (reduce-selections analyzed-schema selections options)
              (->> (merge current-schema))
              (add-type-name analyzed-schema operation options))
    (:loose? options) loose-schema))

(defn- graphql-object->schema
  [analyzed-schema {:keys [selection-set] :as operation} current-schema options]
  (let [grouped-selections   (group-by :type-condition selection-set)
        common-selections    (get grouped-selections nil)
        common-schema        (base-object-schema analyzed-schema common-selections operation current-schema options)
        condition-selections (filter (comp some? key) grouped-selections)]
    (if (seq condition-selections)
      (graphql-interfaces->schema analyzed-schema common-schema condition-selections options)
      common-schema)))

;; Public API

(s/def default-options :- Options
  {:enum-fn #(if (seq %)
               (apply s/enum %)
               (s/eq nil))})

(s/defn valid-schema? :- s/Bool
  "Returns true if the schema-string is a valid GraphQL Schema, false otherwise"
  [schema-string :- s/Str]
  (nil? (:alumbra/parser-errors (parse-schema schema-string))))

(s/defn valid-query? :- s/Bool
  "Returns true if the query-string is a syntactically correct GraphQL Query document, false otherwise. (this does no check if the query is compliant to a specific schema)"
  [query-string :- s/Str]
  (nil? (:alumbra/parser-errors (parse-query query-string))))

(s/defn query->data-schema :- Schema
  "Receives a string containing a GraphQL Schema in SDL and a GraphQL Query String and returns the Prismatic Schema of the
   data response when executing the passed query against the provided schema. An optional `options` argument can be provided
   with the following options:

   <:scalars> - A map containing the Keyword representation of a custom GraphQL Scalar and the corresponding Prismatic Schema
    type to be used. (this is required if your schema contains custom Scalars, they are going to be merged with the default ones)

   <:enum-fn> - A function that receives a set and should return a Prismatic Schema representing an enum made of the elements
    present in the set provided. By default this uses the `s/enum` from Prismatic, can be used to provide a loose-enum implementation.

   <:loose?> - Allows for extra keys in the generated schemas"
  ([schema-string :- s/Str
    query-string :- s/Str]
   (query->data-schema schema-string query-string default-options))
  ([schema-string :- s/Str
    query-string :- s/Str
    options :- Options]
   (let [schema (parse-schema schema-string)
         query  (parse-query query-string)]
     (with-redefs [alumbra.canonical.arguments/resolve-arguments (fn [_ _ _] nil)
                   alumbra.canonical.variables/resolve-variables (fn [opts _] opts)]
       (as-> (analyzer/canonicalize-operation schema query) $
             (graphql-object->schema schema $ {} (merge default-options options)))))))

(s/defn query->variables-schema :- Schema
  "Receives a string containing a GraphQL Schema in SDL and a GraphQL Query String and returns the Prismatic Schema of the
   variables required when executing the passed query against the provided schema. An optional `options` argument can be provided
   with the following options:

   <:scalars> - A map containing the Keyword representation of a custom GraphQL Scalar and the corresponding Prismatic Schema
    type to be used. (this is required if your schema contains custom Scalars, they are going to be merged with the default ones)

   <:enum-fn> - A function that receives a set and should return a Prismatic Schema representing an enum made of the elements
    present in the set provided. By default this uses the `s/enum` from Prismatic, can be used to provide a loose-enum implementation.

   <:loose?> - Allows for extra keys in the generated schemas"
  ([schema-string :- s/Str
    query-string :- s/Str]
   (query->variables-schema schema-string query-string default-options))
  ([schema-string :- s/Str
    query-string :- s/Str
    options :- Options]
   (let [schema (parse-schema schema-string)
         query  (parse-query query-string)]
     (cond->
       (->> query
            :alumbra/operations
            first
            :alumbra/variables
            (reduce
             (fn [acc var]
               (let [type-name (-> var :alumbra/type :alumbra/type-name)
                     non-null? (-> var :alumbra/type :alumbra/non-null?)]
                 (->> (nullability (graphql-type->schema-type schema type-name (merge default-options options)) non-null?)
                      (assoc acc (keyword (:alumbra/variable-name var))))))
             {}))
       (:loose? options) loose-schema))))
