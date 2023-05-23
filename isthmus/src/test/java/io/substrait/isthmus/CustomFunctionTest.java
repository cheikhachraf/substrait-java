package io.substrait.isthmus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.substrait.dsl.SubstraitBuilder;
import io.substrait.extension.SimpleExtension;
import io.substrait.isthmus.expression.AggregateFunctionConverter;
import io.substrait.isthmus.expression.FunctionMappings;
import io.substrait.isthmus.expression.ScalarFunctionConverter;
import io.substrait.isthmus.expression.WindowFunctionConverter;
import io.substrait.relation.Rel;
import io.substrait.type.TypeCreator;
import java.io.IOException;
import java.util.List;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Test;

/** Verify that custom functions can convert from Substrait to Calcite and back. */
public class CustomFunctionTest extends PlanTestBase {
  static final TypeCreator R = TypeCreator.of(false);

  // Define custom functions in a "functions_custom.yaml" extension
  static final String NAMESPACE = "/functions_custom";
  static final String FUNCTIONS_CUSTOM;

  static {
    try {
      FUNCTIONS_CUSTOM = asString("extensions/functions_custom.yaml");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // Load custom extension into an ExtensionCollection
  static final SimpleExtension.ExtensionCollection extensionCollection =
      SimpleExtension.load("/functions_custom", FUNCTIONS_CUSTOM);

  final SubstraitBuilder b = new SubstraitBuilder(extensionCollection);

  // Define additional mapping signatures for the custom scalar functions
  final List<FunctionMappings.Sig> additionalScalarSignatures =
      List.of(FunctionMappings.s(customScalarFn));

  static final SqlFunction customScalarFn =
      new SqlFunction(
          "custom_scalar",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.VARCHAR),
          null,
          null,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  // Define additional mapping signatures for the custom aggregate functions
  final List<FunctionMappings.Sig> additionalAggregateSignatures =
      List.of(FunctionMappings.s(customAggregateFn));

  static final SqlAggFunction customAggregateFn =
      new SqlAggFunction(
          "custom_aggregate",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.BIGINT),
          null,
          null,
          SqlFunctionCategory.USER_DEFINED_FUNCTION) {};

  // Create Function Converters that can handle the custom functions
  ScalarFunctionConverter scalarFunctionConverter =
      new ScalarFunctionConverter(
          extensionCollection.scalarFunctions(), additionalScalarSignatures, typeFactory);
  AggregateFunctionConverter aggregateFunctionConverter =
      new AggregateFunctionConverter(
          extensionCollection.aggregateFunctions(), additionalAggregateSignatures, typeFactory);
  WindowFunctionConverter windowFunctionConverter =
      new WindowFunctionConverter(
          extensionCollection.windowFunctions(), typeFactory, aggregateFunctionConverter);

  // Create a SubstraitToCalcite converter that has access to the custom Function Converters
  class CustomSubstraitToCalcite extends SubstraitToCalcite {

    public CustomSubstraitToCalcite(
        SimpleExtension.ExtensionCollection extensions, RelDataTypeFactory typeFactory) {
      super(extensions, typeFactory);
    }

    @Override
    protected SubstraitRelNodeConverter createSubstraitRelNodeConverter(RelBuilder relBuilder) {
      return new SubstraitRelNodeConverter(
          typeFactory, relBuilder, scalarFunctionConverter, aggregateFunctionConverter);
    }
  }

  final SubstraitToCalcite substraitToCalcite =
      new CustomSubstraitToCalcite(extensionCollection, typeFactory);

  // Create a SubstraitRelVisitor that uses the custom Function Converters
  final SubstraitRelVisitor calciteToSubstrait =
      new SubstraitRelVisitor(
          typeFactory,
          scalarFunctionConverter,
          aggregateFunctionConverter,
          windowFunctionConverter,
          ImmutableFeatureBoard.builder().build());

  @Test
  void customScalarFunctionRoundtrip() {
    // CREATE TABLE example(a TEXT)
    // SELECT custom_scalar(a) FROM example
    Rel rel =
        b.project(
            input ->
                List.of(
                    b.scalarFn(
                        NAMESPACE, "custom_scalar:str", R.STRING, b.fieldReference(input, 0))),
            b.remap(1),
            b.namedScan(List.of("example"), List.of("a"), List.of(R.STRING)));

    RelNode calciteRel = substraitToCalcite.convert(rel);
    var relReturned = calciteToSubstrait.apply(calciteRel);
    assertEquals(rel, relReturned);
  }

  @Test
  void customAggregateFunctionRoundtrip() {
    // CREATE TABLE example (a BIGINT)
    // SELECT custom_aggregate(a) FROM example GROUP BY a
    Rel rel =
        b.aggregate(
            input -> b.grouping(input, 0),
            input ->
                List.of(
                    b.aggregateFn(
                        NAMESPACE, "custom_aggregate:i64", R.I64, b.fieldReference(input, 0))),
            b.namedScan(List.of("example"), List.of("a"), List.of(R.I64)));

    RelNode calciteRel = substraitToCalcite.convert(rel);
    var relReturned = calciteToSubstrait.apply(calciteRel);
    assertEquals(rel, relReturned);
  }
}