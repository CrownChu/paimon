/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.lucene.index;

import org.apache.paimon.options.Options;

import java.io.Serializable;

/** Configuration options for the Lucene global index. */
public class LuceneIndexOptions implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String KEY_DIMENSION = "lucene.vector.dimension";
    public static final String KEY_SIMILARITY = "lucene.vector.similarity";

    public static final int DEFAULT_DIMENSION = 128;
    public static final String DEFAULT_SIMILARITY = "euclidean";

    private final int dimension;
    private final String similarity;

    public LuceneIndexOptions(Options options) {
        this.dimension = options.getInteger(KEY_DIMENSION, DEFAULT_DIMENSION);
        this.similarity = options.getString(KEY_SIMILARITY, DEFAULT_SIMILARITY);
    }

    public int dimension() {
        return dimension;
    }

    public String similarity() {
        return similarity;
    }
}
