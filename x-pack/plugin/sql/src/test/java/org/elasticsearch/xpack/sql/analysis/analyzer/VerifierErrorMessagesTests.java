/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.analysis.analyzer;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.TestUtils;
import org.elasticsearch.xpack.sql.analysis.AnalysisException;
import org.elasticsearch.xpack.sql.analysis.index.EsIndex;
import org.elasticsearch.xpack.sql.analysis.index.IndexResolution;
import org.elasticsearch.xpack.sql.analysis.index.IndexResolverTests;
import org.elasticsearch.xpack.sql.expression.function.FunctionRegistry;
import org.elasticsearch.xpack.sql.expression.predicate.conditional.Coalesce;
import org.elasticsearch.xpack.sql.expression.predicate.conditional.Greatest;
import org.elasticsearch.xpack.sql.expression.predicate.conditional.IfNull;
import org.elasticsearch.xpack.sql.expression.predicate.conditional.Least;
import org.elasticsearch.xpack.sql.expression.predicate.conditional.NullIf;
import org.elasticsearch.xpack.sql.parser.SqlParser;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.sql.stats.Metrics;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.EsField;
import org.elasticsearch.xpack.sql.type.TypesTests;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

public class VerifierErrorMessagesTests extends ESTestCase {

    private SqlParser parser = new SqlParser();
    private IndexResolution indexResolution = IndexResolution.valid(new EsIndex("test",
        TypesTests.loadMapping("mapping-multi-field-with-nested.json")));

    private String error(String sql) {
        return error(indexResolution, sql);
    }

    private String error(IndexResolution getIndexResult, String sql) {
        Analyzer analyzer = new Analyzer(TestUtils.TEST_CFG, new FunctionRegistry(), getIndexResult, new Verifier(new Metrics()));
        AnalysisException e = expectThrows(AnalysisException.class, () -> analyzer.analyze(parser.createStatement(sql), true));
        assertTrue(e.getMessage().startsWith("Found "));
        String header = "Found 1 problem(s)\nline ";
        return e.getMessage().substring(header.length());
    }

    private LogicalPlan accept(String sql) {
        EsIndex test = getTestEsIndex();
        return accept(IndexResolution.valid(test), sql);
    }

    private EsIndex getTestEsIndex() {
        Map<String, EsField> mapping = TypesTests.loadMapping("mapping-multi-field-with-nested.json");
        return new EsIndex("test", mapping);
    }

    private LogicalPlan accept(IndexResolution resolution, String sql) {
        Analyzer analyzer = new Analyzer(TestUtils.TEST_CFG, new FunctionRegistry(), resolution, new Verifier(new Metrics()));
        return analyzer.analyze(parser.createStatement(sql), true);
    }

    private IndexResolution incompatible() {
        Map<String, EsField> basicMapping = TypesTests.loadMapping("mapping-basic.json", true);
        Map<String, EsField> incompatible = TypesTests.loadMapping("mapping-basic-incompatible.json");

        assertNotEquals(basicMapping, incompatible);
        IndexResolution resolution = IndexResolverTests.merge(new EsIndex("basic", basicMapping),
                new EsIndex("incompatible", incompatible));
        assertTrue(resolution.isValid());
        return resolution;
    }

    private String incompatibleError(String sql) {
        return error(incompatible(), sql);
    }

    private LogicalPlan incompatibleAccept(String sql) {
        return accept(incompatible(), sql);
    }
    
    public void testMissingIndex() {
        assertEquals("1:17: Unknown index [missing]", error(IndexResolution.notFound("missing"), "SELECT foo FROM missing"));
    }

    public void testMissingColumn() {
        assertEquals("1:8: Unknown column [xxx]", error("SELECT xxx FROM test"));
    }

    public void testMissingColumnFilter() {
        assertEquals("1:26: Unknown column [xxx]", error("SELECT * FROM test WHERE xxx > 1"));
    }

    public void testMissingColumnWithWildcard() {
        assertEquals("1:8: Unknown column [xxx]", error("SELECT xxx.* FROM test"));
    }
    
    public void testMisspelledColumnWithWildcard() {
        assertEquals("1:8: Unknown column [tex], did you mean [text]?", error("SELECT tex.* FROM test"));
    }
    
    public void testColumnWithNoSubFields() {
        assertEquals("1:8: Cannot determine columns for [text.*]", error("SELECT text.* FROM test"));
    }

    public void testFieldAliasTypeWithoutHierarchy() {
        Map<String, EsField> mapping = new LinkedHashMap<>();

        mapping.put("field", new EsField("field", DataType.OBJECT,
                singletonMap("alias", new EsField("alias", DataType.KEYWORD, emptyMap(), true)), false));

        IndexResolution resolution = IndexResolution.valid(new EsIndex("test", mapping));

        // check the nested alias is seen
        accept(resolution, "SELECT field.alias FROM test");
        // or its hierarhcy
        accept(resolution, "SELECT field.* FROM test");

        // check typos
        assertEquals("1:8: Unknown column [field.alas], did you mean [field.alias]?", error(resolution, "SELECT field.alas FROM test"));

        // non-existing parents for aliases are not seen by the user
        assertEquals("1:8: Cannot use field [field] type [object] only its subfields", error(resolution, "SELECT field FROM test"));
    }

    public void testMultipleColumnsWithWildcard1() {
        assertEquals("1:14: Unknown column [a]\n" +
                "line 1:17: Unknown column [b]\n" +
                "line 1:22: Unknown column [c]\n" +
                "line 1:25: Unknown column [tex], did you mean [text]?", error("SELECT bool, a, b.*, c, tex.* FROM test"));
    }
    
    public void testMultipleColumnsWithWildcard2() {
        assertEquals("1:8: Unknown column [tex], did you mean [text]?\n" +
                "line 1:21: Unknown column [a]\n" +
                "line 1:24: Unknown column [dat], did you mean [date]?\n" +
                "line 1:31: Unknown column [c]", error("SELECT tex.*, bool, a, dat.*, c FROM test"));
    }
    
    public void testMultipleColumnsWithWildcard3() {
        assertEquals("1:8: Unknown column [ate], did you mean [date]?\n" +
                "line 1:21: Unknown column [keyw], did you mean [keyword]?\n" +
                "line 1:29: Unknown column [da], did you mean [date]?" , error("SELECT ate.*, bool, keyw.*, da FROM test"));
    }

    public void testMisspelledColumn() {
        assertEquals("1:8: Unknown column [txt], did you mean [text]?", error("SELECT txt FROM test"));
    }

    public void testFunctionOverMissingField() {
        assertEquals("1:12: Unknown column [xxx]", error("SELECT ABS(xxx) FROM test"));
    }

    public void testFunctionOverMissingFieldInFilter() {
        assertEquals("1:30: Unknown column [xxx]", error("SELECT * FROM test WHERE ABS(xxx) > 1"));
    }

    public void testMissingFunction() {
        assertEquals("1:8: Unknown function [ZAZ]", error("SELECT ZAZ(bool) FROM test"));
    }

    public void testMisspelledFunction() {
        assertEquals("1:8: Unknown function [COONT], did you mean any of [COUNT, COT, CONCAT]?", error("SELECT COONT(bool) FROM test"));
    }

    public void testMissingColumnInGroupBy() {
        assertEquals("1:41: Unknown column [xxx]", error("SELECT * FROM test GROUP BY DAY_OF_YEAR(xxx)"));
    }

    public void testFilterOnUnknownColumn() {
        assertEquals("1:26: Unknown column [xxx]", error("SELECT * FROM test WHERE xxx = 1"));
    }

    public void testMissingColumnInOrderBy() {
        assertEquals("1:29: Unknown column [xxx]", error("SELECT * FROM test ORDER BY xxx"));
    }

    public void testMissingColumnFunctionInOrderBy() {
        assertEquals("1:41: Unknown column [xxx]", error("SELECT * FROM test ORDER BY DAY_oF_YEAR(xxx)"));
    }

    public void testMissingExtract() {
        assertEquals("1:8: Unknown datetime field [ZAZ]", error("SELECT EXTRACT(ZAZ FROM date) FROM test"));
    }

    public void testMissingExtractSimilar() {
        assertEquals("1:8: Unknown datetime field [DAP], did you mean [DAY]?", error("SELECT EXTRACT(DAP FROM date) FROM test"));
    }

    public void testMissingExtractSimilarMany() {
        assertEquals("1:8: Unknown datetime field [DOP], did you mean any of [DOM, DOW, DOY, IDOW]?",
            error("SELECT EXTRACT(DOP FROM date) FROM test"));
    }

    public void testExtractNonDateTime() {
        assertEquals("1:8: Invalid datetime field [ABS]. Use any datetime function.", error("SELECT EXTRACT(ABS FROM date) FROM test"));
    }

    public void testSubtractFromInterval() {
        assertEquals("1:8: Cannot subtract a datetime[CAST('2000-01-01' AS DATETIME)] " +
                "from an interval[INTERVAL 1 MONTH]; do you mean the reverse?",
            error("SELECT INTERVAL 1 MONTH - CAST('2000-01-01' AS DATETIME)"));
    }

    public void testMultipleColumns() {
        assertEquals("1:43: Unknown column [xxx]\nline 1:8: Unknown column [xxx]",
                error("SELECT xxx FROM test GROUP BY DAY_oF_YEAR(xxx)"));
    }

    // GROUP BY
    public void testGroupBySelectWithAlias() {
        assertNotNull(accept("SELECT int AS i FROM test GROUP BY i"));
    }

    public void testGroupBySelectWithAliasOrderOnActualField() {
        assertNotNull(accept("SELECT int AS i FROM test GROUP BY i ORDER BY int"));
    }

    public void testGroupBySelectNonGrouped() {
        assertEquals("1:8: Cannot use non-grouped column [text], expected [int]",
                error("SELECT text, int FROM test GROUP BY int"));
    }

    public void testGroupByFunctionSelectFieldFromGroupByFunction() {
        assertEquals("1:8: Cannot use non-grouped column [int], expected [ABS(int)]",
                error("SELECT int FROM test GROUP BY ABS(int)"));
    }

    public void testGroupByOrderByNonGrouped() {
        assertEquals("1:50: Cannot order by non-grouped column [bool], expected [text]",
                error("SELECT MAX(int) FROM test GROUP BY text ORDER BY bool"));
    }

    public void testGroupByOrderByNonGrouped_WithHaving() {
        assertEquals("1:71: Cannot order by non-grouped column [bool], expected [text]",
            error("SELECT MAX(int) FROM test GROUP BY text HAVING MAX(int) > 10 ORDER BY bool"));
    }

    public void testGroupByOrderByAliasedInSelectAllowed() {
        LogicalPlan lp = accept("SELECT text t FROM test GROUP BY text ORDER BY t");
        assertNotNull(lp);
    }

    public void testGroupByOrderByScalarOverNonGrouped() {
        assertEquals("1:50: Cannot order by non-grouped column [YEAR(date)], expected [text] or an aggregate function",
                error("SELECT MAX(int) FROM test GROUP BY text ORDER BY YEAR(date)"));
    }

    public void testGroupByOrderByFieldFromGroupByFunction() {
        assertEquals("1:54: Cannot use non-grouped column [int], expected [ABS(int)]",
                error("SELECT ABS(int) FROM test GROUP BY ABS(int) ORDER BY int"));
    }

    public void testGroupByOrderByScalarOverNonGrouped_WithHaving() {
        assertEquals("1:71: Cannot order by non-grouped column [YEAR(date)], expected [text] or an aggregate function",
            error("SELECT MAX(int) FROM test GROUP BY text HAVING MAX(int) > 10 ORDER BY YEAR(date)"));
    }

    public void testGroupByHavingNonGrouped() {
        assertEquals("1:48: Cannot use HAVING filter on non-aggregate [int]; use WHERE instead",
                error("SELECT AVG(int) FROM test GROUP BY text HAVING int > 10"));
    }

    public void testGroupByAggregate() {
        assertEquals("1:36: Cannot use an aggregate [AVG] for grouping",
                error("SELECT AVG(int) FROM test GROUP BY AVG(int)"));
    }

    public void testStarOnNested() {
        assertNotNull(accept("SELECT dep.* FROM test"));
    }

    public void testGroupByOnNested() {
        assertEquals("1:38: Grouping isn't (yet) compatible with nested fields [dep.dep_id]",
                error("SELECT dep.dep_id FROM test GROUP BY dep.dep_id"));
    }

    public void testHavingOnNested() {
        assertEquals("1:51: HAVING isn't (yet) compatible with nested fields [dep.start_date]",
                error("SELECT int FROM test GROUP BY int HAVING AVG(YEAR(dep.start_date)) > 1980"));
    }

    public void testGroupByScalarFunctionWithAggOnTarget() {
        assertEquals("1:31: Cannot use an aggregate [AVG] for grouping",
                error("SELECT int FROM test GROUP BY AVG(int) + 2"));
    }

    public void testUnsupportedType() {
        assertEquals("1:8: Cannot use field [unsupported] type [ip_range] as is unsupported",
                error("SELECT unsupported FROM test"));
    }

    public void testUnsupportedStarExpansion() {
        assertEquals("1:8: Cannot use field [unsupported] type [ip_range] as is unsupported",
                error("SELECT unsupported.* FROM test"));
    }

    public void testUnsupportedTypeInFilter() {
        assertEquals("1:26: Cannot use field [unsupported] type [ip_range] as is unsupported",
                error("SELECT * FROM test WHERE unsupported > 1"));
    }

    public void testUnsupportedTypeInFunction() {
        assertEquals("1:12: Cannot use field [unsupported] type [ip_range] as is unsupported",
                error("SELECT ABS(unsupported) FROM test"));
    }

    public void testUnsupportedTypeInOrder() {
        assertEquals("1:29: Cannot use field [unsupported] type [ip_range] as is unsupported",
                error("SELECT * FROM test ORDER BY unsupported"));
    }

    public void testGroupByOrderByAggregate() {
        accept("SELECT AVG(int) a FROM test GROUP BY bool ORDER BY a");
    }

    public void testGroupByOrderByAggs() {
        accept("SELECT int FROM test GROUP BY int ORDER BY COUNT(*)");
    }

    public void testGroupByOrderByAggAndGroupedColumn() {
        accept("SELECT int FROM test GROUP BY int ORDER BY int, MAX(int)");
    }

    public void testGroupByOrderByNonAggAndNonGroupedColumn() {
        assertEquals("1:44: Cannot order by non-grouped column [bool], expected [int]",
                error("SELECT int FROM test GROUP BY int ORDER BY bool"));
    }

    public void testGroupByOrderByScore() {
        assertEquals("1:44: Cannot order by non-grouped column [SCORE()], expected [int] or an aggregate function",
                error("SELECT int FROM test GROUP BY int ORDER BY SCORE()"));
    }

    public void testHavingOnColumn() {
        assertEquals("1:42: Cannot use HAVING filter on non-aggregate [int]; use WHERE instead",
                error("SELECT int FROM test GROUP BY int HAVING int > 2"));
    }

    public void testHavingOnScalar() {
        assertEquals("1:42: Cannot use HAVING filter on non-aggregate [int]; use WHERE instead",
                error("SELECT int FROM test GROUP BY int HAVING 2 < ABS(int)"));
    }

    public void testInWithDifferentDataTypes_SelectClause() {
        assertEquals("1:17: expected data type [integer], value provided is of type [keyword]",
            error("SELECT 1 IN (2, '3', 4)"));
    }

    public void testInNestedWithDifferentDataTypes_SelectClause() {
        assertEquals("1:27: expected data type [integer], value provided is of type [keyword]",
            error("SELECT 1 = 1  OR 1 IN (2, '3', 4)"));
    }

    public void testInWithDifferentDataTypesFromLeftValue_SelectClause() {
        assertEquals("1:14: expected data type [integer], value provided is of type [keyword]",
            error("SELECT 1 IN ('foo', 'bar')"));
    }

    public void testInNestedWithDifferentDataTypesFromLeftValue_SelectClause() {
        assertEquals("1:29: expected data type [keyword], value provided is of type [integer]",
            error("SELECT 1 = 1  OR  'foo' IN (2, 3)"));
    }

    public void testInWithDifferentDataTypes_WhereClause() {
        assertEquals("1:49: expected data type [text], value provided is of type [integer]",
            error("SELECT * FROM test WHERE text IN ('foo', 'bar', 4)"));
    }

    public void testInNestedWithDifferentDataTypes_WhereClause() {
        assertEquals("1:60: expected data type [text], value provided is of type [integer]",
            error("SELECT * FROM test WHERE int = 1 OR text IN ('foo', 'bar', 2)"));
    }

    public void testInWithDifferentDataTypesFromLeftValue_WhereClause() {
        assertEquals("1:35: expected data type [text], value provided is of type [integer]",
            error("SELECT * FROM test WHERE text IN (1, 2)"));
    }

    public void testInNestedWithDifferentDataTypesFromLeftValue_WhereClause() {
        assertEquals("1:46: expected data type [text], value provided is of type [integer]",
            error("SELECT * FROM test WHERE int = 1 OR text IN (1, 2)"));
    }

    public void testNotSupportedAggregateOnDate() {
        assertEquals("1:8: [AVG(date)] argument must be [numeric], found value [date] type [datetime]",
            error("SELECT AVG(date) FROM test"));
    }

    public void testInvalidTypeForStringFunction_WithOneArg() {
        assertEquals("1:8: [LENGTH] argument must be [string], found value [1] type [integer]",
            error("SELECT LENGTH(1)"));
    }

    public void testInvalidTypeForNumericFunction_WithOneArg() {
        assertEquals("1:8: [COS] argument must be [numeric], found value ['foo'] type [keyword]",
            error("SELECT COS('foo')"));
    }

    public void testInvalidTypeForBooleanFunction_WithOneArg() {
        assertEquals("1:8: [NOT 'foo'] argument must be [boolean], found value ['foo'] type [keyword]",
            error("SELECT NOT 'foo'"));
    }

    public void testInvalidTypeForStringFunction_WithTwoArgs() {
        assertEquals("1:8: [CONCAT(1, 'bar')] first argument must be [string], found value [1] type [integer]",
            error("SELECT CONCAT(1, 'bar')"));
        assertEquals("1:8: [CONCAT('foo', 2)] second argument must be [string], found value [2] type [integer]",
            error("SELECT CONCAT('foo', 2)"));
    }

    public void testInvalidTypeForNumericFunction_WithTwoArgs() {
        assertEquals("1:8: [TRUNCATE('foo', 2)] first argument must be [numeric], found value ['foo'] type [keyword]",
            error("SELECT TRUNCATE('foo', 2)"));
        assertEquals("1:8: [TRUNCATE(1.2, 'bar')] second argument must be [numeric], found value ['bar'] type [keyword]",
            error("SELECT TRUNCATE(1.2, 'bar')"));
    }

    public void testInvalidTypeForBooleanFuntion_WithTwoArgs() {
        assertEquals("1:8: [1 OR true] first argument must be [boolean], found value [1] type [integer]",
            error("SELECT 1 OR true"));
        assertEquals("1:8: [true OR 2] second argument must be [boolean], found value [2] type [integer]",
            error("SELECT true OR 2"));
    }

    public void testInvalidTypeForFunction_WithThreeArgs() {
        assertEquals("1:8: [REPLACE(1, 'foo', 'bar')] first argument must be [string], found value [1] type [integer]",
            error("SELECT REPLACE(1, 'foo', 'bar')"));
        assertEquals("1:8: [REPLACE('text', 2, 'bar')] second argument must be [string], found value [2] type [integer]",
            error("SELECT REPLACE('text', 2, 'bar')"));
        assertEquals("1:8: [REPLACE('text', 'foo', 3)] third argument must be [string], found value [3] type [integer]",
            error("SELECT REPLACE('text', 'foo', 3)"));
    }

    public void testInvalidTypeForFunction_WithFourArgs() {
        assertEquals("1:8: [INSERT(1, 1, 2, 'new')] first argument must be [string], found value [1] type [integer]",
            error("SELECT INSERT(1, 1, 2, 'new')"));
        assertEquals("1:8: [INSERT('text', 'foo', 2, 'new')] second argument must be [numeric], found value ['foo'] type [keyword]",
            error("SELECT INSERT('text', 'foo', 2, 'new')"));
        assertEquals("1:8: [INSERT('text', 1, 'bar', 'new')] third argument must be [numeric], found value ['bar'] type [keyword]",
            error("SELECT INSERT('text', 1, 'bar', 'new')"));
        assertEquals("1:8: [INSERT('text', 1, 2, 3)] fourth argument must be [string], found value [3] type [integer]",
            error("SELECT INSERT('text', 1, 2, 3)"));
    }
    
    public void testAllowCorrectFieldsInIncompatibleMappings() {
        assertNotNull(incompatibleAccept("SELECT languages FROM \"*\""));
    }

    public void testWildcardInIncompatibleMappings() {
        assertNotNull(incompatibleAccept("SELECT * FROM \"*\""));
    }

    public void testMismatchedFieldInIncompatibleMappings() {
        assertEquals(
                "1:8: Cannot use field [emp_no] due to ambiguities being mapped as [2] incompatible types: "
                        + "[integer] in [basic], [long] in [incompatible]",
                incompatibleError("SELECT emp_no FROM \"*\""));
    }

    public void testMismatchedFieldStarInIncompatibleMappings() {
        assertEquals(
                "1:8: Cannot use field [emp_no] due to ambiguities being mapped as [2] incompatible types: "
                        + "[integer] in [basic], [long] in [incompatible]",
                incompatibleError("SELECT emp_no.* FROM \"*\""));
    }

    public void testMismatchedFieldFilterInIncompatibleMappings() {
        assertEquals(
                "1:33: Cannot use field [emp_no] due to ambiguities being mapped as [2] incompatible types: "
                        + "[integer] in [basic], [long] in [incompatible]",
                incompatibleError("SELECT languages FROM \"*\" WHERE emp_no > 1"));
    }

    public void testMismatchedFieldScalarInIncompatibleMappings() {
        assertEquals(
                "1:45: Cannot use field [emp_no] due to ambiguities being mapped as [2] incompatible types: "
                        + "[integer] in [basic], [long] in [incompatible]",
                incompatibleError("SELECT languages FROM \"*\" ORDER BY SIGN(ABS(emp_no))"));
    }

    public void testConditionalWithDifferentDataTypes_SelectClause() {
        @SuppressWarnings("unchecked")
        String function = randomFrom(IfNull.class, NullIf.class).getSimpleName();
        assertEquals("1:" + (22 + function.length()) +
                ": expected data type [integer], value provided is of type [keyword]",
            error("SELECT 1 = 1  OR " + function + "(3, '4') > 1"));

        @SuppressWarnings("unchecked")
        String arbirtraryArgsfunction = randomFrom(Coalesce.class, Greatest.class, Least.class).getSimpleName();
        assertEquals("1:" + (34 + arbirtraryArgsfunction.length()) +
                ": expected data type [integer], value provided is of type [keyword]",
            error("SELECT 1 = 1  OR " + arbirtraryArgsfunction + "(null, null, 3, '4') > 1"));
    }

    public void testConditionalWithDifferentDataTypes_WhereClause() {
        @SuppressWarnings("unchecked")
        String function = randomFrom(IfNull.class, NullIf.class).getSimpleName();
        assertEquals("1:" + (34 + function.length()) +
                ": expected data type [keyword], value provided is of type [integer]",
            error("SELECT * FROM test WHERE " + function + "('foo', 4) > 1"));

        @SuppressWarnings("unchecked")
        String arbirtraryArgsfunction = randomFrom(Coalesce.class, Greatest.class, Least.class).getSimpleName();
        assertEquals("1:" + (46 + arbirtraryArgsfunction.length()) +
                ": expected data type [keyword], value provided is of type [integer]",
            error("SELECT * FROM test WHERE " + arbirtraryArgsfunction + "(null, null, 'foo', 4) > 1"));
    }

    public void testAggsInWhere() {
        assertEquals("1:33: Cannot use WHERE filtering on aggregate function [MAX(int)], use HAVING instead",
                error("SELECT MAX(int) FROM test WHERE MAX(int) > 10 GROUP BY bool"));
    }

    public void testHistogramInFilter() {
        assertEquals("1:63: Cannot filter on grouping function [HISTOGRAM(date, INTERVAL 1 MONTH)], use its argument instead",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) AS h FROM test WHERE "
                        + "HISTOGRAM(date, INTERVAL 1 MONTH) > CAST('2000-01-01' AS DATETIME) GROUP BY h"));
    }

    // related https://github.com/elastic/elasticsearch/issues/36853
    public void testHistogramInHaving() {
        assertEquals("1:75: Cannot filter on grouping function [h], use its argument instead",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) AS h FROM test GROUP BY h HAVING "
                        + "h > CAST('2000-01-01' AS DATETIME)"));
    }

    public void testGroupByScalarOnTopOfGrouping() {
        assertEquals(
                "1:14: Cannot combine [HISTOGRAM(date, INTERVAL 1 MONTH)] grouping function inside "
                        + "GROUP BY, found [MONTH(HISTOGRAM(date, INTERVAL 1 MONTH))]; consider moving the expression inside the histogram",
                error("SELECT MONTH(HISTOGRAM(date, INTERVAL 1 MONTH)) AS h FROM test GROUP BY h"));
    }

    public void testAggsInHistogram() {
        assertEquals("1:47: Cannot use an aggregate [MAX] for grouping",
                error("SELECT MAX(date) FROM test GROUP BY HISTOGRAM(MAX(int), 1)"));
    }
    
    public void testHistogramNotInGrouping() {
        assertEquals("1:8: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) AS h FROM test"));
    }
    
    public void testHistogramNotInGroupingWithCount() {
        assertEquals("1:8: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) AS h, COUNT(*) FROM test"));
    }
    
    public void testHistogramNotInGroupingWithMaxFirst() {
        assertEquals("1:19: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT MAX(date), HISTOGRAM(date, INTERVAL 1 MONTH) AS h FROM test"));
    }
    
    public void testHistogramWithoutAliasNotInGrouping() {
        assertEquals("1:8: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) FROM test"));
    }
    
    public void testTwoHistogramsNotInGrouping() {
        assertEquals("1:48: [HISTOGRAM(date, INTERVAL 1 DAY)] needs to be part of the grouping",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) AS h, HISTOGRAM(date, INTERVAL 1 DAY) FROM test GROUP BY h"));
    }
    
    public void testHistogramNotInGrouping_WithGroupByField() {
        assertEquals("1:8: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT HISTOGRAM(date, INTERVAL 1 MONTH) FROM test GROUP BY date"));
    }
    
    public void testScalarOfHistogramNotInGrouping() {
        assertEquals("1:14: [HISTOGRAM(date, INTERVAL 1 MONTH)] needs to be part of the grouping",
                error("SELECT MONTH(HISTOGRAM(date, INTERVAL 1 MONTH)) FROM test"));
    }

    public void testErrorMessageForPercentileWithSecondArgBasedOnAField() {
        assertEquals("1:8: Second argument of PERCENTILE must be a constant, received [ABS(int)]",
            error("SELECT PERCENTILE(int, ABS(int)) FROM test"));
    }

    public void testErrorMessageForPercentileRankWithSecondArgBasedOnAField() {
        assertEquals("1:8: Second argument of PERCENTILE_RANK must be a constant, received [ABS(int)]",
            error("SELECT PERCENTILE_RANK(int, ABS(int)) FROM test"));
    }

    public void testTopHitsFirstArgConstant() {
        assertEquals("1:8: First argument of [FIRST] must be a table column, found constant ['foo']",
            error("SELECT FIRST('foo', int) FROM test"));
    }

    public void testTopHitsSecondArgConstant() {
        assertEquals("1:8: Second argument of [LAST] must be a table column, found constant [10]",
            error("SELECT LAST(int, 10) FROM test"));
    }

    public void testTopHitsFirstArgTextWithNoKeyword() {
        assertEquals("1:8: [FIRST] cannot operate on first argument field of data type [text]",
            error("SELECT FIRST(text) FROM test"));
    }

    public void testTopHitsSecondArgTextWithNoKeyword() {
        assertEquals("1:8: [LAST] cannot operate on second argument field of data type [text]",
            error("SELECT LAST(keyword, text) FROM test"));
    }

    public void testTopHitsGroupByHavingUnsupported() {
        assertEquals("1:50: HAVING filter is unsupported for function [FIRST(int)]",
            error("SELECT FIRST(int) FROM test GROUP BY text HAVING FIRST(int) > 10"));
    }

    public void testMinOnKeywordGroupByHavingUnsupported() {
        assertEquals("1:52: HAVING filter is unsupported for function [MIN(keyword)]",
            error("SELECT MIN(keyword) FROM test GROUP BY text HAVING MIN(keyword) > 10"));
    }

    public void testMaxOnKeywordGroupByHavingUnsupported() {
        assertEquals("1:52: HAVING filter is unsupported for function [MAX(keyword)]",
            error("SELECT MAX(keyword) FROM test GROUP BY text HAVING MAX(keyword) > 10"));
    }
}

