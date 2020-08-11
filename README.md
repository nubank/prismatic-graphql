# prismatic-graphql

A Clojure library designed to create Prismatic Schemas from GraphQL query definitions.

## Usage

```clojure
(require '[prismatic-graphql.core :as prismatic.graphql])

(def schema (slurp "./my-graphql-schema.graphql"))
(def query (slurp "./my-graphql-query.graphql"))

(prismatic.graphql/query->data-schema schema query)
(prismatic.graphql/query->variables-schema schema query)
```

## License

Copyright © 2020 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
