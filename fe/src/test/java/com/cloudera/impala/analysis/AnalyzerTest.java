// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.TimestampArithmeticExpr.TimeUnit;
import com.cloudera.impala.catalog.Catalog;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.catalog.TestSchemaUtils;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.TExpr;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class AnalyzerTest {
  private final static Logger LOG = LoggerFactory.getLogger(AnalyzerTest.class);
  private static Catalog catalog;

  private Analyzer analyzer;

  // maps from type to string that will result in literal of that type
  private static Map<PrimitiveType, String> typeToLiteralValue =
      new HashMap<PrimitiveType, String>();
  static {
    typeToLiteralValue.put(PrimitiveType.BOOLEAN, "true");
    typeToLiteralValue.put(PrimitiveType.TINYINT, "1");
    typeToLiteralValue.put(PrimitiveType.SMALLINT, (Byte.MAX_VALUE + 1) + "");
    typeToLiteralValue.put(PrimitiveType.INT, (Short.MAX_VALUE + 1) + "");
    typeToLiteralValue.put(PrimitiveType.BIGINT, ((long) Integer.MAX_VALUE + 1) + "");
    typeToLiteralValue.put(PrimitiveType.FLOAT, "1.0");
    typeToLiteralValue.put(PrimitiveType.DOUBLE, (Float.MAX_VALUE + 1) + "");
    typeToLiteralValue.put(PrimitiveType.DATE, "'2012-12-21'");
    typeToLiteralValue.put(PrimitiveType.DATETIME, "'2012-12-21 00:00:00'");
    typeToLiteralValue.put(PrimitiveType.TIMESTAMP, "'2012-12-21 00:00:00.000'");
  }

  @BeforeClass
  public static void setUp() throws Exception {
    catalog = new Catalog();
  }

  @AfterClass
  public static void cleanUp() {
    catalog.close();
  }

  /**
   * Check whether SelectStmt components can be converted to thrift.
   *
   * @param node
   * @return
   */
  private void CheckSelectToThrift(SelectStmt node) {
    // convert select list exprs and where clause to thrift
    List<Expr> selectListExprs = node.getResultExprs();
    List<TExpr> thriftExprs = Expr.treesToThrift(selectListExprs);
    LOG.info("select list:\n");
    for (TExpr expr: thriftExprs) {
      LOG.info(expr.toString() + "\n");
    }
    for (Expr expr: selectListExprs) {
      checkBinaryExprs(expr);
    }
    if (node.getWhereClause() != null) {
      TExpr thriftWhere = node.getWhereClause().treeToThrift();
      LOG.info("WHERE pred: " + thriftWhere.toString() + "\n");
      checkBinaryExprs(node.getWhereClause());
    }
    AggregateInfo aggInfo = node.getAggInfo();
    if (aggInfo != null) {
      if (aggInfo.getGroupingExprs() != null) {
        LOG.info("grouping exprs:\n");
        for (Expr expr: aggInfo.getGroupingExprs()) {
          LOG.info(expr.treeToThrift().toString() + "\n");
          checkBinaryExprs(expr);
        }
      }
      LOG.info("aggregate exprs:\n");
      for (Expr expr: aggInfo.getAggregateExprs()) {
        LOG.info(expr.treeToThrift().toString() + "\n");
        checkBinaryExprs(expr);
      }
      if (node.getHavingPred() != null) {
        TExpr thriftHaving = node.getHavingPred().treeToThrift();
        LOG.info("HAVING pred: " + thriftHaving.toString() + "\n");
        checkBinaryExprs(node.getHavingPred());
      }
    }
  }


  /**
   * Parse 'stmt' and return the root ParseNode.
   */
  public ParseNode ParsesOk(String stmt) {
    SqlScanner input = new SqlScanner(new StringReader(stmt));
    SqlParser parser = new SqlParser(input);
    ParseNode node = null;
    try {
      node = (ParseNode) parser.parse().value;
    } catch (Exception e) {
      System.err.println(e.toString());
      fail("\nParser error:\n" + parser.getErrorMsg(stmt));
    }
    assertNotNull(node);
    return node;
  }

  /**
   * Analyze 'stmt', expecting it to pass. Asserts in case of analysis error.
   *
   * @param stmt
   * @return
   */
  public ParseNode AnalyzesOk(String stmt) {
    LOG.info("analyzing " + stmt);
    ParseNode node = ParsesOk(stmt);
    assertNotNull(node);
    analyzer = new Analyzer(catalog);
    try {
      node.analyze(analyzer);
    } catch (AnalysisException e) {
      fail("Analysis error:\n" + e.toString());
    } catch (InternalException e) {
      fail("Internal exception:\n" + e.toString());
    }
    if (node instanceof SelectStmt) {
      CheckSelectToThrift((SelectStmt) node);
    } else if (node instanceof InsertStmt) {
      InsertStmt insertStmt = (InsertStmt) node;
      if (insertStmt.getQueryStmt() instanceof SelectStmt) {
        CheckSelectToThrift((SelectStmt) insertStmt.getQueryStmt());
      }
    }
    return node;
  }

  /**
   * Asserts if stmt passes analysis or the error string doesn't match and it
   * is non-null.
   *
   * @param stmt
   * @param expectedErrorString
   */
  public void AnalysisError(String stmt, String expectedErrorString) {
    LOG.info("analyzing " + stmt);
    SqlScanner input = new SqlScanner(new StringReader(stmt));
    SqlParser parser = new SqlParser(input);
    ParseNode node = null;
    try {
      node = (ParseNode) parser.parse().value;
    } catch (Exception e) {
      System.err.println(e.toString());
      fail("\nParser error:\n" + parser.getErrorMsg(stmt));
    }
    assertNotNull(node);
    analyzer = new Analyzer(catalog);
    try {
      node.analyze(analyzer);
    } catch (AnalysisException e) {
      if (expectedErrorString != null) {
        String errorString = e.getMessage();
        Assert.assertTrue(
            "got error:\n" + errorString + "\nexpected:\n" + expectedErrorString,
            errorString.startsWith(expectedErrorString));
      }
      return;
    } catch (InternalException e) {
      fail("Internal exception:\n" + e.toString());
    }

    fail("Stmt didn't result in analysis error: " + stmt);
  }

  /**
   * Asserts if stmt passes analysis.
   *
   * @param stmt
   */
  public void AnalysisError(String stmt) {
    AnalysisError(stmt, null);
  }

  /**
   * Makes sure that operands to binary exprs having same type.
   */
  private void checkBinaryExprs(Expr expr) {
    if (expr instanceof BinaryPredicate
        || (expr instanceof ArithmeticExpr
        && ((ArithmeticExpr) expr).getOp() != ArithmeticExpr.Operator.BITNOT)) {
      Assert.assertEquals(expr.getChildren().size(), 2);
      // The types must be equal or one of them is NULL_TYPE.
      Assert.assertTrue(expr.getChild(0).getType() == expr.getChild(1).getType()
          || expr.getChild(0).getType().isNull() || expr.getChild(1).getType().isNull());
    }
    for (Expr child: expr.getChildren()) {
      checkBinaryExprs(child);
    }
  }

  @Test
  public void TestMemLayout() throws AnalysisException {
    TestSelectStar();
    TestNonNullable();
    TestMixedNullable();
  }

  private void TestSelectStar() throws AnalysisException {
    AnalyzesOk("select * from functional.AllTypes");
    DescriptorTable descTbl = analyzer.getDescTbl();
    for (SlotDescriptor slotD : descTbl.getTupleDesc(new TupleId(0)).getSlots()) {
      slotD.setIsMaterialized(true);
    }
    descTbl.computeMemLayout();
    checkLayoutParams("functional.alltypes.bool_col", 1, 2, 0, 0);
    checkLayoutParams("functional.alltypes.tinyint_col", 1, 3, 0, 1);
    checkLayoutParams("functional.alltypes.smallint_col", 2, 4, 0, 2);
    checkLayoutParams("functional.alltypes.id", 4, 8, 0, 3);
    checkLayoutParams("functional.alltypes.int_col", 4, 12, 0, 4);
    checkLayoutParams("functional.alltypes.float_col", 4, 16, 0, 5);
    checkLayoutParams("functional.alltypes.year", 4, 20, 0, 6);
    checkLayoutParams("functional.alltypes.month", 4, 24, 0, 7);
    checkLayoutParams("functional.alltypes.bigint_col", 8, 32, 1, 0);
    checkLayoutParams("functional.alltypes.double_col", 8, 40, 1, 1);
    int strSlotSize = PrimitiveType.STRING.getSlotSize();
    checkLayoutParams("functional.alltypes.date_string_col", strSlotSize, 48, 1, 2);
    checkLayoutParams("functional.alltypes.string_col", strSlotSize, 48 + strSlotSize, 1, 3);
  }

  private void TestNonNullable() throws AnalysisException {
    // both slots are non-nullable bigints. The layout should look like:
    // (byte range : data)
    // 0 - 7: count(int_col)
    // 8 - 15: count(*)
    AnalyzesOk("select count(int_col), count(*) from functional.AllTypes");
    DescriptorTable descTbl = analyzer.getDescTbl();
    com.cloudera.impala.analysis.TupleDescriptor aggDesc =
        descTbl.getTupleDesc(new TupleId(1));
    for (SlotDescriptor slotD: aggDesc.getSlots()) {
      slotD.setIsMaterialized(true);
    }
    descTbl.computeMemLayout();
    Assert.assertEquals(aggDesc.getByteSize(), 16);
    checkLayoutParams(aggDesc.getSlots().get(0), 8, 0, 0, -1);
    checkLayoutParams(aggDesc.getSlots().get(1), 8, 8, 0, -1);
  }

  private void TestMixedNullable() throws AnalysisException {
    // one slot is nullable, one is not. The layout should look like:
    // (byte range : data)
    // 0 : 1 nullable-byte (only 1 bit used)
    // 1 - 7: padded bytes
    // 8 - 15: sum(int_col)
    // 16 - 23: count(*)
    AnalyzesOk("select sum(int_col), count(*) from functional.AllTypes");
    DescriptorTable descTbl = analyzer.getDescTbl();
    com.cloudera.impala.analysis.TupleDescriptor aggDesc =
        descTbl.getTupleDesc(new TupleId(1));
    for (SlotDescriptor slotD: aggDesc.getSlots()) {
      slotD.setIsMaterialized(true);
    }
    descTbl.computeMemLayout();
    Assert.assertEquals(aggDesc.getByteSize(), 24);
    checkLayoutParams(aggDesc.getSlots().get(0), 8, 8, 0, 0);
    checkLayoutParams(aggDesc.getSlots().get(1), 8, 16, 0, -1);
  }

  private void checkLayoutParams(SlotDescriptor d, int byteSize, int byteOffset,
      int nullIndicatorByte, int nullIndicatorBit) {
    Assert.assertEquals(byteSize, d.getByteSize());
    Assert.assertEquals(byteOffset, d.getByteOffset());
    Assert.assertEquals(nullIndicatorByte, d.getNullIndicatorByte());
    Assert.assertEquals(nullIndicatorBit, d.getNullIndicatorBit());
  }

  private void checkLayoutParams(String colAlias, int byteSize, int byteOffset,
      int nullIndicatorByte, int nullIndicatorBit) {
    SlotDescriptor d = analyzer.getSlotDescriptor(colAlias);
    checkLayoutParams(d, byteSize, byteOffset, nullIndicatorByte, nullIndicatorBit);
  }

  @Test
  public void TestSubquery() throws AnalysisException {
    AnalyzesOk("select y x from (select id y from functional.hbasealltypessmall) a");
    AnalyzesOk("select id from (select id from functional.hbasealltypessmall) a");
    AnalyzesOk("select * from (select id+2 from functional.hbasealltypessmall) a");
    AnalyzesOk("select t1 c from " +
        "(select c t1 from (select id c from functional.hbasealltypessmall) t1) a");
    AnalysisError("select id from (select id+2 from functional.hbasealltypessmall) a",
        "couldn't resolve column reference: 'id'");
    AnalyzesOk("select a.* from (select id+2 from functional.hbasealltypessmall) a");

    // join test
    AnalyzesOk("select * from (select id+2 id from functional.hbasealltypessmall) a " +
        "join (select * from functional.AllTypes where true) b");
    AnalyzesOk("select a.x from (select count(id) x from functional.AllTypes) a");
    AnalyzesOk("select a.* from (select count(id) from functional.AllTypes) a");
    AnalysisError("select a.id from (select id y from functional.hbasealltypessmall) a",
        "unknown column 'id' (table alias 'a')");
    AnalyzesOk("select * from (select * from functional.AllTypes) a where year = 2009");
    AnalyzesOk("select * from (select * from functional.alltypesagg) a right outer join" +
        "             (select * from functional.alltypessmall) b using (id, int_col) " +
        "       where a.day >= 6 and b.month > 2 and a.tinyint_col = 15 and " +
        "             b.string_col = '15' and a.tinyint_col + b.tinyint_col < 15");
    AnalyzesOk("select * from (select a.smallint_col+b.smallint_col  c1" +
        "         from functional.alltypesagg a join functional.alltypessmall b " +
        "         using (id, int_col)) x " +
        "         where x.c1 > 100");
    AnalyzesOk("select a.* from" +
        " (select * from (select id+2 from functional.hbasealltypessmall) b) a");
    AnalysisError("select * from " +
        "(select * from functional.alltypes a join " +
        "functional.alltypes b on (a.int_col = b.int_col)) x",
        "duplicated inline view column alias: 'id' in inline view 'x'");

    // subquery on the rhs of the join
    AnalyzesOk("select x.float_col " +
        "       from functional.alltypessmall c join " +
        "          (select a.smallint_col smallint_col, a.tinyint_col tinyint_col, " +
        "                   a.int_col int_col, b.float_col float_col" +
        "          from (select * from functional.alltypesagg a where month=1) a join " +
        "                  functional.alltypessmall b on (a.smallint_col = b.id)) x " +
        "            on (x.tinyint_col = c.id)");

    // aggregate test
    AnalyzesOk("select count(*) from (select count(id) from " +
               "functional.AllTypes group by id) a");
    AnalyzesOk("select count(a.x) from (select id+2 x " +
               "from functional.hbasealltypessmall) a");
    AnalyzesOk("select * from (select id, zip " +
        "       from (select * from functional.testtbl) x " +
        "       group by zip, id having count(*) > 0) x");

    AnalysisError("select zip + count(*) from functional.testtbl",
        "select list expression not produced by aggregation output " +
        "(missing from GROUP BY clause?)");

    // union test
    AnalyzesOk("select a.* from " +
        "(select int_col from functional.alltypes " +
        " union all " +
        " select tinyint_col from functional.alltypessmall) a");
    AnalyzesOk("select a.* from " +
        "(select int_col from functional.alltypes " +
        " union all " +
        " select tinyint_col from functional.alltypessmall) a " +
        "union all " +
        "select smallint_col from functional.alltypes");
    AnalyzesOk("select a.* from " +
        "(select int_col from functional.alltypes " +
        " union all " +
        " select b.smallint_col from " +
        "  (select smallint_col from functional.alltypessmall" +
        "   union all" +
        "   select tinyint_col from functional.alltypes) b) a");
    // negative union test, column labels are inherited from first select block
    AnalysisError("select tinyint_col from " +
        "(select int_col from functional.alltypes " +
        " union all " +
        " select tinyint_col from functional.alltypessmall) a",
        "couldn't resolve column reference: 'tinyint_col'");

    // negative aggregate test
    AnalysisError("select * from " +
        "(select id, zip from functional.testtbl group by id having count(*) > 0) x",
        "select list expression not produced by aggregation output " +
            "(missing from GROUP BY clause?)");
    AnalysisError("select * from " +
        "(select id from functional.testtbl group by id having zip + count(*) > 0) x",
        "HAVING clause not produced by aggregation output " +
            "(missing from GROUP BY clause?)");
    AnalysisError("select * from " +
        "(select zip, count(*) from functional.testtbl group by 3) x",
        "GROUP BY: ordinal exceeds number of items in select list");
    AnalysisError("select * from " +
        "(select * from functional.alltypes group by 1) x",
        "cannot combine '*' in select list with GROUP BY");
    AnalysisError("select * from " +
        "(select zip, count(*) from functional.testtbl group by count(*)) x",
        "GROUP BY expression must not contain aggregate functions");
    AnalysisError("select * from " +
        "(select zip, count(*) from functional.testtbl group by count(*) + min(zip)) x",
        "GROUP BY expression must not contain aggregate functions");
    AnalysisError("select * from " +
        "(select zip, count(*) from functional.testtbl group by 2) x",
        "GROUP BY expression must not contain aggregate functions");

    // order by, top-n
    AnalyzesOk("select * from (select zip, count(*) " +
        "       from (select * from functional.testtbl) x " +
        "       group by 1 order by count(*) + min(zip) limit 5) x");
    AnalyzesOk("select c1, c2 from (select zip c1 , count(*) c2 " +
        "                     from (select * from functional.testtbl) x group by 1) x " +
        "        order by 2, 1 limit 5");

    // test NULLs
    AnalyzesOk("select * from (select NULL) a");
  }

  @Test
  public void TestStar() throws AnalysisException {
    AnalyzesOk("select * from functional.AllTypes");
    AnalyzesOk("select functional.alltypes.* from functional.AllTypes");
    // different db
    AnalyzesOk("select functional_seq.alltypes.* from functional_seq.alltypes");
    // two tables w/ identical names from different dbs
    AnalyzesOk("select functional.alltypes.*, functional_seq.alltypes.* " +
        "from functional.alltypes, functional_seq.alltypes");
    AnalyzesOk("select * from functional.alltypes, functional_seq.alltypes");
    // '*' without from clause has no meaning.
    AnalysisError("select *", "'*' expression in select list requires FROM clause.");
    AnalysisError("select 1, *, 2+4",
        "'*' expression in select list requires FROM clause.");
    AnalysisError("select a.*", "unknown table: a");
  }

  @Test
  public void TestTimestampValueExprs() throws AnalysisException {
    AnalyzesOk("select cast (0 as timestamp)");
    AnalyzesOk("select cast (0.1 as timestamp)");
    AnalyzesOk("select cast ('1970-10-10 10:00:00.123' as timestamp)");
  }

  @Test
  public void TestBooleanValueExprs() throws AnalysisException {
    // Test predicates in where clause.
    AnalyzesOk("select * from functional.AllTypes where true");
    AnalyzesOk("select * from functional.AllTypes where false");
    AnalyzesOk("select * from functional.AllTypes where NULL");
    AnalyzesOk("select * from functional.AllTypes where bool_col = true");
    AnalyzesOk("select * from functional.AllTypes where bool_col = false");
    AnalyzesOk("select * from functional.AllTypes where bool_col = NULL");
    AnalyzesOk("select * from functional.AllTypes where NULL = NULL");
    AnalyzesOk("select * from functional.AllTypes where NULL and NULL or NULL");
    AnalyzesOk("select * from functional.AllTypes where true or false");
    AnalyzesOk("select * from functional.AllTypes where true and false");
    AnalyzesOk("select * from functional.AllTypes " +
        "where true or false and bool_col = false");
    AnalyzesOk("select * from functional.AllTypes " +
        "where true and false or bool_col = false");
    // In select list.
    AnalyzesOk("select bool_col = true from functional.AllTypes");
    AnalyzesOk("select bool_col = false from functional.AllTypes");
    AnalyzesOk("select bool_col = NULL from functional.AllTypes");
    AnalyzesOk("select true or false and bool_col = false from functional.AllTypes");
    AnalyzesOk("select true and false or bool_col = false from functional.AllTypes");
    AnalyzesOk("select NULL or NULL and NULL from functional.AllTypes");
  }

  @Test
  public void TestOrdinals() throws AnalysisException {
    // can't group or order on *
    AnalysisError("select * from functional.alltypes group by 1",
        "cannot combine '*' in select list with GROUP BY");
    AnalysisError("select * from functional.alltypes order by 1",
        "ORDER BY: ordinal refers to '*' in select list");
  }

  @Test
  public void TestFromClause() throws AnalysisException {
    AnalyzesOk("select int_col from functional.alltypes");
    AnalysisError("select int_col from badtbl", "Unknown table");

    // case-insensitive
    AnalyzesOk("SELECT INT_COL FROM FUNCTIONAL.ALLTYPES");
    AnalyzesOk("SELECT INT_COL FROM functional.alltypes");
    AnalyzesOk("SELECT INT_COL FROM functional.aLLTYPES");
    AnalyzesOk("SELECT INT_COL FROM Functional.ALLTYPES");
    AnalyzesOk("SELECT INT_COL FROM FUNCTIONAL.ALLtypes");
    AnalyzesOk("SELECT INT_COL FROM FUNCTIONAL.alltypes");
    AnalyzesOk("select functional.AllTypes.Int_Col from functional.alltypes");

    // aliases work
    AnalyzesOk("select a.int_col from functional.alltypes a");
    // implicit aliases
    // This does not work
    AnalyzesOk("select int_col, zip from functional.alltypes, functional.testtbl");
    // duplicate alias
    AnalysisError("select a.int_col, a.id " +
        "          from functional.alltypes a, functional.testtbl a",
        "Duplicate table alias");
    // duplicate implicit alias
    AnalysisError("select int_col from functional.alltypes, " +
        "functional.alltypes", "Duplicate table alias");

    // resolves dbs correctly
    AnalyzesOk("select zip from functional.testtbl");
    AnalysisError("select int_col from functional.testtbl",
        "couldn't resolve column reference");
  }

  @Test
  public void TestNoFromClause() throws AnalysisException {
    AnalyzesOk("select 'test'");
    AnalyzesOk("select 1 + 1, -128, 'two', 1.28");
    AnalyzesOk("select -1, 1 - 1, 10 - -1, 1 - - - 1");
    AnalyzesOk("select -1.0, 1.0 - 1.0, 10.0 - -1.0, 1.0 - - - 1.0");
    AnalysisError("select a + 1", "couldn't resolve column reference: 'a'");
    // Test predicates in select list.
    AnalyzesOk("select true");
    AnalyzesOk("select false");
    AnalyzesOk("select true or false");
    AnalyzesOk("select true and false");
    // Test NULL's in select list.
    AnalyzesOk("select null");
    AnalyzesOk("select null and null");
    AnalyzesOk("select null or null");
    AnalyzesOk("select null is null");
    AnalyzesOk("select null is not null");
    AnalyzesOk("select int_col is not null from functional.alltypes");
  }

  @Test
  public void TestOnClause() throws AnalysisException {
    AnalyzesOk(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (a.int_col = b.int_col)");
    AnalyzesOk(
        "select a.int_col " +
        "from functional.alltypes a join functional.alltypes b on " +
        "(a.int_col = b.int_col and a.string_col = b.string_col)");
    AnalyzesOk(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (a.bool_col)");
    AnalyzesOk(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (NULL)");
    // ON or USING clause not required for inner join
    AnalyzesOk("select a.int_col from functional.alltypes a join functional.alltypes b");
    // arbitrary expr not returning bool
    AnalysisError(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (trim(a.string_col))",
        "ON clause 'trim(a.string_col)' requires return type 'BOOLEAN'. " +
        "Actual type is 'STRING'.");
    AnalysisError(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (a.int_col * b.float_col)",
        "ON clause 'a.int_col * b.float_col' requires return type 'BOOLEAN'. " +
        "Actual type is 'DOUBLE'.");
    // unknown column
    AnalysisError(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (a.int_col = b.badcol)",
        "unknown column 'badcol'");
    // ambiguous col ref
    AnalysisError(
        "select a.int_col from functional.alltypes a " +
        "join functional.alltypes b on (int_col = int_col)",
        "Unqualified column reference 'int_col' is ambiguous");
    // unknown alias
    AnalysisError(
        "select a.int_col from functional.alltypes a join functional.alltypes b on " +
        "(a.int_col = badalias.int_col)",
        "unknown table alias: 'badalias'");
    // incompatible comparison
    AnalysisError(
        "select a.int_col from functional.alltypes a join " +
        "functional.alltypes b on (a.bool_col = b.string_col)",
        "operands are not comparable: a.bool_col = b.string_col");
    AnalyzesOk(
    "select a.int_col, b.int_col, c.int_col " +
        "from functional.alltypes a join functional.alltypes b on " +
        "(a.int_col = b.int_col and a.string_col = b.string_col)" +
        "join functional.alltypes c on " +
        "(b.int_col = c.int_col and b.string_col = c.string_col " +
        "and b.bool_col = c.bool_col)");
    // can't reference an alias that gets declared afterwards
    AnalysisError(
        "select a.int_col, b.int_col, c.int_col " +
        "from functional.alltypes a join functional.alltypes b on " +
        "(c.int_col = b.int_col and a.string_col = b.string_col)" +
        "join functional.alltypes c on " +
        "(b.int_col = c.int_col and b.string_col = c.string_col " +
        "and b.bool_col = c.bool_col)",
        "unknown table alias: 'c'");

    // outer joins require ON/USING clause
    AnalyzesOk("select * from functional.alltypes a left outer join " +
        "functional.alltypes b on (a.id = b.id)");
    AnalyzesOk("select * from functional.alltypes a left outer join " +
        "functional.alltypes b using (id)");
    AnalysisError("select * from functional.alltypes a " +
        "left outer join functional.alltypes b",
        "LEFT OUTER JOIN requires an ON or USING clause");
    AnalyzesOk("select * from functional.alltypes a right outer join " +
        "functional.alltypes b on (a.id = b.id)");
    AnalyzesOk("select * from functional.alltypes a right outer join " +
        "functional.alltypes b using (id)");
    AnalysisError("select * from functional.alltypes a " +
        "right outer join functional.alltypes b",
        "RIGHT OUTER JOIN requires an ON or USING clause");
    AnalyzesOk("select * from functional.alltypes a full outer join " +
        "functional.alltypes b on (a.id = b.id)");
    AnalyzesOk("select * from functional.alltypes a full outer join " +
        "functional.alltypes b using (id)");
    AnalysisError("select * from functional.alltypes a full outer join " +
        "functional.alltypes b",
        "FULL OUTER JOIN requires an ON or USING clause");

    // semi join requires ON/USING clause
    AnalyzesOk("select a.id from functional.alltypes a left semi join " +
        "functional.alltypes b on (a.id = b.id)");
    AnalyzesOk("select a.id from functional.alltypes a left semi join " +
        "functional.alltypes b using (id)");
    AnalysisError("select a.id from functional.alltypes a " +
        "left semi join functional.alltypes b",
        "LEFT SEMI JOIN requires an ON or USING clause");
    // TODO: enable when implemented
    // must not reference semi-joined alias outside of join clause
    // AnalysisError(
    // "select a.id, b.id from alltypes a left semi join alltypes b on (a.id = b.id)",
    // "x");
  }

  @Test
  public void TestUsingClause() throws AnalysisException {
    AnalyzesOk("select a.int_col, b.int_col from functional.alltypes a join " +
        "functional.alltypes b using (int_col)");
    AnalyzesOk("select a.int_col, b.int_col from " +
        "functional.alltypes a join functional.alltypes b " +
        "using (int_col, string_col)");
    AnalyzesOk(
        "select a.int_col, b.int_col, c.int_col " +
        "from functional.alltypes a " +
        "join functional.alltypes b using (int_col, string_col) " +
        "join functional.alltypes c using (int_col, string_col, bool_col)");
    // unknown column
    AnalysisError("select a.int_col from functional.alltypes a " +
        "join functional.alltypes b using (badcol)",
        "unknown column badcol for alias a");
    AnalysisError(
        "select a.int_col from functional.alltypes a " +
         "join functional.alltypes b using (int_col, badcol)",
        "unknown column badcol for alias a ");
  }

  @Test
  public void TestJoinHints() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes a join [broadcast] " +
        "functional.alltypes b using (int_col)");
    AnalyzesOk("select * from functional.alltypes a join [shuffle] " +
        "functional.alltypes b using (int_col)");
    AnalysisError(
        "select * from functional.alltypes a join [broadcast,shuffle] " +
         "functional.alltypes b using (int_col)",
        "Conflicting JOIN hint: shuffle");
    AnalysisError(
        "select * from functional.alltypes a join [bla] " +
         "functional.alltypes b using (int_col)",
        "JOIN hint not recognized: bla");
  }

  @Test
  public void TestWhereClause() throws AnalysisException {
    AnalyzesOk("select zip, name from functional.testtbl where id > 15");
    AnalysisError("select zip, name from functional.testtbl where badcol > 15",
        "couldn't resolve column reference");
    AnalyzesOk("select * from functional.testtbl where true");
    AnalysisError("select * from functional.testtbl where count(*) > 0",
        "aggregation function not allowed in WHERE clause");
    // NULL and bool literal in binary predicate.
    for (BinaryPredicate.Operator op : BinaryPredicate.Operator.values()) {
      AnalyzesOk("select id from functional.testtbl where id " +
          op.toString() + " true");
      AnalyzesOk("select id from functional.testtbl where id " +
          op.toString() + " false");
      AnalyzesOk("select id from functional.testtbl where id " +
          op.toString() + " NULL");
    }
    // Where clause is a SlotRef of type bool.
    AnalyzesOk("select id from functional.alltypes where bool_col");
    // Arbitrary exprs that do not return bool.
    AnalysisError("select id from functional.alltypes where int_col",
        "WHERE clause requires return type 'BOOLEAN'. Actual type is 'INT'.");
    AnalysisError("select id from functional.alltypes where trim('abc')",
        "WHERE clause requires return type 'BOOLEAN'. Actual type is 'STRING'.");
    AnalysisError("select id from functional.alltypes where (int_col + float_col) * 10",
        "WHERE clause requires return type 'BOOLEAN'. Actual type is 'DOUBLE'.");
  }

  @Test
  public void TestAggregates() throws AnalysisException {
    AnalyzesOk("select count(*), min(id), max(id), sum(id), avg(id) " +
        "from functional.testtbl");
    AnalyzesOk("select count(NULL), min(NULL), max(NULL), sum(NULL), avg(NULL) " +
        "from functional.testtbl");
    AnalysisError("select id, zip from functional.testtbl where count(*) > 0",
        "aggregation function not allowed in WHERE clause");

    // only count() allows '*'
    AnalysisError("select avg(*) from functional.testtbl",
        "'*' can only be used in conjunction with COUNT");
    AnalysisError("select min(*) from functional.testtbl",
        "'*' can only be used in conjunction with COUNT");
    AnalysisError("select max(*) from functional.testtbl",
        "'*' can only be used in conjunction with COUNT");
    AnalysisError("select sum(*) from functional.testtbl",
        "'*' can only be used in conjunction with COUNT");

    // multiple args
    AnalysisError("select count(id, zip) from functional.testtbl",
        "COUNT must have DISTINCT for multiple arguments: COUNT(id, zip)");
    AnalysisError("select min(id, zip) from functional.testtbl",
        "MIN requires exactly one parameter");
    AnalysisError("select max(id, zip) from functional.testtbl",
        "MAX requires exactly one parameter");
    AnalysisError("select sum(id, zip) from functional.testtbl",
        "SUM requires exactly one parameter");
    AnalysisError("select avg(id, zip) from functional.testtbl",
        "AVG requires exactly one parameter");

    // nested aggregates
    AnalysisError("select sum(count(*)) from functional.testtbl",
        "aggregate function cannot contain aggregate parameters");

    // wrong type
    AnalysisError("select sum(timestamp_col) from functional.alltypes",
        "SUM requires a numeric parameter: SUM(timestamp_col)");
    AnalysisError("select sum(string_col) from functional.alltypes",
        "SUM requires a numeric parameter: SUM(string_col)");
    AnalysisError("select avg(string_col) from functional.alltypes",
        "AVG requires a numeric or timestamp parameter: AVG(string_col)");

    // aggregate requires table in the FROM clause
    AnalysisError("select count(*)", "aggregation without a FROM clause is not allowed");
    AnalysisError("select min(1)", "aggregation without a FROM clause is not allowed");
  }

  @Test
  public void TestDistinct() throws AnalysisException {
    // DISTINCT
    AnalyzesOk("select count(distinct id) as sum_id from " +
        "functional.testtbl order by sum_id");
    AnalyzesOk("select count(distinct id) as sum_id from " +
        "functional.testtbl order by max(id)");
    AnalyzesOk("select distinct id, zip from functional.testtbl");
    AnalyzesOk("select distinct * from functional.testtbl");
    AnalysisError("select distinct count(*) from functional.testtbl",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select distinct id, zip from functional.testtbl group by 1, 2",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select distinct id, zip, count(*) from " +
        "functional.testtbl group by 1, 2",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalyzesOk("select count(distinct id, zip) from functional.testtbl");
    AnalysisError("select count(distinct id, zip), count(distinct zip) " +
        "from functional.testtbl",
        "all DISTINCT aggregate functions need to have the same set of parameters");
    AnalyzesOk("select tinyint_col, count(distinct int_col, bigint_col) "
        + "from functional.alltypesagg group by 1");
    AnalyzesOk("select tinyint_col, count(distinct int_col),"
        + "sum(distinct int_col) from functional.alltypesagg group by 1");
    AnalysisError("select tinyint_col, count(distinct int_col),"
        + "sum(distinct bigint_col) from functional.alltypesagg group by 1",
        "all DISTINCT aggregate functions need to have the same set of parameters");
    // min and max are ignored in terms of DISTINCT
    AnalyzesOk("select tinyint_col, count(distinct int_col),"
        + "min(distinct smallint_col), max(distinct string_col) "
        + "from functional.alltypesagg group by 1");
  }

  @Test
  public void TestDistinctInlineView() throws AnalysisException {
    // DISTINCT
    AnalyzesOk("select distinct id from " +
        "(select distinct id, zip from (select * from functional.testtbl) x) y");
    AnalyzesOk("select distinct * from " +
        "(select distinct * from (Select * from functional.testtbl) x) y");
    AnalyzesOk("select distinct * from (select count(*) from functional.testtbl) x");
    AnalyzesOk("select count(distinct id, zip) " +
        "from (select * from functional.testtbl) x");
    AnalyzesOk("select * from (select tinyint_col, count(distinct int_col, bigint_col) "
        + "from (select * from functional.alltypesagg) x group by 1) y");
    AnalyzesOk("select tinyint_col, count(distinct int_col),"
        + "sum(distinct int_col) from " +
        "(select * from functional.alltypesagg) x group by 1");

    // Error case when distinct is inside an inline view
    AnalysisError("select * from " +
        "(select distinct count(*) from functional.testtbl) x",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select * from " +
        "(select distinct id, zip from functional.testtbl group by 1, 2) x",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select * from " +
        "(select distinct id, zip, count(*) from functional.testtbl group by 1, 2) x",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select * from " +
        "(select count(distinct id, zip), count(distinct zip) " +
        "from functional.testtbl) x",
        "all DISTINCT aggregate functions need to have the same set of parameters");
    AnalysisError("select * from " + "(select tinyint_col, count(distinct int_col),"
        + "sum(distinct bigint_col) from functional.alltypesagg group by 1) x",
        "all DISTINCT aggregate functions need to have the same set of parameters");

    // Error case when inline view is in the from clause
    AnalysisError("select distinct count(*) from (select * from functional.testtbl) x",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select distinct id, zip from " +
        "(select * from functional.testtbl) x group by 1, 2",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalysisError("select distinct id, zip, count(*) from " +
        "(select * from functional.testtbl) x group by 1, 2",
        "cannot combine SELECT DISTINCT with aggregate functions or GROUP BY");
    AnalyzesOk("select count(distinct id, zip) " +
        "from (select * from functional.testtbl) x");
    AnalysisError("select count(distinct id, zip), count(distinct zip) " +
        " from (select * from functional.testtbl) x",
        "all DISTINCT aggregate functions need to have the same set of parameters");
    AnalyzesOk("select tinyint_col, count(distinct int_col, bigint_col) "
        + "from (select * from functional.alltypesagg) x group by 1");
    AnalyzesOk("select tinyint_col, count(distinct int_col),"
        + "sum(distinct int_col) from " +
        "(select * from functional.alltypesagg) x group by 1");
    AnalysisError("select tinyint_col, count(distinct int_col),"
        + "sum(distinct bigint_col) from " +
        "(select * from functional.alltypesagg) x group by 1",
        "all DISTINCT aggregate functions need to have the same set of parameters");
  }

  @Test
  public void TestGroupBy() throws AnalysisException {
    AnalyzesOk("select zip, count(*) from functional.testtbl group by zip");
    AnalyzesOk("select zip + count(*) from functional.testtbl group by zip");
    // grouping on constants is ok and doesn't require them to be in select list
    AnalyzesOk("select count(*) from functional.testtbl group by 2*3+4");
    AnalyzesOk("select count(*) from functional.testtbl " +
        "group by true, false, NULL");
    // ok for constants in select list not to be in group by list
    AnalyzesOk("select true, NULL, 1*2+5 as a, zip, count(*) from functional.testtbl " +
        "group by zip");

    // doesn't group by all non-agg select list items
    AnalysisError("select zip, count(*) from functional.testtbl",
        "select list expression not produced by aggregation output " +
        "(missing from GROUP BY clause?)");
    AnalysisError("select zip + count(*) from functional.testtbl",
        "select list expression not produced by aggregation output " +
        "(missing from GROUP BY clause?)");

    // test having clause
    AnalyzesOk("select id, zip from functional.testtbl " +
        "group by zip, id having count(*) > 0");
    AnalyzesOk("select count(*) from functional.alltypes " +
        "group by bool_col having bool_col");
    // arbitrary exprs not returning boolean
    AnalysisError("select count(*) from functional.alltypes " +
        "group by bool_col having 5 + 10 * 5.6",
        "HAVING clause '5.0 + 10.0 * 5.6' requires return type 'BOOLEAN'. " +
        "Actual type is 'DOUBLE'.");
    AnalysisError("select count(*) from functional.alltypes " +
        "group by bool_col having int_col",
        "HAVING clause 'int_col' requires return type 'BOOLEAN'. Actual type is 'INT'.");
    AnalysisError("select id, zip from functional.testtbl " +
        "group by id having count(*) > 0",
        "select list expression not produced by aggregation output " +
        "(missing from GROUP BY clause?)");
    AnalysisError("select id from functional.testtbl " +
        "group by id having zip + count(*) > 0",
        "HAVING clause not produced by aggregation output " +
        "(missing from GROUP BY clause?)");
    // resolves ordinals
    AnalyzesOk("select zip, count(*) from functional.testtbl group by 1");
    AnalyzesOk("select count(*), zip from functional.testtbl group by 2");
    AnalysisError("select zip, count(*) from functional.testtbl group by 3",
        "GROUP BY: ordinal exceeds number of items in select list");
    AnalysisError("select * from functional.alltypes group by 1",
        "cannot combine '*' in select list with GROUP BY");
    // picks up select item alias
    AnalyzesOk("select zip z, id iD1, id ID2, count(*) " +
        "from functional.testtbl group by z, ID1, id2");
    // ambiguous alias
    AnalysisError("select zip a, id a, count(*) from functional.testtbl group by a",
        "Column a in group by clause is ambiguous");
    AnalysisError("select zip id, id, count(*) from functional.testtbl group by id",
        "Column id in group by clause is ambiguous");
    AnalysisError("select zip id, zip ID, count(*) from functional.testtbl group by id",
        "Column id in group by clause is ambiguous");


    // can't group by aggregate
    AnalysisError("select zip, count(*) from functional.testtbl group by count(*)",
        "GROUP BY expression must not contain aggregate functions");
    AnalysisError("select zip, count(*) " +
        "from functional.testtbl group by count(*) + min(zip)",
        "GROUP BY expression must not contain aggregate functions");
    AnalysisError("select zip, count(*) from functional.testtbl group by 2",
        "GROUP BY expression must not contain aggregate functions");

    // multiple grouping cols
    AnalyzesOk("select int_col, string_col, bigint_col, count(*) " +
        "from functional.alltypes group by string_col, int_col, bigint_col");
    AnalyzesOk("select int_col, string_col, bigint_col, count(*) " +
        "from functional.alltypes group by 2, 1, 3");
    AnalysisError("select int_col, string_col, bigint_col, count(*) " +
        "from functional.alltypes group by 2, 1, 4",
        "GROUP BY expression must not contain aggregate functions");

    // group by floating-point column
    AnalyzesOk("select float_col, double_col, count(*) " +
        "from functional.alltypes group by 1, 2");
    // group by floating-point exprs
    AnalyzesOk("select int_col + 0.5, count(*) from functional.alltypes group by 1");
    AnalyzesOk("select cast(int_col as double), count(*)" +
        "from functional.alltypes group by 1");
  }

  @Test
  public void TestAvgSubstitution() throws AnalysisException {
    SelectStmt select = (SelectStmt) AnalyzesOk(
        "select avg(id) from functional.testtbl having count(id) > 0 order by avg(zip)");
    ArrayList<Expr> selectListExprs = select.getResultExprs();
    assertNotNull(selectListExprs);
    assertEquals(selectListExprs.size(), 1);
    // all agg exprs are replaced with refs to agg output slots
    Expr havingPred = select.getHavingPred();
    assertEquals("<slot 2> / <slot 3>",
        selectListExprs.get(0).toSql());
    assertNotNull(havingPred);
    // we only have one 'count(id)' slot (slot 2)
    assertEquals(havingPred.toSql(), "<slot 3> > 0");
    Expr orderingExpr = select.getSortInfo().getOrderingExprs().get(0);
    assertNotNull(orderingExpr);
    assertEquals("<slot 4> / <slot 5>", orderingExpr.toSql());
  }

  @Test
  public void TestOrderBy() throws AnalysisException {
    AnalyzesOk("select zip, id from functional.testtbl order by zip");
    AnalyzesOk("select zip, id from functional.testtbl order by zip asc");
    AnalyzesOk("select zip, id from functional.testtbl order by zip desc");
    AnalyzesOk("select zip, id from functional.testtbl " +
        "order by true asc, false desc, NULL asc");

    // resolves ordinals
    AnalyzesOk("select zip, id from functional.testtbl order by 1");
    AnalyzesOk("select zip, id from functional.testtbl order by 2 desc, 1 asc");
    // ordinal out of range
    AnalysisError("select zip, id from functional.testtbl order by 0",
        "ORDER BY: ordinal must be >= 1");
    AnalysisError("select zip, id from functional.testtbl order by 3",
        "ORDER BY: ordinal exceeds number of items in select list");
    // can't order by '*'
    AnalysisError("select * from functional.alltypes order by 1",
        "ORDER BY: ordinal refers to '*' in select list");
    // picks up select item alias
    AnalyzesOk("select zip z, id C, id D from functional.testtbl order by z, C, d");

    // can introduce additional aggregates in order by clause
    AnalyzesOk("select zip, count(*) from functional.testtbl group by 1 order by count(*)");
    AnalyzesOk("select zip, count(*) from functional.testtbl " +
        "group by 1 order by count(*) + min(zip)");
    AnalysisError("select zip, count(*) from functional.testtbl group by 1 order by id",
        "ORDER BY expression not produced by aggregation output " +
        "(missing from GROUP BY clause?)");

    // multiple ordering exprs
    AnalyzesOk("select int_col, string_col, bigint_col from functional.alltypes " +
               "order by string_col, 15.7 * float_col, int_col + bigint_col");
    AnalyzesOk("select int_col, string_col, bigint_col from functional.alltypes " +
               "order by 2, 1, 3");

    // ordering by floating-point exprs is okay
    AnalyzesOk("select float_col, int_col + 0.5 from functional.alltypes order by 1, 2");
    AnalyzesOk("select float_col, int_col + 0.5 from functional.alltypes order by 2, 1");

    // select-list item takes precedence
    AnalyzesOk("select t1.int_col from functional.alltypes t1, " +
        "functional.alltypes t2 where t1.id = t2.id order by int_col");

    // Ambiguous alias cause error
    AnalysisError("select string_col a, int_col a from " +
        "functional.alltypessmall order by a limit 1",
        "Column a in order clause is ambiguous");
    AnalysisError("select string_col a, int_col A from " +
        "functional.alltypessmall order by a limit 1",
        "Column a in order clause is ambiguous");
  }

  @Test
  public void TestBinaryPredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes where bool_col != true");
    AnalyzesOk("select * from functional.alltypes where tinyint_col <> 1");
    AnalyzesOk("select * from functional.alltypes where smallint_col <= 23");
    AnalyzesOk("select * from functional.alltypes where int_col > 15");
    AnalyzesOk("select * from functional.alltypes where bigint_col >= 17");
    AnalyzesOk("select * from functional.alltypes where float_col < 15.0");
    AnalyzesOk("select * from functional.alltypes where double_col > 7.7");
    // automatic type cast if compatible
    AnalyzesOk("select * from functional.alltypes where 1 = 0");
    AnalyzesOk("select * from functional.alltypes where int_col = smallint_col");
    AnalyzesOk("select * from functional.alltypes where bigint_col = float_col");
    AnalyzesOk("select * from functional.alltypes where bool_col = 0");
    AnalyzesOk("select * from functional.alltypes where int_col = cast('0' as int)");
    AnalyzesOk("select * from functional.alltypes where cast(string_col as int) = 15");
    // tests with NULL
    AnalyzesOk("select * from functional.alltypes where bool_col != NULL");
    AnalyzesOk("select * from functional.alltypes where tinyint_col <> NULL");
    AnalyzesOk("select * from functional.alltypes where smallint_col <= NULL");
    AnalyzesOk("select * from functional.alltypes where int_col > NULL");
    AnalyzesOk("select * from functional.alltypes where bigint_col >= NULL");
    AnalyzesOk("select * from functional.alltypes where float_col < NULL");
    AnalyzesOk("select * from functional.alltypes where double_col > NULL");
    AnalyzesOk("select * from functional.alltypes where string_col = NULL");
    AnalyzesOk("select * from functional.alltypes where timestamp_col = NULL");
    // invalid casts
    AnalysisError("select * from functional.alltypes where bool_col = '15'",
        "operands are not comparable: bool_col = '15'");
    // AnalysisError("select * from functional.alltypes where date_col = 15",
    // "operands are not comparable: date_col = 15");
    // AnalysisError("select * from functional.alltypes where datetime_col = 1.0",
    // "operands are not comparable: datetime_col = 1.0");
  }

  @Test
  public void TestStringCasts() throws AnalysisException {
    // No implicit cast from STRING to numeric and boolean
    AnalysisError("select * from functional.alltypes where tinyint_col = '1'",
        "operands are not comparable: tinyint_col = '1'");
    AnalysisError("select * from functional.alltypes where bool_col = '0'",
        "operands are not comparable: bool_col = '0'");
    // No explicit cast from STRING to boolean.
    AnalysisError("select cast('false' as boolean) from functional.alltypes",
        "Invalid type cast of 'false' from STRING to BOOLEAN");

    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('0.5' as float)");
    AnalyzesOk("select * from functional.alltypes where " +
        "smallint_col = cast('0.5' as float)");
    AnalyzesOk("select * from functional.alltypes where int_col = cast('0.5' as float)");
    AnalyzesOk("select * from functional.alltypes where " +
        "bigint_col = cast('0.5' as float)");
    AnalyzesOk("select 1.0 = cast('" + Double.toString(Double.MIN_VALUE) +
        "' as double)");
    AnalyzesOk("select 1.0 = cast('-" + Double.toString(Double.MIN_VALUE) +
        "' as double)");
    AnalyzesOk("select 1.0 = cast('" + Double.toString(Double.MAX_VALUE) +
        "' as double)");
    AnalyzesOk("select 1.0 = cast('-" + Double.toString(Double.MAX_VALUE) +
        "' as double)");
    // Test chains of minus. Note that "--" is the a comment symbol.
    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('-1' as tinyint)");
    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('- -1' as tinyint)");
    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('- - -1' as tinyint)");
    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('- - - -1' as tinyint)");
    // Test correct casting to compatible type on bitwise ops.
    AnalyzesOk("select 1 | cast('" + Byte.toString(Byte.MIN_VALUE) + "' as int)");
    AnalyzesOk("select 1 | cast('" + Byte.toString(Byte.MAX_VALUE) + "' as int)");
    AnalyzesOk("select 1 | cast('" + Short.toString(Short.MIN_VALUE) + "' as int)");
    AnalyzesOk("select 1 | cast('" + Short.toString(Short.MAX_VALUE) + "' as int)");
    AnalyzesOk("select 1 | cast('" + Integer.toString(Integer.MIN_VALUE) + "' as int)");
    AnalyzesOk("select 1 | cast('" + Integer.toString(Integer.MAX_VALUE) + "' as int)");
    // We need to add 1 to MIN_VALUE because there are no negative integer literals.
    // The reason is that whether a minus belongs to an
    // arithmetic expr or a literal must be decided by the parser, not the lexer.
    AnalyzesOk("select 1 | cast('" + Long.toString(Long.MIN_VALUE + 1) + "' as bigint)");
    AnalyzesOk("select 1 | cast('" + Long.toString(Long.MAX_VALUE) + "' as bigint)");
    // Cast to numeric never overflow
    AnalyzesOk("select * from functional.alltypes where tinyint_col = " +
        "cast('" + Long.toString(Long.MIN_VALUE) + "1' as tinyint)");
    AnalyzesOk("select * from functional.alltypes where tinyint_col = " +
        "cast('" + Long.toString(Long.MAX_VALUE) + "1' as tinyint)");
    AnalyzesOk("select * from functional.alltypes where tinyint_col = " +
        "cast('" + Double.toString(Double.MAX_VALUE) + "1' as tinyint)");
    // Java converts a float underflow to 0.0.
    // Since there is no easy, reliable way to detect underflow,
    // we don't consider it an error.
    AnalyzesOk("select * from functional.alltypes where tinyint_col = " +
        "cast('" + Double.toString(Double.MIN_VALUE) + "1' as tinyint)");
    // Cast never raise analysis exception
    AnalyzesOk("select * from functional.alltypes where " +
        "tinyint_col = cast('--1' as tinyint)");

    // Cast string literal to string
    AnalyzesOk("select cast('abc' as string)");
  }

  /**
   * Tests that cast(null to type) returns type for all types.
   */
  @Test
  public void TestNullCasts() throws AnalysisException {
   for (PrimitiveType type: PrimitiveType.values()) {
     // Cannot cast to INVALID_TYPE, NULL_TYPE or unsupported types.
     if (!type.isValid() || type.isNull() || !type.isSupported()) {
       continue;
     }
     checkExprType("select cast(null as " + type + ")", type);
   }
  }

  // Analyzes query and asserts that the first result expr returns the given type.
  // Requires query to parse to a SelectStmt.
  private void checkExprType(String query, PrimitiveType type) {
    SelectStmt select = (SelectStmt) AnalyzesOk(query);
    assertEquals(select.getResultExprs().get(0).getType(), type);
  }

  @Test
  public void TestLikePredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes where string_col like 'test%'");
    AnalyzesOk("select * from functional.alltypes where string_col like string_col");
    AnalyzesOk("select * from functional.alltypes where 'test' like string_col");
    AnalyzesOk("select * from functional.alltypes where string_col rlike 'test%'");
    AnalyzesOk("select * from functional.alltypes where string_col regexp 'test.*'");
    AnalysisError("select * from functional.alltypes where string_col like 5",
        "right operand of LIKE must be of type STRING");
    AnalysisError("select * from functional.alltypes where 'test' like 5",
        "right operand of LIKE must be of type STRING");
    AnalysisError("select * from functional.alltypes where int_col like 'test%'",
        "left operand of LIKE must be of type STRING");
    AnalysisError("select * from functional.alltypes where string_col regexp 'test]['",
        "invalid regular expression in 'string_col REGEXP 'test][''");
    // Test NULLs.
    String[] likePreds = new String[] {"LIKE", "RLIKE", "REGEXP"};
    for (String likePred: likePreds) {
      AnalyzesOk(String.format("select * from functional.alltypes " +
          "where string_col %s NULL", likePred));
      AnalyzesOk(String.format("select * from functional.alltypes " +
          "where NULL %s string_col", likePred));
      AnalyzesOk(String.format("select * from functional.alltypes " +
          "where NULL %s NULL", likePred));
    }
  }

  @Test
  public void TestCompoundPredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes where " +
        "string_col = '5' and int_col = 5");
    AnalyzesOk("select * from functional.alltypes where " +
        "string_col = '5' or int_col = 5");
    AnalyzesOk("select * from functional.alltypes where (string_col = '5' " +
        "or int_col = 5) and string_col > '1'");
    AnalyzesOk("select * from functional.alltypes where not string_col = '5'");
    AnalyzesOk("select * from functional.alltypes where int_col = cast('5' as int)");

    // Test all combinations of truth values and bool_col with all boolean operators.
    String[] operands = new String[]{ "true", "false", "NULL", "bool_col" };
    for (String lop: operands) {
      for (String rop: operands) {
        for (CompoundPredicate.Operator op: CompoundPredicate.Operator.values()) {
          // Unary operator tested elsewhere (below).
          if (op == CompoundPredicate.Operator.NOT) continue;
          String expr = String.format("%s %s %s", lop, op, rop);
          AnalyzesOk(String.format("select %s from functional.alltypes where %s",
              expr, expr));
        }
      }
      String notExpr = String.format("%s %s", CompoundPredicate.Operator.NOT, lop);
      AnalyzesOk(String.format("select %s from functional.alltypes where %s",
          notExpr, notExpr));
    }

    // arbitrary exprs as operands should fail to analyze
    AnalysisError("select * from functional.alltypes where 1 + 2 and false",
        "Operand '1 + 2' part of predicate '1 + 2 AND FALSE' should return " +
            "type 'BOOLEAN' but returns type 'BIGINT'.");
    AnalysisError("select * from functional.alltypes where 1 + 2 or true",
        "Operand '1 + 2' part of predicate '1 + 2 OR TRUE' should return " +
            "type 'BOOLEAN' but returns type 'BIGINT'.");
    AnalysisError("select * from functional.alltypes where not 1 + 2",
        "Operand '1 + 2' part of predicate 'NOT 1 + 2' should return " +
            "type 'BOOLEAN' but returns type 'BIGINT'.");
    AnalysisError("select * from functional.alltypes where 1 + 2 and true",
        "Operand '1 + 2' part of predicate '1 + 2 AND TRUE' should return " +
            "type 'BOOLEAN' but returns type 'BIGINT'.");
    AnalysisError("select * from functional.alltypes where false and trim('abc')",
        "Operand 'trim('abc')' part of predicate 'FALSE AND trim('abc')' should " +
            "return type 'BOOLEAN' but returns type 'STRING'.");
    AnalysisError("select * from functional.alltypes where bool_col or double_col",
        "Operand 'double_col' part of predicate 'bool_col OR double_col' should " +
            "return type 'BOOLEAN' but returns type 'DOUBLE'.");
  }

  @Test
  public void TestIsNullPredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes where int_col is null");
    AnalyzesOk("select * from functional.alltypes where string_col is not null");
    AnalyzesOk("select * from functional.alltypes where null is not null");
  }

  @Test
  public void TestBetweenPredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes " +
        "where tinyint_col between smallint_col and int_col");
    AnalyzesOk("select * from functional.alltypes " +
        "where tinyint_col not between smallint_col and int_col");
    AnalyzesOk("select * from functional.alltypes " +
        "where 'abc' between string_col and date_string_col");
    AnalyzesOk("select * from functional.alltypes " +
        "where 'abc' not between string_col and date_string_col");
    // Additional predicates before and/or after between predicate.
    AnalyzesOk("select * from functional.alltypes " +
        "where string_col = 'abc' and tinyint_col between 10 and 20");
    AnalyzesOk("select * from functional.alltypes " +
        "where tinyint_col between 10 and 20 and string_col = 'abc'");
    AnalyzesOk("select * from functional.alltypes " +
        "where bool_col and tinyint_col between 10 and 20 and string_col = 'abc'");
    // Chaining/nesting of between predicates.
    AnalyzesOk("select * from functional.alltypes " +
        "where true between false and true and 'b' between 'a' and 'c'");
    // true between ('b' between 'a' and 'b') and ('bb' between 'aa' and 'cc)
    AnalyzesOk("select * from functional.alltypes " +
        "where true between 'b' between 'a' and 'c' and 'bb' between 'aa' and 'cc'");
    // Test proper precedence with exprs before between.
    AnalyzesOk("select 5 + 1 between 4 and 10");
    AnalyzesOk("select 'abc' like '%a' between true and false");
    AnalyzesOk("select false between (true and true) and (false and true)");
    // Lower and upper bounds require implicit casts.
    AnalyzesOk("select * from functional.alltypes " +
        "where double_col between smallint_col and int_col");
    // Comparison expr requires implicit cast.
    AnalyzesOk("select * from functional.alltypes " +
        "where smallint_col between float_col and double_col");
    // Test NULLs.
    AnalyzesOk("select * from functional.alltypes " +
        "where NULL between float_col and double_col");
    AnalyzesOk("select * from functional.alltypes " +
        "where smallint_col between NULL and double_col");
    AnalyzesOk("select * from functional.alltypes " +
        "where smallint_col between float_col and NULL");
    AnalyzesOk("select * from functional.alltypes " +
        "where NULL between NULL and NULL");
    // Incompatible types.
    AnalysisError("select * from functional.alltypes " +
        "where string_col between bool_col and double_col",
        "Incompatible return types 'STRING' and 'BOOLEAN' " +
        "of exprs 'string_col' and 'bool_col'.");
    AnalysisError("select * from functional.alltypes " +
        "where timestamp_col between int_col and double_col",
        "Incompatible return types 'TIMESTAMP' and 'INT' " +
        "of exprs 'timestamp_col' and 'int_col'.");
  }

  @Test
  public void TestInPredicates() throws AnalysisException {
    AnalyzesOk("select * from functional.alltypes where int_col in (1, 2, 3, 4)");
    AnalyzesOk("select * from functional.alltypes where int_col not in (1, 2, 3, 4)");
    AnalyzesOk("select * from functional.alltypes where " +
        "string_col in ('a', 'b', 'c', 'd')");
    AnalyzesOk("select * from functional.alltypes where " +
        "string_col not in ('a', 'b', 'c', 'd')");
    // Test booleans.
    AnalyzesOk("select * from functional.alltypes where " +
        "true in (bool_col, true and false)");
    AnalyzesOk("select * from functional.alltypes where " +
        "true not in (bool_col, true and false)");
    // In list requires implicit casts.
    AnalyzesOk("select * from functional.alltypes where " +
        "double_col in (int_col, bigint_col)");
    // Comparison expr requires implicit cast.
    AnalyzesOk("select * from functional.alltypes where " +
        "int_col in (double_col, bigint_col)");
    // Test predicates.
    AnalyzesOk("select * from functional.alltypes where " +
        "!true in (false or true, true and false)");
    // Test NULLs.
    AnalyzesOk("select * from functional.alltypes where " +
        "NULL in (NULL, NULL)");
    // Incompatible types.
    AnalysisError("select * from functional.alltypes where " +
        "string_col in (bool_col, double_col)",
        "Incompatible return types 'STRING' and 'BOOLEAN' " +
        "of exprs 'string_col' and 'bool_col'.");
    AnalysisError("select * from functional.alltypes where " +
        "timestamp_col in (int_col, double_col)",
        "Incompatible return types 'TIMESTAMP' and 'INT' " +
        "of exprs 'timestamp_col' and 'int_col'.");
    AnalysisError("select * from functional.alltypes where " +
        "timestamp_col in (NULL, int_col)",
        "Incompatible return types 'TIMESTAMP' and 'INT' " +
        "of exprs 'timestamp_col' and 'int_col'.");
  }

  /**
   * Test of all arithmetic type casts following mysql's casting policy.
   * @throws AnalysisException
   */
  @Test
  public void TestArithmeticTypeCasts() throws AnalysisException {
    // test all numeric types and the null type
    List<PrimitiveType> numericTypes =
        new ArrayList<PrimitiveType>(PrimitiveType.getNumericTypes());
    numericTypes.add(PrimitiveType.NULL_TYPE);

    for (PrimitiveType type1 : numericTypes) {
      for (PrimitiveType type2 : numericTypes) {
        PrimitiveType compatibleType =
            PrimitiveType.getAssignmentCompatibleType(type1, type2);
        PrimitiveType promotedType = compatibleType.getMaxResolutionType();

        // +, -, *
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.ADD, null,
            promotedType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.ADD, null,
            promotedType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.SUBTRACT, null,
            promotedType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.SUBTRACT, null,
            promotedType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.MULTIPLY, null,
            promotedType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.MULTIPLY, null,
            promotedType);

        // /
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.DIVIDE, null,
            PrimitiveType.DOUBLE);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.DIVIDE, null,
            PrimitiveType.DOUBLE);

        // % div, &, |, ^ only for fixed-point types
        if ((!type1.isFixedPointType() && !type1.isNull())
            || (!type2.isFixedPointType() && !type2.isNull())) {
          continue;
        }
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.MOD, null,
            compatibleType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.MOD, null,
            compatibleType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.INT_DIVIDE, null,
            compatibleType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.INT_DIVIDE, null,
            compatibleType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.BITAND, null,
            compatibleType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.BITAND, null,
            compatibleType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.BITOR, null,
            compatibleType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.BITOR, null,
            compatibleType);
        typeCastTest(type1, type2, false, ArithmeticExpr.Operator.BITXOR, null,
            compatibleType);
        typeCastTest(type1, type2, true, ArithmeticExpr.Operator.BITXOR, null,
            compatibleType);
      }
    }

    List<PrimitiveType> fixedPointTypes = new ArrayList<PrimitiveType>(
        PrimitiveType.getFixedPointTypes());
    fixedPointTypes.add(PrimitiveType.NULL_TYPE);
    for (PrimitiveType type: fixedPointTypes) {
      typeCastTest(null, type, false, ArithmeticExpr.Operator.BITNOT, null, type);
    }
  }

  /**
   * Test of all type casts in comparisons following mysql's casting policy.
   *
   * @throws AnalysisException
   */
  @Test
  public void TestComparisonTypeCasts() throws AnalysisException {
    // test all numeric types and the null type
    List<PrimitiveType> types =
        new ArrayList<PrimitiveType>(PrimitiveType.getNumericTypes());
    types.add(PrimitiveType.NULL_TYPE);

    // test on all comparison ops
    for (BinaryPredicate.Operator cmpOp : BinaryPredicate.Operator.values()) {
      for (PrimitiveType type1 : types) {
        for (PrimitiveType type2 : types) {
          PrimitiveType compatibleType =
              PrimitiveType.getAssignmentCompatibleType(type1, type2);
          typeCastTest(type1, type2, false, null, cmpOp, compatibleType);
          typeCastTest(type1, type2, true, null, cmpOp, compatibleType);
        }
      }
    }
  }

  /**
   * Generate an expr of the form "<type1> <arithmeticOp | cmpOp> <type2>"
   * and make sure that the expr has the correct type (opType for arithmetic
   * ops or bool for comparisons) and that both operands are of type 'opType'.
   * @throws AnalysisException
   */
  private void typeCastTest(PrimitiveType type1, PrimitiveType type2,
      boolean op1IsLiteral, ArithmeticExpr.Operator arithmeticOp,
      BinaryPredicate.Operator cmpOp, PrimitiveType opType) throws AnalysisException {
    Preconditions.checkState((arithmeticOp == null) != (cmpOp == null));
    boolean arithmeticMode = arithmeticOp != null;
    String op1 = "";
    if (type1 != null) {
      if (op1IsLiteral) {
        op1 = typeToLiteralValue.get(type1);
      } else {
        op1 = TestSchemaUtils.getAllTypesColumn(type1);
      }
    }
    String op2 = TestSchemaUtils.getAllTypesColumn(type2);
    String queryStr = null;
    if (arithmeticMode) {
      queryStr = "select " + op1 + " " + arithmeticOp.toString() + " " + op2 +
          " AS a from functional.alltypes";
    } else {
      queryStr = "select int_col from functional.alltypes " +
          "where " + op1 + " " + cmpOp.toString() + " " + op2;
    }
    System.err.println(queryStr);
    SelectStmt select = (SelectStmt) AnalyzesOk(queryStr);
    Expr expr = null;
    if (arithmeticMode) {
      ArrayList<Expr> selectListExprs = select.getResultExprs();
      assertNotNull(selectListExprs);
      assertEquals(selectListExprs.size(), 1);
      // check the first expr in select list
      expr = selectListExprs.get(0);
      assertEquals(opType, expr.getType());
    } else {
      // check the where clause
      expr = select.getWhereClause();
      if (!expr.getType().isNull()) {
        assertEquals(PrimitiveType.BOOLEAN, expr.getType());
      }
    }

    checkCasts(expr);
    // The children's types must be NULL or equal to the requested opType.
    Assert.assertTrue(opType == expr.getChild(0).getType()
        || opType.isNull() || expr.getChild(0).getType().isNull());
    if (type1 != null) {
      Assert.assertTrue(opType == expr.getChild(1).getType()
          || opType.isNull() || expr.getChild(1).getType().isNull());
    }
  }

  // TODO: re-enable tests as soon as we have date-related types
  // @Test
  public void DoNotTestStringLiteralToDateCasts() throws AnalysisException {
    // positive tests are included in TestComparisonTypeCasts
    AnalysisError("select int_col from functional.alltypes where date_col = 'ABCD'",
        "Unable to parse string 'ABCD' to date");
    AnalysisError("select int_col from functional.alltypes " +
        "where date_col = 'ABCD-EF-GH'",
        "Unable to parse string 'ABCD-EF-GH' to date");
    AnalysisError("select int_col from functional.alltypes where date_col = '2006'",
        "Unable to parse string '2006' to date");
    AnalysisError("select int_col from functional.alltypes where date_col = '0.5'",
        "Unable to parse string '0.5' to date");
    AnalysisError("select int_col from functional.alltypes where " +
        "date_col = '2006-10-10 ABCD'",
        "Unable to parse string '2006-10-10 ABCD' to date");
    AnalysisError("select int_col from functional.alltypes where " +
        "date_col = '2006-10-10 12:11:05.ABC'",
        "Unable to parse string '2006-10-10 12:11:05.ABC' to date");
  }

  // TODO: generate all possible error combinations of types and operands
  @Test
  public void TestFixedPointArithmeticOps() throws AnalysisException {
    // negative tests, no floating point types allowed
    AnalysisError("select ~float_col from functional.alltypes",
        "Bitwise operations only allowed on fixed-point types");
    AnalysisError("select float_col ^ int_col from functional.alltypes",
        "Invalid floating point argument to operation ^");
    AnalysisError("select float_col & int_col from functional.alltypes",
        "Invalid floating point argument to operation &");
    AnalysisError("select double_col | bigint_col from functional.alltypes",
        "Invalid floating point argument to operation |");
    AnalysisError("select int_col from functional.alltypes where " +
        "float_col & bool_col > 5",
        "Arithmetic operation requires numeric operands");
  }

  /**
   * We have three variants of timestamp arithmetic exprs, as in MySQL:
   * http://dev.mysql.com/doc/refman/5.5/en/date-and-time-functions.html
   * (section #function_date-add)
   * 1. Non-function-call like version, e.g., 'a + interval b timeunit'
   * 2. Beginning with an interval (only for '+'), e.g., 'interval b timeunit + a'
   * 3. Function-call like version, e.g., date_add(a, interval b timeunit)
   */
  @Test
  public void TestTimestampArithmeticExpressions() {
    String[] valueTypeCols =
        new String[] {"tinyint_col", "smallint_col", "int_col", "NULL"};

    // Tests all time units.
    for (TimeUnit timeUnit : TimeUnit.values()) {
      // Tests on all valid time value types (fixed points).
      for (String col : valueTypeCols) {
        // Non-function call like version.
        AnalyzesOk("select timestamp_col + interval " + col + " " + timeUnit.toString() +
            " from functional.alltypes");
        AnalyzesOk("select timestamp_col - interval " + col + " " + timeUnit.toString() +
            " from functional.alltypes");
        AnalyzesOk("select NULL - interval " + col + " " + timeUnit.toString() +
            " from functional.alltypes");
        // Reversed interval and timestamp using addition.
        AnalyzesOk("select interval " + col + " " + timeUnit.toString() +
            " + timestamp_col from functional.alltypes");
        // Function-call like version.
        AnalyzesOk("select date_add(timestamp_col, interval " + col + " " +
            timeUnit.toString() + ") from functional.alltypes");
        AnalyzesOk("select date_sub(timestamp_col, interval " + col + " " +
            timeUnit.toString() + ") from functional.alltypes");
        AnalyzesOk("select date_add(NULL, interval " + col + " " +
            timeUnit.toString() + ") from functional.alltypes");
        AnalyzesOk("select date_sub(NULL, interval " + col + " " +
            timeUnit.toString() + ") from functional.alltypes");
      }
    }

    // First operand does not return a timestamp. Non-function-call like version.
    AnalysisError("select float_col + interval 10 years from functional.alltypes",
        "Operand 'float_col' of timestamp arithmetic expression " +
        "'float_col + INTERVAL 10 years' returns type 'FLOAT'. " +
        "Expected type 'TIMESTAMP'.");
    AnalysisError("select string_col + interval 10 years from functional.alltypes",
        "Operand 'string_col' of timestamp arithmetic expression " +
        "'string_col + INTERVAL 10 years' returns type 'STRING'. " +
        "Expected type 'TIMESTAMP'.");
    // Reversed interval and timestamp using addition.
    AnalysisError("select interval 10 years + float_col from functional.alltypes",
        "Operand 'float_col' of timestamp arithmetic expression " +
        "'INTERVAL 10 years + float_col' returns type 'FLOAT'. " +
        "Expected type 'TIMESTAMP'");
    AnalysisError("select interval 10 years + string_col from functional.alltypes",
        "Operand 'string_col' of timestamp arithmetic expression " +
        "'INTERVAL 10 years + string_col' returns type 'STRING'. " +
        "Expected type 'TIMESTAMP'");
    // First operand does not return a timestamp. Function-call like version.
    AnalysisError("select date_add(float_col, interval 10 years) " +
        "from functional.alltypes",
        "Operand 'float_col' of timestamp arithmetic expression " +
        "'date_add(float_col, INTERVAL 10 years)' returns type 'FLOAT'. " +
        "Expected type 'TIMESTAMP'.");
    AnalysisError("select date_add(string_col, interval 10 years) " +
        "from functional.alltypes",
        "Operand 'string_col' of timestamp arithmetic expression " +
        "'date_add(string_col, INTERVAL 10 years)' returns type 'STRING'. " +
        "Expected type 'TIMESTAMP'.");

    // Second operand is not compatible with type INT. Non-function-call like version.
    AnalysisError("select timestamp_col + interval 5.2 years from functional.alltypes",
        "Operand '5.2' of timestamp arithmetic expression " +
        "'timestamp_col + INTERVAL 5.2 years' returns type 'DOUBLE' " +
        "which is incompatible with expected type 'INT'.");
    AnalysisError("select timestamp_col + interval bigint_col years " +
        "from functional.alltypes",
        "Operand 'bigint_col' of timestamp arithmetic expression " +
        "'timestamp_col + INTERVAL bigint_col years' returns type 'BIGINT' " +
        "which is incompatible with expected type 'INT'.");

    // No implicit cast from STRING to INT
    AnalysisError("select timestamp_col + interval '10' years from functional.alltypes",
                  "Operand ''10'' of timestamp arithmetic expression 'timestamp_col + " +
                  "INTERVAL '10' years' returns type 'STRING' which is incompatible " +
                  "with expected type 'INT'.");
    AnalysisError("select date_add(timestamp_col, interval '10' years) " +
                  "from functional.alltypes", "Operand ''10'' of timestamp arithmetic " +
                  "expression 'date_add(timestamp_col, INTERVAL '10' years)' returns " +
                  "type 'STRING' which is incompatible with expected type 'INT'.");

    // Cast from STRING to INT.
    AnalyzesOk("select timestamp_col + interval cast('10' as int) years " +
        "from functional.alltypes");
    // Reversed interval and timestamp using addition.
    AnalysisError("select interval 5.2 years + timestamp_col from functional.alltypes",
        "Operand '5.2' of timestamp arithmetic expression " +
        "'INTERVAL 5.2 years + timestamp_col' returns type 'DOUBLE' " +
        "which is incompatible with expected type 'INT'.");
    AnalysisError("select interval bigint_col years + timestamp_col " +
        "from functional.alltypes",
        "Operand 'bigint_col' of timestamp arithmetic expression " +
        "'INTERVAL bigint_col years + timestamp_col' returns type 'BIGINT' " +
        "which is incompatible with expected type 'INT'.");
    // Cast from STRING to INT.
    AnalyzesOk("select interval cast('10' as int) years + timestamp_col " +
        "from functional.alltypes");
    // Second operand is not compatible with type INT. Function-call like version.
    AnalysisError("select date_add(timestamp_col, interval 5.2 years) " +
        "from functional.alltypes",
        "Operand '5.2' of timestamp arithmetic expression " +
        "'date_add(timestamp_col, INTERVAL 5.2 years)' returns type 'DOUBLE' " +
        "which is incompatible with expected type 'INT'.");
    AnalysisError("select date_add(timestamp_col, interval bigint_col years) " +
        "from functional.alltypes",
        "Operand 'bigint_col' of timestamp arithmetic expression " +
        "'date_add(timestamp_col, INTERVAL bigint_col years)' returns type 'BIGINT' " +
        "which is incompatible with expected type 'INT'.");
    // Cast from STRING to INT.
    AnalyzesOk("select date_add(timestamp_col, interval cast('10' as int) years) " +
        " from functional.alltypes");

    // Invalid time unit. Non-function-call like version.
    AnalysisError("select timestamp_col + interval 10 error from functional.alltypes",
        "Invalid time unit 'error' in timestamp arithmetic expression " +
         "'timestamp_col + INTERVAL 10 error'.");
    AnalysisError("select timestamp_col - interval 10 error from functional.alltypes",
        "Invalid time unit 'error' in timestamp arithmetic expression " +
         "'timestamp_col - INTERVAL 10 error'.");
    // Reversed interval and timestamp using addition.
    AnalysisError("select interval 10 error + timestamp_col from functional.alltypes",
        "Invalid time unit 'error' in timestamp arithmetic expression " +
        "'INTERVAL 10 error + timestamp_col'.");
    // Invalid time unit. Function-call like version.
    AnalysisError("select date_add(timestamp_col, interval 10 error) " +
        "from functional.alltypes",
        "Invalid time unit 'error' in timestamp arithmetic expression " +
        "'date_add(timestamp_col, INTERVAL 10 error)'.");
    AnalysisError("select date_sub(timestamp_col, interval 10 error) " +
        "from functional.alltypes",
        "Invalid time unit 'error' in timestamp arithmetic expression " +
        "'date_sub(timestamp_col, INTERVAL 10 error)'.");
  }

  @Test
  public void TestNestedFunctions() throws AnalysisException {
    AnalyzesOk("select sin(pi())");
    AnalyzesOk("select sin(cos(pi()))");
    AnalyzesOk("select sin(cos(tan(e())))");
  }

  @Test
  public void TestVarArgFunctions() throws AnalysisException {
    AnalyzesOk("select concat('a')");
    AnalyzesOk("select concat('a', 'b')");
    AnalyzesOk("select concat('a', 'b', 'c')");
    AnalyzesOk("select concat('a', 'b', 'c', 'd')");
    AnalyzesOk("select concat('a', 'b', 'c', 'd', 'e')");
    // Test different vararg type signatures for same function name.
    AnalyzesOk("select coalesce(true)");
    AnalyzesOk("select coalesce(true, false, true)");
    AnalyzesOk("select coalesce(5)");
    AnalyzesOk("select coalesce(5, 6, 7)");
    AnalyzesOk("select coalesce('a')");
    AnalyzesOk("select coalesce('a', 'b', 'c')");
    // Need at least one argument.
    AnalysisError("select concat()",
                  "No matching function with those arguments: concat ()");
    AnalysisError("select coalesce()",
                  "No matching function with those arguments: coalesce ()");
  }

  /**
   * Tests that functions with NULL arguments get resolved properly,
   * and that proper errors are reported when the non-null arguments
   * cannot be cast to match a signature.
   */
  @Test
  public void TestNullFunctionArguments() {
    // Test fixed arg functions using 'substring' as representative.
    AnalyzesOk("select substring(NULL, 1, 2)");
    AnalyzesOk("select substring('a', NULL, 2)");
    AnalyzesOk("select substring('a', 1, NULL)");
    AnalyzesOk("select substring(NULL, NULL, NULL)");
    // Cannot cast non-null args to match a signature.
    AnalysisError("select substring(1, NULL, NULL)",
        "No matching function with those arguments: " +
            "substring (TINYINT, NULL_TYPE, NULL_TYPE)");
    AnalysisError("select substring(NULL, 'a', NULL)",
        "No matching function with those arguments: " +
            "substring (NULL_TYPE, STRING, NULL_TYPE)");

    // Test vararg functions with 'concat' as representative.
    AnalyzesOk("select concat(NULL, 'a', 'b')");
    AnalyzesOk("select concat('a', NULL, 'b')");
    AnalyzesOk("select concat('a', 'b', NULL)");
    AnalyzesOk("select concat(NULL, NULL, NULL)");
    // Cannot cast non-null args to match a signature.
    AnalysisError("select concat(NULL, 1, 'b')",
        "No matching function with those arguments: " +
            "concat (NULL_TYPE, TINYINT, STRING)");
    AnalysisError("select concat('a', NULL, 1)",
        "No matching function with those arguments: " +
            "concat (STRING, NULL_TYPE, TINYINT)");
    AnalysisError("select concat(1, 'b', NULL)",
        "No matching function with those arguments: " +
            "concat (TINYINT, STRING, NULL_TYPE)");
  }

  @Test
  public void TestCaseExpr() throws AnalysisException {
    // No case expr.
    AnalyzesOk("select case when 20 > 10 then 20 else 15 end");
    // No else.
    AnalyzesOk("select case when 20 > 10 then 20 end");
    // First when condition is a boolean slotref.
    AnalyzesOk("select case when bool_col then 20 else 15 end from functional.alltypes");
    // Requires casting then exprs.
    AnalyzesOk("select case when 20 > 10 then 20 when 1 > 2 then 1.0 else 15 end");
    // Requires casting then exprs.
    AnalyzesOk("select case when 20 > 10 then 20 when 1 > 2 then 1.0 " +
        "when 4 < 5 then 2 else 15 end");
    // First when expr doesn't return boolean.
    AnalysisError("select case when 20 then 20 when 1 > 2 then timestamp_col " +
        "when 4 < 5 then 2 else 15 end from functional.alltypes",
        "When expr '20' is not of type boolean and not castable to type boolean.");
    // Then exprs return incompatible types.
    AnalysisError("select case when 20 > 10 then 20 when 1 > 2 then timestamp_col " +
        "when 4 < 5 then 2 else 15 end from functional.alltypes",
        "Incompatible return types 'TINYINT' and 'TIMESTAMP' " +
         "of exprs '20' and 'timestamp_col'.");

    // With case expr.
    AnalyzesOk("select case int_col when 20 then 30 else 15 end " +
        "from functional.alltypes");
    // No else.
    AnalyzesOk("select case int_col when 20 then 30 end " +
        "from functional.alltypes");
    // Requires casting case expr.
    AnalyzesOk("select case int_col when bigint_col then 30 else 15 end " +
        "from functional.alltypes");
    // Requires casting when expr.
    AnalyzesOk("select case bigint_col when int_col then 30 else 15 end " +
        "from functional.alltypes");
    // Requires multiple casts.
    AnalyzesOk("select case bigint_col when int_col then 30 " +
        "when double_col then 1.0 else 15 end from functional.alltypes");
    // Type of case expr is incompatible with first when expr.
    AnalysisError("select case bigint_col when timestamp_col then 30 " +
        "when double_col then 1.0 else 15 end from functional.alltypes",
        "Incompatible return types 'BIGINT' and 'TIMESTAMP' " +
        "of exprs 'bigint_col' and 'timestamp_col'.");
    // Then exprs return incompatible types.
    AnalysisError("select case bigint_col when int_col then 30 " +
        "when double_col then timestamp_col else 15 end from functional.alltypes",
        "Incompatible return types 'TINYINT' and 'TIMESTAMP' " +
         "of exprs '30' and 'timestamp_col'.");

    // Test different type classes (all types are tested in BE tests).
    AnalyzesOk("select case when true then 1 end");
    AnalyzesOk("select case when true then 1.0 end");
    AnalyzesOk("select case when true then 'abc' end");
    AnalyzesOk("select case when true then cast('2011-01-01 09:01:01' " +
        "as timestamp) end");
    // Test NULLs.
    AnalyzesOk("select case NULL when 1 then 2 else 3 end");
    AnalyzesOk("select case 1 when NULL then 2 else 3 end");
    AnalyzesOk("select case 1 when 2 then NULL else 3 end");
    AnalyzesOk("select case 1 when 2 then 3 else NULL end");
    AnalyzesOk("select case NULL when NULL then NULL else NULL end");
  }

  @Test
  public void TestConditionalExprs() {
    // Test IF conditional expr.
    AnalyzesOk("select if(true, false, false)");
    AnalyzesOk("select if(1 != 2, false, false)");
    AnalyzesOk("select if(bool_col, false, true) from functional.alltypes");
    AnalyzesOk("select if(bool_col, int_col, double_col) from functional.alltypes");
    // Test NULLs.
    AnalyzesOk("select if(NULL, false, true) from functional.alltypes");
    AnalyzesOk("select if(bool_col, NULL, true) from functional.alltypes");
    AnalyzesOk("select if(bool_col, false, NULL) from functional.alltypes");
    AnalyzesOk("select if(NULL, NULL, NULL) from functional.alltypes");

    // if() only accepts three arguments
    AnalysisError("select if(true, false, true, true)",
        "No matching function with those arguments: if (BOOLEAN, BOOLEAN, BOOLEAN, " +
        "BOOLEAN)");
    AnalysisError("select if(true, false)",
        "No matching function with those arguments: if (BOOLEAN, BOOLEAN)");
    AnalysisError("select if(false)",
        "No matching function with those arguments: if (BOOLEAN)");

    // Test IsNull() conditional function.
    for (PrimitiveType t: PrimitiveType.values()) {
      String literal = typeToLiteralValue.get(t);
      AnalyzesOk(String.format("select isnull(%s, %s)", literal, literal));
      AnalyzesOk(String.format("select isnull(%s, NULL)", literal));
      AnalyzesOk(String.format("select isnull(NULL, %s)", literal));
    }
    // IsNull() requires two arguments.
    AnalysisError("select isnull(1)",
        "No matching function with those arguments: isnull (TINYINT)");
    AnalysisError("select isnull(1, 2, 3)",
        "No matching function with those arguments: isnull (TINYINT, TINYINT, TINYINT)");
    // Incompatible types.
    AnalysisError("select isnull('a', true)",
        "No matching function with those arguments: isnull (STRING, BOOLEAN)");
  }

  @Test
  public void TestUnion() {
    // Selects on different tables.
    AnalyzesOk("select int_col from functional.alltypes union " +
        "select int_col from functional.alltypessmall");
    // Selects on same table without aliases.
    AnalyzesOk("select int_col from functional.alltypes union " +
        "select int_col from functional.alltypes");
    // Longer union chain.
    AnalyzesOk("select int_col from functional.alltypes union " +
        "select int_col from functional.alltypes " +
        "union select int_col from functional.alltypes union " +
        "select int_col from functional.alltypes");
    // All columns, perfectly compatible.
    AnalyzesOk("select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year," +
        "month from functional.alltypes union " +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year," +
        "month from functional.alltypes");
    // Make sure table aliases aren't visible across union operands.
    AnalyzesOk("select a.smallint_col from functional.alltypes a " +
        "union select a.int_col from functional.alltypessmall a");
    // All columns compatible with NULL.
    AnalyzesOk("select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year," +
        "month from functional.alltypes union " +
        "select NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL, NULL," +
        "NULL from functional.alltypes");

    // No from clause. Has literals and NULLs. Requires implicit casts.
    AnalyzesOk("select 1, 2, 3 " +
        "union select NULL, NULL, NULL " +
        "union select 1.0, NULL, 3 " +
        "union select NULL, 10, NULL");
    // Implicit casts on integer types.
    AnalyzesOk("select tinyint_col from functional.alltypes " +
        "union select smallint_col from functional.alltypes " +
        "union select int_col from functional.alltypes " +
        "union select bigint_col from functional.alltypes");
    // Implicit casts on float types.
    AnalyzesOk("select float_col from functional.alltypes union " +
        "select double_col from functional.alltypes");
    // Implicit casts on all numeric types with two columns from each select.
    AnalyzesOk("select tinyint_col, double_col from functional.alltypes " +
        "union select smallint_col, float_col from functional.alltypes " +
        "union select int_col, bigint_col from functional.alltypes " +
        "union select bigint_col, int_col from functional.alltypes " +
        "union select float_col, smallint_col from functional.alltypes " +
        "union select double_col, tinyint_col from functional.alltypes");

    // With order by and limit.
    AnalyzesOk("(select int_col from functional.alltypes) " +
        "union (select tinyint_col from functional.alltypessmall) " +
        "order by int_col limit 1");
    // Bigger order by.
    AnalyzesOk("(select tinyint_col, double_col from functional.alltypes) " +
        "union (select smallint_col, float_col from functional.alltypes) " +
        "union (select int_col, bigint_col from functional.alltypes) " +
        "union (select bigint_col, int_col from functional.alltypes) " +
        "order by double_col, tinyint_col");
    // Bigger order by with ordinals.
    AnalyzesOk("(select tinyint_col, double_col from functional.alltypes) " +
        "union (select smallint_col, float_col from functional.alltypes) " +
        "union (select int_col, bigint_col from functional.alltypes) " +
        "union (select bigint_col, int_col from functional.alltypes) " +
        "order by 2, 1");

    // Unequal number of columns.
    AnalysisError("select int_col from functional.alltypes " +
        "union select int_col, float_col from functional.alltypes",
        "Operands have unequal number of columns:\n" +
        "'SELECT int_col FROM functional.alltypes' has 1 column(s)\n" +
        "'SELECT int_col, float_col FROM functional.alltypes' has 2 column(s)");
    // Unequal number of columns, longer union chain.
    AnalysisError("select int_col from functional.alltypes " +
        "union select tinyint_col from functional.alltypes " +
        "union select smallint_col from functional.alltypes " +
        "union select smallint_col, bigint_col from functional.alltypes",
        "Operands have unequal number of columns:\n" +
        "'SELECT int_col FROM functional.alltypes' has 1 column(s)\n" +
        "'SELECT smallint_col, bigint_col FROM functional.alltypes' has 2 column(s)");
    // Incompatible types.
    AnalysisError("select bool_col from functional.alltypes " +
        "union select string_col from functional.alltypes",
        "Incompatible return types 'BOOLEAN' and 'STRING' " +
            "of exprs 'bool_col' and 'string_col'.");
    // Incompatible types, longer union chain.
    AnalysisError("select int_col, string_col from functional.alltypes " +
        "union select tinyint_col, bool_col from functional.alltypes " +
        "union select smallint_col, int_col from functional.alltypes " +
        "union select smallint_col, bool_col from functional.alltypes",
        "Incompatible return types 'STRING' and 'BOOLEAN' of " +
            "exprs 'string_col' and 'bool_col'.");
    // Invalid ordinal in order by.
    AnalysisError("(select int_col from functional.alltypes) " +
        "union (select int_col from functional.alltypessmall) order by 2",
        "ORDER BY: ordinal exceeds number of items in select list: 2");
    // Ambiguous order by.
    AnalysisError("(select int_col a, string_col a from functional.alltypes) " +
        "union (select int_col a, string_col a " +
        "from functional.alltypessmall) order by a",
        "Column a in order clause is ambiguous");
    // Ambiguous alias in the second union operand should work.
    AnalyzesOk("(select int_col a, string_col b from functional.alltypes) " +
        "union (select int_col a, string_col a " +
        "from functional.alltypessmall) order by a");

    // Column labels are inherited from first select block.
    // Order by references an invalid column
    AnalysisError("(select smallint_col from functional.alltypes) " +
        "union (select int_col from functional.alltypessmall) order by int_col",
        "couldn't resolve column reference: 'int_col'");
    // Make sure table aliases aren't visible across union operands.
    AnalysisError("select a.smallint_col from functional.alltypes a " +
        "union select a.int_col from functional.alltypessmall",
        "unknown table alias: 'a'");
  }

  @Test
  public void TestValuesStmt() throws AnalysisException {
    // Values stmt with a single row.
    AnalyzesOk("values(1, 2, 3)");
    AnalyzesOk("select * from (values('a', NULL, 'c')) as t");
    AnalyzesOk("values(1.0, 2, NULL) union all values(1, 2.0, 3)");
    AnalyzesOk("insert overwrite table functional.alltypes " +
        "partition (year=2009, month=10)" +
        "values(1, true, 1, 1, 1, 1, 1.0, 1.0, 'a', 'a', cast(0 as timestamp))");
    AnalyzesOk("insert overwrite table functional.alltypes " +
        "partition (year, month) " +
        "values(1, true, 1, 1, 1, 1, 1.0, 1.0, 'a', 'a', cast(0 as timestamp)," +
        "2009, 10)");
    // Values stmt with multiple rows.
    AnalyzesOk("values((1, 2, 3), (4, 5, 6))");
    AnalyzesOk("select * from (values('a', 'b', 'c')) as t");
    AnalyzesOk("select * from (values(('a', 'b', 'c'), ('d', 'e', 'f'))) as t");
    AnalyzesOk("values((1.0, 2, NULL), (2.0, 3, 4)) union all values(1, 2.0, 3)");
    AnalyzesOk("insert overwrite table functional.alltypes " +
        "partition (year=2009, month=10) " +
        "values(" +
        "(1, true, 1, 1, 1, 1, 1.0, 1.0, 'a', 'a', cast(0 as timestamp))," +
        "(2, false, 2, 2, NULL, 2, 2.0, 2.0, 'b', 'b', cast(0 as timestamp))," +
        "(3, true, 3, 3, 3, 3, 3.0, 3.0, 'c', 'c', cast(0 as timestamp)))");
    AnalyzesOk("insert overwrite table functional.alltypes " +
        "partition (year, month) " +
        "values(" +
        "(1, true, 1, 1, 1, 1, 1.0, 1.0, 'a', 'a', cast(0 as timestamp), 2009, 10)," +
        "(2, false, 2, 2, NULL, 2, 2.0, 2.0, 'b', 'b', cast(0 as timestamp), 2009, 2)," +
        "(3, true, 3, 3, 3, 3, 3.0, 3.0, 'c', 'c', cast(0 as timestamp), 2009, 3))");
    // Test multiple aliases. Values() is like union, the column labels are 'x' and 'y'.
    AnalyzesOk("values((1 as x, 'a' as y), (2 as k, 'b' as j))");
    // Test order by and limit.
    AnalyzesOk("values(1 as x, 'a') order by 2 limit 10");
    AnalyzesOk("values(1 as x, 'a' as y), (2, 'b') order by y limit 10");
    AnalyzesOk("values((1, 'a'), (2, 'b')) order by 1 limit 10");

    AnalysisError("values(1, 'a', 1.0, *)",
    		"'*' expression in select list requires FROM clause.");
    AnalysisError("values(sum(1), 'a', 1.0)",
        "aggregation without a FROM clause is not allowed");
    AnalysisError("values(1, id, 2)",
        "couldn't resolve column reference: 'id'");
    AnalysisError("values((1 as x, 'a' as y), (2, 'b')) order by c limit 1",
        "couldn't resolve column reference: 'c'");
    AnalysisError("values((1, 2), (3, 4, 5))",
        "Operands have unequal number of columns:\n" +
        "'(1, 2)' has 2 column(s)\n" +
        "'(3, 4, 5)' has 3 column(s)");
    AnalysisError("values((1, 'a'), (3, 4))",
        "Incompatible return types 'STRING' and 'TINYINT' of exprs ''a'' and '4'");
    AnalysisError("insert overwrite table functional.alltypes " +
        "partition (year, month) " +
        "values(1, true, 'a', 1, 1, 1, 1.0, 1.0, 'a', 'a', cast(0 as timestamp)," +
        "2009, 10)", "Target table 'alltypes' and result of select statement are not " +
        "union compatible.\nIncompatible types 'TINYINT' and 'STRING' in column " +
        "'<slot 2>'.");
  }

  @Test
  public void TestAlterTableAddDropPartition() throws AnalysisException {
    String[] addDrop = {"add if not exists", "drop if exists"};
    for (String kw: addDrop) {
      // Add different partitions for different column types
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(year=2050, month=10)");
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(month=10, year=2050)");
      AnalyzesOk("alter table functional.insert_string_partitioned " + kw +
          " partition(s2='1234')");

      // Can't add/drop partitions to/from unpartitioned tables
      AnalysisError("alter table functional.alltypesnopart " + kw + " partition (i=1)",
          "Table is not partitioned: functional.alltypesnopart");
      AnalysisError("alter table functional.hbasealltypesagg " + kw +
          " partition (i=1)", "Table is not partitioned: functional.hbasealltypesagg");

      // Duplicate partition key name
      AnalysisError("alter table functional.alltypes " + kw +
          " partition(year=2050, year=2051)", "Duplicate partition key name: year");
      // Not a partition column
      AnalysisError("alter table functional.alltypes " + kw +
          " partition(year=2050, int_col=1)",
          "Column 'int_col' is not a partition column in table: functional.alltypes");

      // NULL partition keys
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(year=NULL, month=1)");
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(year=NULL, month=NULL)");
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(year=ascii(null), month=ascii(NULL))");
      // Empty string partition keys
      AnalyzesOk("alter table functional.insert_string_partitioned " + kw +
          " partition(s2='')");
      // Arbitrary exprs as partition key values. Constant exprs are ok.
      AnalyzesOk("alter table functional.alltypes " + kw +
          " partition(year=-1, month=cast((10+5*4) as INT))");

      // Arbitrary exprs as partition key values. Non-constant exprs should fail.
      AnalysisError("alter table functional.alltypes " + kw +
          " partition(year=2050, month=int_col)",
          "Non-constant expressions are not supported as static partition-key values " +
          "in 'month=int_col'.");
      AnalysisError("alter table functional.alltypes " + kw +
          " partition(year=cast(int_col as int), month=12)",
          "Non-constant expressions are not supported as static partition-key values " +
          "in 'year=CAST(int_col AS INT)'.");

      // Not a valid column
      AnalysisError("alter table functional.alltypes " + kw +
          " partition(year=2050, blah=1)",
          "Partition column 'blah' not found in table: functional.alltypes");

      // Data types don't match
      AnalysisError(
          "alter table functional.insert_string_partitioned " + kw +
          " partition(s2=1234)",
          "Target table not compatible.\nIncompatible types 'STRING' and 'SMALLINT' " +
          "in column 's2'");

      // Loss of precision
      AnalysisError(
          "alter table functional.alltypes " + kw +
          " partition(year=100000000000, month=10)",
          "Partition key value may result in loss of precision.\nWould need to cast" +
          " '100000000000' to 'INT' for partition column: year");


      // Table/Db does not exist
      AnalysisError("alter table db_does_not_exist.alltypes " + kw +
          " partition (i=1)", "Unknown database: db_does_not_exist");
      AnalysisError("alter table functional.table_does_not_exist " + kw +
          " partition (i=1)", "Unknown table: functional.table_does_not_exist");
    }

    // IF NOT EXISTS properly checks for partition existence
    AnalyzesOk("alter table functional.alltypes add " +
          "partition(year=2050, month=10)");
    AnalysisError("alter table functional.alltypes add " +
          "partition(year=2010, month=10)",
          "Partition spec already exists: (year=2010, month=10).");
    AnalyzesOk("alter table functional.alltypes add if not exists " +
          " partition(year=2010, month=10)");

    // IF EXISTS properly checks for partition existence
    AnalyzesOk("alter table functional.alltypes drop " +
          "partition(year=2010, month=10)");
    AnalysisError("alter table functional.alltypes drop " +
          "partition(year=2050, month=10)",
          "Partition spec does not exist: (year=2050, month=10).");
    AnalyzesOk("alter table functional.alltypes drop if exists " +
          "partition(year=2050, month=10)");
  }

  @Test
  public void TestAlterTableAddReplaceColumns() throws AnalysisException {
    AnalyzesOk("alter table functional.alltypes add columns (new_col int)");
    AnalyzesOk("alter table functional.alltypes add columns (c1 string comment 'hi')");
    AnalyzesOk(
        "alter table functional.alltypes replace columns (c1 int comment 'c', c2 int)");

    // Column name must be unique for add
    AnalysisError("alter table functional.alltypes add columns (int_col int)",
        "Column already exists: int_col");
    // Add a column with same name as a partition column
    AnalysisError("alter table functional.alltypes add columns (year int)",
        "Column name conflicts with existing partition column: year");

    // Replace should not throw an error if the column already exists
    AnalyzesOk("alter table functional.alltypes replace columns (int_col int)");
    // It is not possible to replace a partition column
    AnalysisError("alter table functional.alltypes replace columns (Year int)",
        "Column name conflicts with existing partition column: year");

    // Duplicate column names
    AnalysisError("alter table functional.alltypes add columns (c1 int, c1 int)",
        "Duplicate column name: c1");

    AnalysisError("alter table functional.alltypes replace columns (c1 int, C1 int)",
        "Duplicate column name: c1");

    // Table/Db does not exist
    AnalysisError("alter table db_does_not_exist.alltypes add columns (i int)",
        "Unknown database: db_does_not_exist");
    AnalysisError("alter table functional.table_does_not_exist add columns (i int)",
        "Unknown table: functional.table_does_not_exist");
  }

  @Test
  public void TestAlterTableDropColumn() throws AnalysisException {
    AnalyzesOk("alter table functional.alltypes drop column int_col");

    AnalysisError("alter table functional.alltypes drop column no_col",
        "Column 'no_col' does not exist in table: functional.alltypes");

    AnalysisError("alter table functional.alltypes drop column year",
        "Cannot drop partition column: year");

    // Tables should always have at least 1 column
    AnalysisError("alter table functional_seq_snap.bad_seq_snap drop column field",
        "Cannot drop column 'field' from functional_seq_snap.bad_seq_snap. " +
        "Tables must contain at least 1 column.");

    // Table/Db does not exist
    AnalysisError("alter table db_does_not_exist.alltypes drop column col1",
        "Unknown database: db_does_not_exist");
    AnalysisError("alter table functional.table_does_not_exist drop column col1",
        "Unknown table: functional.table_does_not_exist");
  }

  @Test
  public void TestAlterTableChangeColumn() throws AnalysisException {
    // Rename a column
    AnalyzesOk("alter table functional.alltypes change column int_col int_col2 int");
    // Rename and change the datatype
    AnalyzesOk("alter table functional.alltypes change column int_col c2 string");
    // Change only the datatype
    AnalyzesOk("alter table functional.alltypes change column int_col int_col tinyint");
    // Add a comment to a column.
    AnalyzesOk("alter table functional.alltypes change int_col int_col int comment 'c'");

    AnalysisError("alter table functional.alltypes change column no_col c1 int",
        "Column 'no_col' does not exist in table: functional.alltypes");

    AnalysisError("alter table functional.alltypes change column year year int",
        "Cannot modify partition column: year");

    AnalysisError("alter table functional.alltypes change column int_col Tinyint_col int",
        "Column already exists: Tinyint_col");

    // Table/Db does not exist
    AnalysisError("alter table db_does_not_exist.alltypes change c1 c2 int",
        "Unknown database: db_does_not_exist");
    AnalysisError("alter table functional.table_does_not_exist change c1 c2 double",
        "Unknown table: functional.table_does_not_exist");
  }

  @Test
  public void TestAlterTableSet() throws AnalysisException {
    AnalyzesOk("alter table functional.alltypes set fileformat sequencefile");
    AnalyzesOk("alter table functional.alltypes set location '/a/b'");
    AnalyzesOk("alter table functional.alltypes PARTITION (Year=2010, month=11) " +
               "set location '/a/b'");
    AnalyzesOk("alter table functional.alltypes PARTITION (month=11, year=2010) " +
               "set fileformat parquetfile");
    AnalyzesOk("alter table functional.stringpartitionkey PARTITION " +
               "(string_col='partition1') set fileformat parquetfile");
    AnalyzesOk("alter table functional.stringpartitionkey PARTITION " +
               "(string_col='PaRtiTion1') set location '/a/b/c'");
    // Arbitrary exprs as partition key values. Constant exprs are ok.
    AnalyzesOk("alter table functional.alltypes PARTITION " +
               "(year=cast(100*20+10 as INT), month=cast(2+9 as INT)) " +
               "set fileformat sequencefile");
    AnalyzesOk("alter table functional.alltypes PARTITION " +
               "(year=cast(100*20+10 as INT), month=cast(2+9 as INT)) " +
               "set location '/a/b'");
    // Arbitrary exprs as partition key values. Non-constant exprs should fail.
    AnalysisError("alter table functional.alltypes PARTITION " +
                  "(Year=2050, month=int_col) set fileformat sequencefile",
                  "Non-constant expressions are not supported as static partition-key " +
                  "values in 'month=int_col'.");
    AnalysisError("alter table functional.alltypes PARTITION " +
                  "(Year=2050, month=int_col) set location '/a/b'",
                  "Non-constant expressions are not supported as static partition-key " +
                  "values in 'month=int_col'.");

    // Partition spec does not exist
    AnalysisError("alter table functional.alltypes PARTITION (year=2014, month=11) " +
                  "set location '/a/b'",
                  "No matching partition spec found: (year=2014, month=11)");
    AnalysisError("alter table functional.alltypes PARTITION (year=2010, year=2010) " +
                  "set location '/a/b'",
                  "No matching partition spec found: (year=2010, year=2010)");
    AnalysisError("alter table functional.alltypes PARTITION (month=11, year=2014) " +
                  "set fileformat sequencefile",
                  "No matching partition spec found: (month=11, year=2014)");
    AnalysisError("alter table functional.alltypesnopart PARTITION (month=1) " +
                  "set fileformat sequencefile",
                  "Table is not partitioned: functional.alltypesnopart");
    AnalysisError("alter table functional.alltypesnopart PARTITION (month=1) " +
                  "set location '/a/b/c'",
                  "Table is not partitioned: functional.alltypesnopart");
    AnalysisError("alter table functional.stringpartitionkey PARTITION " +
                  "(string_col='partition2') set location '/a/b'",
                  "No matching partition spec found: (string_col='partition2')");
    AnalysisError("alter table functional.stringpartitionkey PARTITION " +
                  "(string_col='partition2') set fileformat sequencefile",
                  "No matching partition spec found: (string_col='partition2')");
    AnalysisError("alter table functional.alltypes PARTITION " +
                 "(year=cast(10*20+10 as INT), month=cast(5*3 as INT)) " +
                  "set location '/a/b'",
                  "No matching partition spec found: " +
                  "(year=CAST(10 * 20 + 10 AS INT), month=CAST(5 * 3 AS INT))");
    AnalysisError("alter table functional.alltypes PARTITION " +
                  "(year=cast(10*20+10 as INT), month=cast(5*3 as INT)) " +
                  "set fileformat sequencefile",
                  "No matching partition spec found: " +
                  "(year=CAST(10 * 20 + 10 AS INT), month=CAST(5 * 3 AS INT))");

    // Table/Db does not exist
    AnalysisError("alter table db_does_not_exist.alltypes set fileformat sequencefile",
        "Unknown database: db_does_not_exist");
    AnalysisError("alter table functional.table_does_not_exist set fileformat rcfile",
        "Unknown table: functional.table_does_not_exist");
    AnalysisError("alter table db_does_not_exist.alltypes set location '/a/b'",
        "Unknown database: db_does_not_exist");
    AnalysisError("alter table functional.table_does_not_exist set location '/a/b'",
        "Unknown table: functional.table_does_not_exist");
    AnalysisError("alter table functional.no_tbl partition(i=1) set location '/a/b'",
        "Unknown table: functional.no_tbl");
    AnalysisError("alter table no_db.alltypes partition(i=1) set fileformat textfile",
        "Unknown database: no_db");
  }

  @Test
  public void TestAlterTableRename() throws AnalysisException {
    AnalyzesOk("alter table functional.alltypes rename to new_alltypes");
    AnalyzesOk("alter table functional.alltypes rename to functional.new_alltypes");
    AnalysisError("alter table functional.alltypes rename to functional.alltypes",
        "Table already exists: functional.alltypes");
    AnalysisError("alter table functional.alltypes rename to functional.alltypesagg",
        "Table already exists: functional.alltypesagg");

    AnalysisError("alter table functional.table_does_not_exist rename to new_table",
        "Unknown table: functional.table_does_not_exist");
    AnalysisError("alter table db_does_not_exist.alltypes rename to new_table",
        "Unknown database: db_does_not_exist");

    AnalysisError("alter table functional.alltypes rename to db_does_not_exist.new_table",
        "Unknown database: db_does_not_exist");
  }

  @Test
  public void TestDrop() throws AnalysisException {
    AnalyzesOk("drop database functional");
    AnalyzesOk("drop table functional.alltypes");

    // If the database does not exist, and the user hasn't specified "IF EXISTS",
    // an analysis error should be thrown.
    AnalysisError("drop database db_does_not_exist",
        "Unknown database: db_does_not_exist");
    AnalysisError("drop table db_does_not_exist.alltypes",
        "Unknown database: db_does_not_exist");

    // No error is thrown if the user specifies IF EXISTS
    AnalyzesOk("drop database if exists db_does_not_exist");
    AnalyzesOk("drop table if exists db_does_not_exist.alltypes");
  }

  @Test
  public void TestCreateDb() throws AnalysisException {
    AnalyzesOk("create database some_new_database");
    AnalysisError("create database functional", "Database already exists: functional");
    AnalyzesOk("create database if not exists functional");
  }

  @Test
  public void TestCreateTable() throws AnalysisException {
    AnalyzesOk("create table functional.new_table (i int)");
    AnalyzesOk("create table if not exists functional.alltypes (i int)");
    AnalyzesOk("create table if not exists functional.new_tbl like functional.alltypes");
    AnalyzesOk("create table if not exists functional.alltypes like functional.alltypes");
    AnalysisError("create table functional.alltypes like functional.alltypes",
        "Table already exists: functional.alltypes");
    AnalysisError("create table functional.new_table like functional.tbl_does_not_exist",
        "Source table does not exist: functional.tbl_does_not_exist");
    AnalysisError("create table functional.new_table like db_does_not_exist.alltypes",
        "Database does not exist: db_does_not_exist");
    AnalysisError("create table functional.alltypes (i int)",
        "Table already exists: functional.alltypes");
    AnalyzesOk("create table functional.new_table (i int) row format delimited fields " +
        "terminated by '|'");

    // Note: Backslashes need to be escaped twice - once for Java and once for Impala.
    // For example, if this were a real query the value '\' would be stored in the
    // metastore for the ESCAPED BY field.
    AnalyzesOk("create table functional.new_table (i int) row format delimited fields " +
        "terminated by '\\t' escaped by '\\\\' lines terminated by '\\n'");

    AnalysisError("create table functional.new_table (i int) row format delimited " +
        "fields terminated by '||' escaped by '\\\\' lines terminated by '\\n'",
        "ESCAPED BY values and LINE/FIELD terminators must have length of 1: ||");

    AnalysisError("create table db_does_not_exist.new_table (i int)",
        "Database does not exist: db_does_not_exist");
    AnalysisError("create table new_table (i int, I string)",
        "Duplicate column name: I");
    AnalysisError("create table new_table (c1 double, col2 int, c1 double, c4 string)",
        "Duplicate column name: c1");
    AnalysisError("create table new_table (i int, s string) PARTITIONED BY (i int)",
        "Duplicate column name: i");
    AnalysisError("create table new_table (i int) PARTITIONED BY (C int, c2 int, c int)",
        "Duplicate column name: c");

    // Unsupported partition-column types.
    AnalysisError("create table new_table (i int) PARTITIONED BY (t timestamp)",
        "Type 'TIMESTAMP' is not supported as partition-column type in column: t");
    AnalysisError("create table new_table (i int) PARTITIONED BY (d date)",
        "Type 'DATE' is not supported as partition-column type in column: d");
    AnalysisError("create table new_table (i int) PARTITIONED BY (d datetime)",
        "Type 'DATETIME' is not supported as partition-column type in column: d");
  }

  @Test
  public void TestUseDb() throws AnalysisException {
    AnalyzesOk("use functional");
    AnalysisError("use db_does_not_exist", "Database does not exist: db_does_not_exist");
  }

  @Test
  public void TestInsert() throws AnalysisException {
    for (String qualifier: ImmutableList.of("INTO", "OVERWRITE")) {
      testInsertStatic(qualifier);
      testInsertDynamic(qualifier);
      testInsertUnpartitioned(qualifier);
    }
  }

  /**
   * Run tests for dynamic partitions for INSERT INTO/OVERWRITE:
   */
  private void testInsertDynamic(String qualifier) throws AnalysisException {
    // Fully dynamic partitions.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year, " +
        "month from functional.alltypes");
    // Fully dynamic partitions with NULL literals as partitioning columns.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, " +
        "string_col, timestamp_col, NULL, NULL from functional.alltypes");
    // Fully dynamic partitions with NULL partition keys and column values.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month)" +
        "select NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL from functional.alltypes");
    // Fully dynamic partitions. Order of corresponding select list items doesn't matter,
    // as long as they appear at the very end of the select list.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, month, " +
        "year from functional.alltypes");
    // Partially dynamic partitions.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, month " +
        "from functional.alltypes");
    // Partially dynamic partitions with NULL static partition key value.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year from " +
        "functional.alltypes");
    // Partially dynamic partitions.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year from " +
        "functional.alltypes");
    // Partially dynamic partitions with NULL static partition key value.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month=NULL)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, year from " +
        "functional.alltypes");
    // Partially dynamic partitions with NULL literal as column.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, NULL from " +
        "functional.alltypes");
    // Partially dynamic partitions with NULL literal as column.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col, NULL from " +
        "functional.alltypes");
    // Partially dynamic partitions with NULL literal in partition clause.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "Partition (year=2009, month=NULL)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Partially dynamic partitions with NULL literal in partition clause.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");

    // Select '*' includes partitioning columns at the end.
    AnalyzesOk("insert " + qualifier +
        " table functional.alltypessmall partition (year, month)" +
        "select * from functional.alltypes");
    // No corresponding select list items of fully dynamic partitions.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "No matching select list item found for dynamic partition 'year'.\n" +
            "The select list items corresponding to dynamic partition keys " +
            "must be at the end of the select list.");
    // No corresponding select list items of partially dynamic partitions.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "No matching select list item found for dynamic partition 'month'.\n" +
            "The select list items corresponding to dynamic partition keys " +
            "must be at the end of the select list.");
    // No corresponding select list items of partially dynamic partitions.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "No matching select list item found for dynamic partition 'year'.\n" +
            "The select list items corresponding to dynamic partition keys " +
            "must be at the end of the select list.");
    // Select '*' includes partitioning columns, and hence, is not union compatible.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select * from functional.alltypes",
        "Target table 'alltypessmall' and result of select statement are not union " +
            "compatible.\n" +
            "Target table expects 11 columns but the select statement returns 13.");
  }

  /**
   * Tests for inserting into unpartitioned tables
   */
  private void testInsertUnpartitioned(String qualifier) throws AnalysisException {
    // Wrong number of columns.
    AnalysisError("insert " + qualifier + " table functional.alltypesnopart " +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col from functional.alltypes");
    // Wrong number of columns.
    AnalysisError("INSERT " + qualifier +
        " TABLE functional.hbasealltypesagg SELECT * FROM functional.alltypesagg");
    // Unpartitioned table without partition clause.
    AnalyzesOk("insert " + qualifier + " table functional.alltypesnopart " +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col from " +
        "functional.alltypes");
    // All NULL column values.
    AnalyzesOk("insert " + qualifier + " table functional.alltypesnopart " +
        "select NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL " +
        "from functional.alltypes");

    String hbaseQuery =  "INSERT " + qualifier + " TABLE " +
        "functional.hbaseinsertalltypesagg select id, bigint_col, bool_col, " +
        "date_string_col, double_col, float_col, int_col, smallint_col, " +
        "string_col, timestamp_col, tinyint_col from functional.alltypesagg";

    // HBase doesn't support OVERWRITE so error out if the query is
    // trying to do that.
    if (!qualifier.contains("OVERWRITE")) {
      AnalyzesOk(hbaseQuery);
    } else {
      AnalysisError(hbaseQuery);
    }

    // Unpartitioned table with partition clause
    AnalysisError("INSERT " + qualifier +
        " TABLE functional.alltypesnopart PARTITION(year=2009) " +
        "SELECT * FROM functional.alltypes", "PARTITION clause is only valid for INSERT" +
        " into partitioned table. 'alltypesnopart' is not partitioned");

    // Unknown target DB
    AnalysisError("INSERT " + qualifier +
       " table UNKNOWNDB.alltypesnopart SELECT * from functional.alltypesnopart");
  }

  /**
   * Run general tests and tests using static partitions for INSERT INTO/OVERWRITE:
   */
  private void testInsertStatic(String qualifier) throws AnalysisException {
    // Static partition.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");

    // Static partition with NULL partition keys
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month=NULL)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Static partition with NULL column values
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month=NULL)" +
        "select NULL, NULL, NULL, NULL, NULL, NULL, " +
        "NULL, NULL, NULL, NULL, NULL " +
        "from functional.alltypes");
    // Static partition with NULL partition keys.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month=NULL)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Static partition with partial NULL partition keys.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=NULL)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Static partition with partial NULL partition keys.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=NULL, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Arbitrary exprs as partition key values. Constant exprs are ok.
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=-1, month=cast(100*20+10 as INT))" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");

    // Union compatibility requires cast of select list expr in column 5
    // (int_col -> bigint).
    AnalyzesOk("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, int_col, " +
        "float_col, float_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // No partition clause given for partitioned table.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "No PARTITION clause given for INSERT into partitioned table 'alltypessmall'.");
    // Not union compatible, unequal number of columns.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, timestamp_col from functional.alltypes",
        "Target table 'alltypessmall' and result of select statement are not union " +
        "compatible.\n" +
        "Target table expects 11 columns but the select statement returns 10.");
    // Not union compatible, incompatible type in last column (bool_col -> string).
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, bool_col, timestamp_col " +
        "from functional.alltypes",
        "Target table 'alltypessmall' and result of select statement are not union " +
        "compatible.\n" +
        "Incompatible types 'STRING' and 'BOOLEAN' in column 'bool_col'.");
    // Too many partitioning columns.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4, year=10)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Superfluous columns in PARTITION clause: year.");
    // Too few partitioning columns.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Missing partition column 'month' from PARTITION clause.");
    // Non-partitioning column in partition clause.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, bigint_col=10)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Missing partition column 'month' from PARTITION clause.");
    // Loss of precision when casting in column 6 (double_col -> float).
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "double_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Inserting into target table 'alltypessmall' may result in loss of precision.\n" +
        "Would need to cast 'double_col' to 'FLOAT'.");
    // Select '*' includes partitioning columns, and hence, is not union compatible.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select * from functional.alltypes",
        "Target table 'alltypessmall' and result of select statement are not union " +
        "compatible.\n" +
        "Target table expects 11 columns but the select statement returns 13.");

    // Partition columns should be type-checked
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=\"should be an int\", month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
    // Arbitrary exprs as partition key values. Non-constant exprs should fail.
    AnalysisError("insert " + qualifier + " table functional.alltypessmall " +
        "partition (year=-1, month=int_col)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Non-constant expressions are not supported as static partition-key values " +
        "in 'month=int_col'.");
  }

  @Test
  public void testUseStatement() {
    Assert.assertTrue(AnalyzesOk("USE functional") instanceof UseStmt);
  }

  /**
   * We distinguish between three classes of unsupported types:
   * 1. Complex types, e.g., map
   *    For tables with such types we prevent loading the table metadata.
   * 2. Primitive types
   *    For tables with unsupported primitive types (e.g., decimal)
   *    we can run queries as long as the unsupported columns are not referenced.
   *    We fail analysis if a query references an unsupported primitive column.
   * 3. Partition-column types
   *    We do not support table partitioning on timestamp columns
   */
  @Test
  public void TestUnsupportedTypes() {
    // The table metadata should not have been loaded.
    AnalysisError("select * from functional.map_table",
        "Failed to load metadata for table: map_table");

    // Select supported types from a table with mixed supported/unsupported types.
    AnalyzesOk("select int_col, str_col, bigint_col from functional.unsupported_types");
    // Unsupported type decimal.
    AnalysisError("select dec_col from functional.unsupported_types",
        "Unsupported type 'DECIMAL' in 'dec_col'.");
    // Unsupported type binary.
    AnalysisError("select bin_col from functional.unsupported_types",
        "Unsupported type 'BINARY' in 'bin_col'.");
    // Mixed supported/unsupported types.
    AnalysisError("select int_col, dec_col, str_col, bin_col " +
        "from functional.unsupported_types",
        "Unsupported type 'DECIMAL' in 'dec_col'.");
    // Unsupported partition-column type.
    AnalysisError("select * from functional.unsupported_partition_types",
        "Failed to load metadata for table: unsupported_partition_types");
  }

  @Test
  public void TestExplain() {
    // Analysis error from explain insert: too many partitioning columns.
    AnalysisError("explain insert into table functional.alltypessmall " +
        "partition (year=2009, month=4, year=10)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, bigint_col, " +
        "float_col, double_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes",
        "Superfluous columns in PARTITION clause: year.");

    // Analysis error from explain query
    AnalysisError("explain " +
    		"select id from (select id+2 from functional.hbasealltypessmall) a",
        "couldn't resolve column reference: 'id'");

    // Positive test for explain query
    AnalyzesOk("explain select * from functional.AllTypes");

    // Positive test for explain insert
    AnalyzesOk("explain insert into table functional.alltypessmall " +
        "partition (year=2009, month=4)" +
        "select id, bool_col, tinyint_col, smallint_col, int_col, int_col, " +
        "float_col, float_col, date_string_col, string_col, timestamp_col " +
        "from functional.alltypes");
  }

  @Test
  public void TestUnsupportedSerde() {
    AnalysisError("select * from functional.bad_serde",
                  "Failed to load metadata for table: bad_serde");
  }

  /**
   * Check that:
   * - we don't cast literals (we should have simply converted the literal
   *   to the target type)
   * - we don't do redundant casts (ie, we don't cast a bigint expr to a bigint)
   */
  private void checkCasts(Expr expr) {
    if (expr instanceof CastExpr) {
      Assert.assertFalse(expr.getType() == expr.getChild(0).getType());
      Assert.assertFalse(expr.getChild(0) instanceof LiteralExpr);
    }
    for (Expr child: expr.getChildren()) {
      checkCasts(child);
    }
  }
}
