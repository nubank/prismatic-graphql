(defproject prismatic-graphql "0.1.0-SNAPSHOT"
  :description "Clojure library for creating Prismatic Schemas from GraphQL Queries"
  :url "https://github.com/nubank/prismatic-graphql"
  :license {:name "Apache 2.0"
            :url  "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [alumbra/parser "0.1.6"]
                 [prismatic/schema "1.1.11"]
                 [alumbra/validator "0.2.1"]
                 [alumbra/analyzer "0.1.17"]])
