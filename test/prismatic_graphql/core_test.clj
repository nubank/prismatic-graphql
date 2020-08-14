(ns prismatic-graphql.core-test
  (:require [clojure.test :refer :all]
            [prismatic-graphql.core :as core]
            [clojure.java.io :as io]
            [matcher-combinators.test :refer [match?]]
            [schema.core :as s])
  (:import [schema.core ConditionalSchema]))

(defn load-query [query-name]
  (slurp (io/resource (str "prismatic-graphql/test-data/" (name query-name) ".graphql"))))

(def schema (slurp (io/resource "prismatic-graphql/test-data/schema.graphql")))
(def options {:scalars {:Keyword s/Keyword}})

(deftest valid-schema?-test
  (testing "it returns true to a valid GraphQL schema"
    (is (true? (core/valid-schema? schema))))

  (testing "it returns false to an invalid GraphQL schema"
    (is (false? (core/valid-schema? "invalid schema")))))

(deftest valid-query?-test
  (testing "it returns true to a valid GraphQL Query"
    (is (true? (core/valid-query? (load-query :testQuery)))))

  (testing "it returns false to an invalid GraphQL Query"
    (is (false? (core/valid-query? "invalid query")))))

(deftest query->data-schema-test
  (testing "when creating data prismatic schema from a GraphQL query + schema"
    (testing "simple query"
      (is (= {:employee {:id                s/Str
                         :name              s/Str
                         :organizationRole  s/Str
                         :requirePermission s/Bool}}
             (core/query->data-schema schema (load-query :employeeQuery) options))))

    (testing "query with custom scalar"
      (is (= {:car {:brand s/Keyword
                    :model s/Str}}
             (core/query->data-schema schema (load-query :customScalarQuery) options))))

    (testing "query with aliases"
      (is (= {:car {:manufacture s/Keyword
                    :model       s/Str}}
             (core/query->data-schema schema (load-query :aliasQuery) options))))

    (testing "query with list result"
      (is (= {:task {:name (s/maybe s/Str)
                     :tags [(s/maybe s/Str)]}}
             (core/query->data-schema schema (load-query :listQuery) options))))

    (testing "on a mutation result"
      (is (= {:newPerson {:__typename (s/enum "Partner" "Customer" "Employee")
                          :name       s/Str}}
             (core/query->data-schema schema (load-query :newPersonMutation) options))))

    (testing "on a mutation with scalar result"
      (is (= {:newLoan (s/maybe s/Str)}
             (core/query->data-schema schema (load-query :camelCaseInputMutation) options))))

    ;; TODO: Test better conditional schemas
    (testing "with one interface"
      (is (match? {:customer {:id s/Str :name s/Str}
                   :person   (partial instance? ConditionalSchema)}
                  (core/query->data-schema schema (load-query :testQuery) options))))

    (testing "query with union result"
      (is (match? {:something (partial instance? ConditionalSchema)}
                  (core/query->data-schema schema (load-query :unionQuery) options))))

    (testing "when doing an interface or union query it required asking the __typename"
      (is (thrown-with-msg? AssertionError #"Assert failed: Unions and Interface Types Require __typename on query"
                            (core/query->data-schema schema (load-query :interfaceNoTypename) options))))

    (testing "does not accept queries with two ops"
      (is (thrown-with-msg? IllegalArgumentException #"no operation name supplied"
                            (core/query->data-schema schema (load-query :twoOps) options))))

    (testing "throws AssertionError on invalid query"
      (is (thrown-with-msg? AssertionError #"field not in scope: wololo"
                            (core/query->data-schema schema (load-query :invalidQuery) options))))))

(deftest conditional-schemas-test
  (testing "on simple interface schemas"
    (let [schema-checker (s/checker (core/query->data-schema schema (load-query :testQuery) options))]
      (testing "we can coerce valid values"
        (is (nil? (schema-checker {:customer {:id "123" :name "test"}
                                   :person   {:__typename "Employee"
                                              :name       "test"
                                              :position   "position"}})))
        (is (nil? (schema-checker {:customer {:id "123" :name "test"}
                                   :person   {:__typename "Customer"
                                              :name       "test"}}))))

      (testing "wrong values are not accepted"
        (is (= {:person {:position 'disallowed-key}}
               (schema-checker {:customer {:id "123" :name "test"}
                                :person   {:__typename "Customer"
                                           :name       "test"
                                           :position   "position"}})))
        (is (= {:person {:position 'missing-required-key}}
               (schema-checker {:customer {:id "123" :name "test"}
                                :person   {:__typename "Employee"
                                           :name       "test"}}))))))

  (testing "on complex union and interface schemas"
    (let [schema-checker (s/checker (core/query->data-schema schema (load-query :unionQuery) options))]
      (testing "we can coerce valid values"
        (is (nil? (schema-checker {:something {:__typename "Car"
                                               :model      "corsa"}})))
        (is (nil? (schema-checker {:something {:__typename "Customer"
                                               :name       "jonas"}})))
        (is (nil? (schema-checker {:something {:__typename "Partner"
                                               :name       "jonas"}})))
        (is (nil? (schema-checker {:something {:__typename "Employee"
                                               :position   "test"
                                               :name       "jonas"}})))
        (is (nil? (schema-checker {:something {:__typename nil}}))))

      (testing "wrong values are not accepted"
        (is (= {:something {:model 'disallowed-key
                            :name  'missing-required-key}}
               (schema-checker {:something {:__typename "Customer"
                                            :model      "corsa"}})))
        (is (= {:something {:model 'missing-required-key
                            :name  'disallowed-key}}
               (schema-checker {:something {:__typename "Car"
                                            :name       "jonas"}})))
        (is (= {:something {:model    'disallowed-key
                            :name     'missing-required-key
                            :position 'missing-required-key}}
               (schema-checker {:something {:__typename "Employee"
                                            :model      "corsa"}})))
        (is (= {:something {:position 'disallowed-key}}
               (schema-checker {:something {:__typename "Customer"
                                            :name       "jonas"
                                            :position   "test"}})))))))

(deftest query->variables-schema-test
  (testing "when creating variables prismatic schemas from GraphQL Query + Schema"
    (testing "simple query"
      (is (= {:id s/Str}
             (core/query->variables-schema schema (load-query :employeeQuery) options))))

    (testing "on query without variables - returns an empty map"
      (is (= {}
             (core/query->variables-schema schema (load-query :aliasQuery) options))))

    (testing "on a mutation with input type"
      (is (= {:input {:name s/Str
                      :age  s/Int
                      :tags [s/Str]}}
             (core/query->variables-schema schema (load-query :newPersonMutation) options))))))
