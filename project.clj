(defproject nubank/prismatic-graphql "0.1.1"
  :description "Clojure library for creating Prismatic Schemas from GraphQL Queries"
  :url "https://github.com/nubank/prismatic-graphql"
  :license {:name "Apache 2.0"
            :url  "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [leoiacovini/alumbra.parser "0.1.9-SNAPSHOT"]
                 [prismatic/schema "1.1.11"]
                 [alumbra/validator "0.2.1"]
                 [alumbra/analyzer "0.1.17"]]

  :profiles {:dev {:dependencies [[nubank/matcher-combinators "3.0.1"]]}})
