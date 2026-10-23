<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# paimon-elasticsearch: ES Index (Multi-Column Global Index)

`paimon-elasticsearch` provides the `es-index` global index: a **multi-column, multi-modal**
index. A single `es-index` file indexes a **primary column** (typically a vector) together with
optional **companion columns** — full-text, keyword, scalar, and date — so vector search and
full-text search can be served from the same index, and companion columns stay available for scoring
and filtering inside one index definition.

Unlike the single-purpose vector and full-text global indexes — each of which indexes one column with
one dedicated engine — `es-index` groups a primary column plus optional companion columns into one
index. The primary column determines the main modality (usually a vector); companion columns are
indexed according to their data type and per-field options.

The module was renamed from `paimon-eslib` to `paimon-elasticsearch`. Use Maven artifact
`org.apache.paimon:paimon-elasticsearch` and profile `-Ppaimon-elasticsearch`. The Java package
`org.apache.paimon.eslib`, the `es-index` SPI name, table option keys and archive formats are retained.

## Build and runtime package

Java is the default backend (`global-index.es-index.backend=java`). It uses the published
ESLib 1.0.7 / Lucene 9.12.0 dependencies declared in this module's POM, on JDK 11+.
Deploy the module and its runtime dependencies to the Spark driver/executors or Flink client/TaskManagers.
No elasticpp SO is needed for Java construction or queries. IK additionally needs a compatible IK analyzer JAR.

```sh
mvn -Ppaimon-elasticsearch -pl paimon-elasticsearch -am package
```

Java writes preserve ESM1 v2; legacy Java v1/v2 archives use the Java reader. New native writes
remain available with explicit `global-index.es-index.backend=elasticpp` and produce ESM1 v3.
The persisted backend selects the reader even when current table options have changed.
Offset-only legacy metadata still requires the original field configuration in table options.
A backend option does not convert existing archive files.

For native reads/writes, also supply JNA and the verified `linux-x86_64-native` classifier:

```sh
mvn -Ppaimon-elasticsearch -pl paimon-elasticsearch -am \
  -Dpaimon.es.native.bundle=/absolute/path/to/qualified-bundle package
```

The bundle's `native-manifest.json`, shared-library hashes, C ABI, CPU and glibc requirements
are checked before loading. No SO is downloaded automatically. This change publishes no native
runtime release. Java archives are not made readable by the C++ SDK by this adapter.

The original native design and acceptance documents describe the preceding native-only revision:
[design](docs/native-direct.html), [acceptance](docs/native-direct-acceptance.html).
Elastic License 2.0 notices and source provenance are retained in `LICENSE-ELASTIC-2.0.txt`
and `NOTICE.native-direct.txt`.

See the general [Global Index](../docs/docs/multimodal-table/global-index.mdx) documentation for the
required Data Evolution table properties, coverage/freshness behavior, and shared build options.

Spark SQL can query one grouped Java or native index with
`paimon_es_search(catalog_name, query_json)`. See the
[HTML usage guide](docs/paimon-es-search.html) for text and filter-KNN examples, the bounded
version-1 SEARCH contract, snapshot coverage checks, source decoding and Java/native tests. Flink provides `CALL sys.paimon_es_search(query_json)`;
the Java SDK provides `EsIndexSearch.search(catalog, query_json)` using the same contract.

## How columns map to index types

For each indexed column, `es-index` decides the sub-index type from the column data type, unless you
override it with `fields.<field>.type`:

| Column data type | Default sub-index | Override with `type` |
|---|---|---|
| `VECTOR<FLOAT>` / `ARRAY<FLOAT>` | Vector (ANN) | `vector` |
| `STRING` | Full-text primary + keyword sub-field | `fulltext` or `keyword` |
| `TIMESTAMP` / `DATE` | Long scalar | `date` |
| supported numeric scalars | Scalar | — |

Text columns always provide both capabilities. A `fulltext` primary field gets a `<column>.keyword`
exact-match sub-field; a `keyword` primary field gets a `<column>.fulltext` analyzed sub-field.

## Prerequisites

Create a Data Evolution table with the required properties:

```sql
CREATE TABLE my_table (
    id INT,
    embedding ARRAY<FLOAT>,
    content STRING,
    category STRING,
    price INT
) TBLPROPERTIES (
    'bucket' = '-1',
    'row-tracking.enabled' = 'true',
    'data-evolution.enabled' = 'true',
    'global-index.enabled' = 'true'
);
```

## Build ES Index

Build the index with `sys.create_global_index` and `index_type => 'es-index'`. To index several
columns in one `es-index`, pass a **comma-separated** `index_column` list: the **first** column is
the primary column, the rest are companion columns. Per-column behavior is configured through options
under the `global-index.es-index.` prefix.

### Spark SQL

```sql
-- Single vector column with an HNSW index
CALL sys.create_global_index(
    table => 'db.my_table',
    index_column => 'embedding',
    index_type => 'es-index',
    options => 'global-index.es-index.fields.embedding.algorithm=hnsw,global-index.es-index.fields.embedding.dimension=768,global-index.es-index.fields.embedding.metric=cosine'
);

-- Multi-column: vector (primary) + full-text + keyword + scalar in one index.
-- 'content' becomes full-text because an analyzer is set; 'category' stays keyword; 'price' scalar.
CALL sys.create_global_index(
    table => 'db.my_table',
    index_column => 'embedding,content,category,price',
    index_type => 'es-index',
    options => 'global-index.es-index.fields.embedding.algorithm=diskbbq,global-index.es-index.fields.embedding.dimension=768,global-index.es-index.fields.embedding.metric=cosine,global-index.es-index.fields.content.analyzer=standard,global-index.es-index.fields.category.type=keyword'
);
```

### Flink SQL

```sql
-- Single vector column with an HNSW index
CALL sys.create_global_index(
    `table` => 'db.my_table',
    index_column => 'embedding',
    index_type => 'es-index',
    options => 'global-index.es-index.fields.embedding.algorithm=hnsw,global-index.es-index.fields.embedding.dimension=768,global-index.es-index.fields.embedding.metric=cosine'
);

-- Multi-column: vector (primary) + full-text + keyword + scalar in one index.
CALL sys.create_global_index(
    `table` => 'db.my_table',
    index_column => 'embedding,content,category,price',
    index_type => 'es-index',
    options => 'global-index.es-index.fields.embedding.algorithm=diskbbq,global-index.es-index.fields.embedding.dimension=768,global-index.es-index.fields.embedding.metric=cosine,global-index.es-index.fields.content.analyzer=standard,global-index.es-index.fields.category.type=keyword'
);
```

You can also set the same options in `TBLPROPERTIES` at table-creation time instead of passing them
per build. Options are resolved with field-level keys taking precedence over index-type-level keys:
`global-index.es-index.fields.<field>.<key>` overrides `global-index.es-index.<key>`.

## Options

Index-type-level keys use the `global-index.es-index.<key>` prefix and apply to every column of that
kind; field-level keys use `global-index.es-index.fields.<field>.<key>` and override them for one
column.

| Key | Applies to | Default | Description |
|---|---|---|---|
| `type` | any column | inferred from data type | Force the sub-index type: `vector`, `fulltext`, `keyword`, `date`. |
| `algorithm` | vector | `hnsw` | Vector algorithm: `hnsw`, `int8_hnsw`, or `diskbbq`. `native` is not supported by paimon-elasticsearch. |
| `dimension` | vector | inferred for `VECTOR<FLOAT>` | Vector dimension (1–1024). Required for `ARRAY<FLOAT>`; taken from the type for `VECTOR<FLOAT>`. |
| `metric` | vector | `cosine` | Native metrics: `cosine`, `l2`, `dot_product`. Dot-product vectors must have unit length; maximum-inner-product mappings are currently rejected. |
| `m` | vector (`hnsw`, `int8_hnsw`) | engine default | Java accepts this tuning; the qualified native bridge rejects custom values. |
| `ef_construction` | vector (`hnsw`, `int8_hnsw`) | engine default | Java accepts this tuning; the qualified native bridge rejects custom values. |
| `vectors_per_cluster` | vector (`diskbbq`) | engine default | Java accepts this tuning; the qualified native bridge rejects custom values. |
| `centroids_per_parent_cluster` | vector (`diskbbq`) | engine default | Java accepts this tuning; the qualified native bridge rejects custom values. |
| `analyzer` | string | `standard` | Text analyzer used by the full-text primary field or `.fulltext` sub-field: `standard`, `whitespace`, `simple`, `keyword`, `ik_smart`, or `ik_max_word`. IK requires `ES_PATH_CONF/analysis-ik` with pinned `main.dic`, `quantifier.dic` and `stopword.dic`. |

The qualified bridge rejects nonfinite scalar values, fuzzy full-text queries and unqualified
vector tuning. Unsupported ordinary scalar predicates retain all rows for residual evaluation,
including rows from grouped index shards, to avoid false negatives.

## Query

`es-index` implements the same search API as the other global indexes, so querying does not depend on
which engine built the index — only the build step differs.

### Spark SQL

```sql
-- Vector search: top-5 nearest neighbors on the primary vector column
SELECT * FROM vector_search('my_table', 'embedding', array(1.0f, 2.0f, 3.0f), 5);

-- Full-text search on a companion full-text column, using the JSON query DSL
SELECT * FROM full_text_search(
    'my_table',
    'content',
    '{"match":{"query":"paimon lake format"}}',
    10
);
```

Vector routes and full-text routes over an `es-index` can also be combined with the
`hybrid_search(...)` table-valued function.

### Flink SQL

Flink exposes vector search as a `CALL` procedure. The procedure returns JSON-serialized rows as
strings.

```sql
-- Vector search: top-5 nearest neighbors on the primary vector column
CALL sys.vector_search(
    `table` => 'db.my_table',
    vector_column => 'embedding',
    query_vector => '1.0,2.0,3.0',
    top_k => 5
);

-- Optionally restrict the returned columns
CALL sys.vector_search(
    `table` => 'db.my_table',
    vector_column => 'embedding',
    query_vector => '1.0,2.0,3.0',
    top_k => 5,
    projection => 'id,content'
);
```

> **Note.** Flink SQL currently exposes vector search only. For full-text search and hybrid search
> over an `es-index`, use Spark SQL (the `full_text_search` / `hybrid_search` table-valued functions)
> or the Java API (`Table.newFullTextSearchBuilder()` / `Table.newHybridSearchBuilder()`).

## Drop ES Index

### Spark SQL

```sql
CALL sys.drop_global_index(
    table => 'db.my_table',
    index_column => 'embedding',
    index_type => 'es-index'
);
```

### Flink SQL

```sql
CALL sys.drop_global_index(
    `table` => 'db.my_table',
    index_column => 'embedding',
    index_type => 'es-index'
);
```
