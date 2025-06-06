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

package org.apache.flink.table.catalog;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.expressions.DefaultSqlFactory;
import org.apache.flink.table.expressions.SqlFactory;
import org.apache.flink.util.Preconditions;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated {@link CatalogTable} that is backed by the original metadata coming from the {@link
 * Catalog} but resolved by the framework.
 *
 * <p>Note: Compared to {@link CatalogTable}, instances of this class are serializable for
 * persistence if and only if the originating {@link CatalogTable} implements {@link
 * CatalogTable#getOptions()}. Catalog implementations are encouraged to use {@link
 * ResolvedCatalogTable#toProperties(SqlFactory)}.
 */
@PublicEvolving
public final class ResolvedCatalogTable
        implements ResolvedCatalogBaseTable<CatalogTable>, CatalogTable {

    private final CatalogTable origin;

    private final ResolvedSchema resolvedSchema;

    public ResolvedCatalogTable(CatalogTable origin, ResolvedSchema resolvedSchema) {
        this.origin =
                Preconditions.checkNotNull(origin, "Original catalog table must not be null.");
        this.resolvedSchema =
                Preconditions.checkNotNull(resolvedSchema, "Resolved schema must not be null.");
    }

    @Override
    public CatalogTable getOrigin() {
        return origin;
    }

    @Override
    public ResolvedSchema getResolvedSchema() {
        return resolvedSchema;
    }

    /**
     * Convenience method for {@link Catalog} implementations for serializing instances of this
     * class into a map of string properties. Instances are serializable for persistence if and only
     * if the originating {@link CatalogTable} implements {@link CatalogTable#getOptions()}.
     *
     * <p>{@link CatalogTable#fromProperties(Map)} provides the reverse operation for
     * deserialization. Note that the serialization and deserialization of catalog tables are not
     * symmetric. The framework will resolve functions and perform other validation tasks. A catalog
     * implementation must not deal with this during a read operation.
     */
    public Map<String, String> toProperties() {
        return toProperties(DefaultSqlFactory.INSTANCE);
    }

    /**
     * Convenience method for {@link Catalog} implementations for serializing instances of this
     * class into a map of string properties. Instances are serializable for persistence if and only
     * if the originating {@link CatalogTable} implements {@link CatalogTable#getOptions()}.
     *
     * <p>{@link CatalogTable#fromProperties(Map)} provides the reverse operation for
     * deserialization. Note that the serialization and deserialization of catalog tables are not
     * symmetric. The framework will resolve functions and perform other validation tasks. A catalog
     * implementation must not deal with this during a read operation.
     */
    public Map<String, String> toProperties(SqlFactory sqlFactory) {
        return CatalogPropertiesUtil.serializeCatalogTable(this, sqlFactory);
    }

    // --------------------------------------------------------------------------------------------
    // Delegations to original CatalogTable
    // --------------------------------------------------------------------------------------------

    @Override
    public Map<String, String> getOptions() {
        return origin.getOptions();
    }

    @Override
    public Schema getUnresolvedSchema() {
        return origin.getUnresolvedSchema();
    }

    @Override
    public String getComment() {
        return origin.getComment();
    }

    @Override
    public CatalogBaseTable copy() {
        return new ResolvedCatalogTable((CatalogTable) origin.copy(), resolvedSchema);
    }

    @Override
    public Optional<String> getDescription() {
        return origin.getDescription();
    }

    @Override
    public Optional<String> getDetailedDescription() {
        return origin.getDetailedDescription();
    }

    @Override
    public boolean isPartitioned() {
        return origin.isPartitioned();
    }

    @Override
    public List<String> getPartitionKeys() {
        return origin.getPartitionKeys();
    }

    @Override
    public Optional<Long> getSnapshot() {
        return origin.getSnapshot();
    }

    @Override
    public Optional<TableDistribution> getDistribution() {
        return origin.getDistribution();
    }

    @Override
    public ResolvedCatalogTable copy(Map<String, String> options) {
        return new ResolvedCatalogTable(origin.copy(options), resolvedSchema);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResolvedCatalogTable that = (ResolvedCatalogTable) o;
        return Objects.equals(origin, that.origin)
                && Objects.equals(resolvedSchema, that.resolvedSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, resolvedSchema);
    }

    @Override
    public String toString() {
        return "ResolvedCatalogTable{"
                + "origin="
                + origin
                + ", resolvedSchema="
                + resolvedSchema
                + '}';
    }
}
