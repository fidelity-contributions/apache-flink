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

package org.apache.flink.table.planner.functions.bridging;

import org.apache.flink.table.api.TableException;
import org.apache.flink.table.catalog.ContextResolvedFunction;
import org.apache.flink.table.catalog.ContextResolvedProcedure;
import org.apache.flink.table.catalog.DataTypeFactory;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.functions.AggregateFunctionDefinition;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.functions.FunctionIdentifier;
import org.apache.flink.table.functions.ScalarFunctionDefinition;
import org.apache.flink.table.functions.TableAggregateFunctionDefinition;
import org.apache.flink.table.functions.TableFunctionDefinition;
import org.apache.flink.table.functions.UserDefinedFunction;
import org.apache.flink.table.functions.UserDefinedFunctionHelper;
import org.apache.flink.table.planner.functions.inference.TypeInferenceOperandChecker;
import org.apache.flink.table.planner.functions.inference.TypeInferenceOperandInference;
import org.apache.flink.table.planner.functions.inference.TypeInferenceReturnInference;
import org.apache.flink.table.types.inference.TypeInference;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;

import javax.annotation.Nullable;

import java.util.Optional;

/** Utilities for bridging {@link FunctionDefinition} with Calcite's representation of functions. */
final class BridgingUtils {
    static String createName(ContextResolvedFunction resolvedFunction) {
        return resolvedFunction
                .getIdentifier()
                .map(BridgingUtils::extractName)
                .orElseGet(() -> createInlineFunctionName(resolvedFunction.getDefinition()));
    }

    static String createName(ContextResolvedProcedure resolveProcedure) {
        return extractName(resolveProcedure.getIdentifier());
    }

    private static String extractName(FunctionIdentifier identifier) {
        if (identifier.getSimpleName().isPresent()) {
            return identifier.getSimpleName().get();
        }
        return identifier
                .getIdentifier()
                .map(ObjectIdentifier::getObjectName)
                .orElseThrow(IllegalStateException::new);
    }

    private static String createInlineFunctionName(FunctionDefinition functionDefinition) {
        final Optional<UserDefinedFunction> userDefinedFunction =
                extractUserDefinedFunction(functionDefinition);

        return userDefinedFunction
                .map(UserDefinedFunctionHelper::generateInlineFunctionName)
                .orElseThrow(
                        () ->
                                new TableException(
                                        String.format(
                                                "Unsupported function definition: %s. Only user defined functions are supported as inline functions.",
                                                functionDefinition)));
    }

    private static Optional<UserDefinedFunction> extractUserDefinedFunction(
            FunctionDefinition functionDefinition) {
        if (functionDefinition instanceof UserDefinedFunction) {
            return Optional.of((UserDefinedFunction) functionDefinition);
        } else if (functionDefinition instanceof ScalarFunctionDefinition) {
            return Optional.ofNullable(
                    ((ScalarFunctionDefinition) functionDefinition).getScalarFunction());
        } else if (functionDefinition instanceof AggregateFunctionDefinition) {
            return Optional.ofNullable(
                    ((AggregateFunctionDefinition) functionDefinition).getAggregateFunction());
        } else if (functionDefinition instanceof TableFunctionDefinition) {
            return Optional.ofNullable(
                    ((TableFunctionDefinition) functionDefinition).getTableFunction());
        } else if (functionDefinition instanceof TableAggregateFunctionDefinition) {
            return Optional.ofNullable(
                    ((TableAggregateFunctionDefinition) functionDefinition)
                            .getTableAggregateFunction());
        }
        return Optional.empty();
    }

    static @Nullable SqlIdentifier createSqlIdentifier(ContextResolvedFunction resolvedFunction) {
        return resolvedFunction
                .getIdentifier()
                .map(
                        fi ->
                                fi.getIdentifier()
                                        .map(
                                                oi ->
                                                        new SqlIdentifier(
                                                                oi.toList(), SqlParserPos.ZERO))
                                        .orElseGet(
                                                () ->
                                                        new SqlIdentifier(
                                                                fi.getSimpleName()
                                                                        .orElseThrow(
                                                                                IllegalStateException
                                                                                        ::new),
                                                                SqlParserPos.ZERO)))
                .orElse(null);
    }

    static SqlIdentifier createSqlIdentifier(ContextResolvedProcedure resolvedProcedure) {
        final FunctionIdentifier fi = resolvedProcedure.getIdentifier();
        return fi.getIdentifier()
                .map(oi -> new SqlIdentifier(oi.toList(), SqlParserPos.ZERO))
                .orElseGet(
                        () ->
                                new SqlIdentifier(
                                        fi.getSimpleName().orElseThrow(IllegalStateException::new),
                                        SqlParserPos.ZERO));
    }

    static SqlReturnTypeInference createSqlReturnTypeInference(
            DataTypeFactory dataTypeFactory,
            FunctionDefinition definition,
            TypeInference typeInference) {
        return new TypeInferenceReturnInference(dataTypeFactory, definition, typeInference);
    }

    static SqlOperandTypeInference createSqlOperandTypeInference(
            DataTypeFactory dataTypeFactory,
            FunctionDefinition definition,
            TypeInference typeInference) {
        return new TypeInferenceOperandInference(dataTypeFactory, definition, typeInference);
    }

    static SqlOperandTypeChecker createSqlOperandTypeChecker(
            DataTypeFactory dataTypeFactory,
            FunctionDefinition definition,
            TypeInference typeInference) {
        return new TypeInferenceOperandChecker(dataTypeFactory, definition, typeInference);
    }

    static SqlFunctionCategory createSqlFunctionCategory(ContextResolvedFunction resolvedFunction) {
        final Optional<FunctionIdentifier> identifier = resolvedFunction.getIdentifier();
        if (!identifier.isPresent() || identifier.get().getIdentifier().isPresent()) {
            return SqlFunctionCategory.USER_DEFINED_FUNCTION;
        }
        return SqlFunctionCategory.SYSTEM;
    }

    private BridgingUtils() {
        // no instantiation
    }
}
