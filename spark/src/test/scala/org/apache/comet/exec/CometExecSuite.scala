/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.comet.exec

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{AnalysisException, Column, CometTestBase, DataFrame, DataFrameWriter, Row, SaveMode}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogStatistics, CatalogTable}
import org.apache.spark.sql.catalyst.expressions.Hex
import org.apache.spark.sql.comet.{CometBroadcastExchangeExec, CometFilterExec, CometHashAggregateExec, CometProjectExec, CometScanExec, CometTakeOrderedAndProjectExec}
import org.apache.spark.sql.comet.execution.shuffle.{CometColumnarShuffle, CometShuffleExchangeExec}
import org.apache.spark.sql.execution.{CollectLimitExec, ProjectExec, UnionExec}
import org.apache.spark.sql.execution.exchange.BroadcastExchangeExec
import org.apache.spark.sql.execution.joins.{BroadcastNestedLoopJoinExec, CartesianProductExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.window.WindowExec
import org.apache.spark.sql.functions.{date_add, expr}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.SESSION_LOCAL_TIMEZONE
import org.apache.spark.unsafe.types.UTF8String

import org.apache.comet.CometConf
import org.apache.comet.CometSparkSessionExtensions.isSpark34Plus

class CometExecSuite extends CometTestBase {
  import testImplicits._

  override protected def test(testName: String, testTags: Tag*)(testFun: => Any)(implicit
      pos: Position): Unit = {
    super.test(testName, testTags: _*) {
      withSQLConf(CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true") {
        testFun
      }
    }
  }

  test("CometBroadcastExchangeExec") {
    withSQLConf(CometConf.COMET_EXEC_BROADCAST_ENABLED.key -> "true") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_a") {
        withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_b") {
          val df = sql(
            "SELECT tbl_a._1, tbl_b._2 FROM tbl_a JOIN tbl_b " +
              "WHERE tbl_a._1 > tbl_a._2 LIMIT 2")

          val nativeBroadcast = find(df.queryExecution.executedPlan) {
            case _: CometBroadcastExchangeExec => true
            case _ => false
          }.get.asInstanceOf[CometBroadcastExchangeExec]

          val numParts = nativeBroadcast.executeColumnar().getNumPartitions

          val rows = nativeBroadcast.executeCollect().toSeq.sortBy(row => row.getInt(0))
          val rowContents = rows.map(row => row.getInt(0))
          val expected = (0 until numParts).flatMap(_ => (0 until 5).map(i => i + 1)).sorted

          assert(rowContents === expected)
        }
      }
    }
  }

  test("CometBroadcastExchangeExec: empty broadcast") {
    withSQLConf(CometConf.COMET_EXEC_BROADCAST_ENABLED.key -> "true") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_a") {
        withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_b") {
          val df = sql(
            "SELECT /*+ BROADCAST(a) */ *" +
              " FROM (SELECT * FROM tbl_a WHERE _1 < 0) a JOIN tbl_b b" +
              " ON a._1 = b._1")
          val nativeBroadcast = find(df.queryExecution.executedPlan) {
            case _: CometBroadcastExchangeExec => true
            case _ => false
          }.get.asInstanceOf[CometBroadcastExchangeExec]
          val rows = nativeBroadcast.executeCollect()
          assert(rows.isEmpty)
        }
      }
    }
  }

  test("CometExec.executeColumnarCollectIterator can collect ColumnarBatch results") {
    withSQLConf(
      CometConf.COMET_EXEC_ENABLED.key -> "true",
      CometConf.COMET_EXEC_ALL_OPERATOR_ENABLED.key -> "true") {
      withParquetTable((0 until 50).map(i => (i, i + 1)), "tbl") {
        val df = sql("SELECT _1 + 1, _2 + 2 FROM tbl WHERE _1 > 3")

        val nativeProject = find(df.queryExecution.executedPlan) {
          case _: CometProjectExec => true
          case _ => false
        }.get.asInstanceOf[CometProjectExec]

        val (rows, batches) = nativeProject.executeColumnarCollectIterator()
        assert(rows == 46)

        val column1 = mutable.ArrayBuffer.empty[Int]
        val column2 = mutable.ArrayBuffer.empty[Int]

        batches.foreach(batch => {
          batch.rowIterator().asScala.foreach { row =>
            assert(row.numFields == 2)
            column1 += row.getInt(0)
            column2 += row.getInt(1)
          }
        })

        assert(column1.toArray.sorted === (4 until 50).map(_ + 1).toArray)
        assert(column2.toArray.sorted === (5 until 51).map(_ + 2).toArray)
      }
    }
  }

  test("scalar subquery") {
    val dataTypes =
      Seq(
        "BOOLEAN",
        "BYTE",
        "SHORT",
        "INT",
        "BIGINT",
        "FLOAT",
        "DOUBLE",
        // "DATE": TODO: needs to address issue #1364 first
        // "TIMESTAMP", TODO: needs to address issue #1364 first
        "STRING",
        "BINARY",
        "DECIMAL(38, 10)")
    dataTypes.map { subqueryType =>
      withSQLConf(
        CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true",
        CometConf.COMET_COLUMNAR_SHUFFLE_ENABLED.key -> "true") {
        withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
          var column1 = s"CAST(max(_1) AS $subqueryType)"
          if (subqueryType == "BINARY") {
            // arrow-rs doesn't support casting integer to binary yet.
            // We added it to upstream but it's not released yet.
            column1 = "CAST(CAST(max(_1) AS STRING) AS BINARY)"
          }

          val df1 = sql(s"SELECT (SELECT $column1 FROM tbl) AS a, _1, _2 FROM tbl")
          checkSparkAnswerAndOperator(df1)

          var column2 = s"CAST(_1 AS $subqueryType)"
          if (subqueryType == "BINARY") {
            // arrow-rs doesn't support casting integer to binary yet.
            // We added it to upstream but it's not released yet.
            column2 = "CAST(CAST(_1 AS STRING) AS BINARY)"
          }

          val df2 = sql(s"SELECT _1, _2 FROM tbl WHERE $column2 > (SELECT $column1 FROM tbl)")
          checkSparkAnswerAndOperator(df2)

          // Non-correlated exists subquery will be rewritten to scalar subquery
          val df3 = sql(
            "SELECT * FROM tbl WHERE EXISTS " +
              s"(SELECT $column2 FROM tbl WHERE _1 > 1)")
          checkSparkAnswerAndOperator(df3)

          // Null value
          column1 = s"CAST(NULL AS $subqueryType)"
          if (subqueryType == "BINARY") {
            column1 = "CAST(CAST(NULL AS STRING) AS BINARY)"
          }

          val df4 = sql(s"SELECT (SELECT $column1 FROM tbl LIMIT 1) AS a, _1, _2 FROM tbl")
          checkSparkAnswerAndOperator(df4)
        }
      }
    }
  }

  test("Comet native metrics: project and filter") {
    withSQLConf(
      CometConf.COMET_EXEC_ENABLED.key -> "true",
      CometConf.COMET_EXEC_ALL_OPERATOR_ENABLED.key -> "true") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
        val df = sql("SELECT _1 + 1, _2 + 2 FROM tbl WHERE _1 > 3")
        df.collect()

        var metrics = find(df.queryExecution.executedPlan) {
          case _: CometProjectExec => true
          case _ => false
        }.map(_.metrics).get

        assert(metrics.contains("output_rows"))
        assert(metrics("output_rows").value == 1L)

        metrics = find(df.queryExecution.executedPlan) {
          case _: CometFilterExec => true
          case _ => false
        }.map(_.metrics).get

        assert(metrics.contains("output_rows"))
        assert(metrics("output_rows").value == 1L)
      }
    }
  }

  test(
    "fix: ReusedExchangeExec + CometShuffleExchangeExec under QueryStageExec " +
      "should be CometRoot") {
    val tableName = "table1"
    val dim = "dim"

    withSQLConf(
      SQLConf.EXCHANGE_REUSE_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true",
      CometConf.COMET_COLUMNAR_SHUFFLE_ENABLED.key -> "true") {
      withTable(tableName, dim) {

        sql(
          s"CREATE TABLE $tableName (id BIGINT, price FLOAT, date DATE, ts TIMESTAMP) USING parquet " +
            "PARTITIONED BY (id)")
        sql(s"CREATE TABLE $dim (id BIGINT, date DATE) USING parquet")

        spark
          .range(1, 100)
          .withColumn("date", date_add(expr("DATE '1970-01-01'"), expr("CAST(id % 4 AS INT)")))
          .withColumn("ts", expr("TO_TIMESTAMP(date)"))
          .withColumn("price", expr("CAST(id AS FLOAT)"))
          .select("id", "price", "date", "ts")
          .coalesce(1)
          .write
          .mode(SaveMode.Append)
          .partitionBy("id")
          .saveAsTable(tableName)

        spark
          .range(1, 10)
          .withColumn("date", expr("DATE '1970-01-02'"))
          .select("id", "date")
          .coalesce(1)
          .write
          .mode(SaveMode.Append)
          .saveAsTable(dim)

        val query =
          s"""
             |SELECT $tableName.id, sum(price) as sum_price
             |FROM $tableName, $dim
             |WHERE $tableName.id = $dim.id AND $tableName.date = $dim.date
             |GROUP BY $tableName.id HAVING sum(price) > (
             |  SELECT sum(price) * 0.0001 FROM $tableName, $dim WHERE $tableName.id = $dim.id AND $tableName.date = $dim.date
             |  )
             |ORDER BY sum_price
             |""".stripMargin

        val df = sql(query)
        checkSparkAnswer(df)
        val exchanges = stripAQEPlan(df.queryExecution.executedPlan).collect {
          case s: CometShuffleExchangeExec if s.shuffleType == CometColumnarShuffle =>
            s
            s
        }
        assert(exchanges.length == 4)
      }
    }
  }

  test("expand operator") {
    val data1 = (0 until 1000)
      .map(_ % 5) // reduce value space to trigger dictionary encoding
      .map(i => (i, i + 100, i + 10))
    val data2 = (0 until 5).map(i => (i, i + 1, i * 1000))

    Seq(data1, data2).foreach { tableData =>
      withParquetTable(tableData, "tbl") {
        val df = sql("SELECT _1, _2, SUM(_3) FROM tbl GROUP BY _1, _2 GROUPING SETS ((_1), (_2))")
        checkSparkAnswerAndOperator(df)
      }
    }
  }

  test("multiple distinct multiple columns sets") {
    withTable("agg2") {
      val data2 = Seq[(Integer, Integer, Integer)](
        (1, 10, -10),
        (null, -60, 60),
        (1, 30, -30),
        (1, 30, 30),
        (2, 1, 1),
        (null, -10, 10),
        (2, -1, null),
        (2, 1, 1),
        (2, null, 1),
        (null, 100, -10),
        (3, null, 3),
        (null, null, null),
        (3, null, null)).toDF("key", "value1", "value2")
      data2.write.saveAsTable("agg2")

      val df = spark.sql("""
          |SELECT
          |  key,
          |  count(distinct value1),
          |  sum(distinct value1),
          |  count(distinct value2),
          |  sum(distinct value2),
          |  count(distinct value1, value2),
          |  count(value1),
          |  sum(value1),
          |  count(value2),
          |  sum(value2),
          |  count(*),
          |  count(1)
          |FROM agg2
          |GROUP BY key
              """.stripMargin)

      // The above query uses COUNT(DISTINCT) which Comet doesn't support yet, so the plan will
      // have a mix of `HashAggregate` and `CometHashAggregate`. In the following we check all
      // operators starting from `CometHashAggregate` are native.
      checkSparkAnswer(df)
      val subPlan = stripAQEPlan(df.queryExecution.executedPlan).collectFirst {
        case s: CometHashAggregateExec => s
      }
      assert(subPlan.isDefined)
      checkCometOperators(subPlan.get)
    }
  }

  test("transformed cometPlan") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df = sql("select * FROM tbl where _1 >= 2").select("_1")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("project") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df = sql("SELECT _1 + 1, _2 + 2, _1 - 1, _2 * 2, _2 / 2 FROM tbl")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("project + filter on arrays") {
    withParquetTable((0 until 5).map(i => (i, i)), "tbl") {
      val df = sql("SELECT _1 FROM tbl WHERE _1 == _2")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("project + filter") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df = sql("SELECT _1 + 1, _2 + 2 FROM tbl WHERE _1 > 3")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("empty projection") {
    withParquetDataFrame((0 until 5).map(i => (i, i + 1))) { df =>
      assert(df.where("_1 IS NOT NULL").count() == 5)
      checkSparkAnswerAndOperator(df)
      assert(df.select().limit(2).count() === 2)
    }
  }

  test("filter on string") {
    withParquetTable((0 until 5).map(i => (i, i.toString)), "tbl") {
      val df = sql("SELECT _1 + 1, _2 FROM tbl WHERE _2 = '3'")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("filter on dictionary string") {
    val data = (0 until 1000)
      .map(_ % 5) // reduce value space to trigger dictionary encoding
      .map(i => (i.toString, (i + 100).toString))

    withParquetTable(data, "tbl") {
      val df = sql("SELECT _1, _2 FROM tbl WHERE _1 = '3'")
      checkSparkAnswerAndOperator(df)
    }
  }

  test("sort with dictionary") {
    withSQLConf(CometConf.COMET_BATCH_SIZE.key -> 8192.toString) {
      withTempDir { dir =>
        val path = new Path(dir.toURI.toString, "test")
        spark
          .createDataFrame((0 until 1000).map(i => (i % 5, (i % 7).toLong)))
          .write
          .option("compression", "none")
          .parquet(path.toString)

        spark
          .createDataFrame((0 until 1000).map(i => (i % 3 + 7, (i % 13 + 10).toLong)))
          .write
          .option("compression", "none")
          .mode(SaveMode.Append)
          .parquet(path.toString)

        val df = spark.read
          .format("parquet")
          .load(path.toString)
          .sortWithinPartitions($"_1".asc, $"_2".desc)

        checkSparkAnswerAndOperator(df)
      }
    }
  }

  test("final aggregation") {
    withSQLConf(CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true") {
      withParquetTable(
        (0 until 100)
          .map(_ => (Random.nextInt(), Random.nextInt() % 5)),
        "tbl") {
        val df = sql("SELECT _2, COUNT(*) FROM tbl GROUP BY _2")
        checkSparkAnswerAndOperator(df)
      }
    }
  }

  test("sort (non-global)") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df = sql("SELECT * FROM tbl").sortWithinPartitions($"_1".desc)
      checkSparkAnswerAndOperator(df)
    }
  }

  test("global sort (columnar shuffle only)") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
      CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true",
      CometConf.COMET_COLUMNAR_SHUFFLE_ENABLED.key -> "true") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
        val df = sql("SELECT * FROM tbl").sort($"_1".desc)
        checkSparkAnswerAndOperator(df)
      }
    }
  }

  test("spill sort with (multiple) dictionaries") {
    withSQLConf(CometConf.COMET_MEMORY_OVERHEAD.key -> "15MB") {
      withTempDir { dir =>
        val path = new Path(dir.toURI.toString, "part-r-0.parquet")
        makeRawTimeParquetFileColumns(path, dictionaryEnabled = true, n = 1000, rowGroupSize = 10)
        readParquetFile(path.toString) { df =>
          Seq(
            $"_0".desc_nulls_first,
            $"_0".desc_nulls_last,
            $"_0".asc_nulls_first,
            $"_0".asc_nulls_last).foreach { colOrder =>
            val query = df.sortWithinPartitions(colOrder)
            checkSparkAnswerAndOperator(query)
          }
        }
      }
    }
  }

  test("spill sort with (multiple) dictionaries on mixed columns") {
    withSQLConf(CometConf.COMET_MEMORY_OVERHEAD.key -> "15MB") {
      withTempDir { dir =>
        val path = new Path(dir.toURI.toString, "part-r-0.parquet")
        makeRawTimeParquetFile(path, dictionaryEnabled = true, n = 1000, rowGroupSize = 10)
        readParquetFile(path.toString) { df =>
          Seq(
            $"_6".desc_nulls_first,
            $"_6".desc_nulls_last,
            $"_6".asc_nulls_first,
            $"_6".asc_nulls_last).foreach { colOrder =>
            // TODO: We should be able to sort on dictionary timestamp column
            val query = df.sortWithinPartitions(colOrder)
            checkSparkAnswerAndOperator(query)
          }
        }
      }
    }
  }

  test("limit") {
    Seq("true", "false").foreach { columnarShuffle =>
      withSQLConf(
        CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true",
        CometConf.COMET_COLUMNAR_SHUFFLE_ENABLED.key -> columnarShuffle) {
        withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_a") {
          val df = sql("SELECT * FROM tbl_a")
            .repartition(10, $"_1")
            .limit(2)
            .sort($"_2".desc)
          checkSparkAnswerAndOperator(df)
        }
      }
    }
  }

  test("limit (cartesian product)") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_a") {
        withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_b") {
          val df = sql("SELECT tbl_a._1, tbl_b._2 FROM tbl_a JOIN tbl_b LIMIT 2")
          checkSparkAnswerAndOperator(
            df,
            classOf[CollectLimitExec],
            classOf[CartesianProductExec])
        }
      }
    }
  }

  test("limit with more than one batch") {
    withSQLConf(CometConf.COMET_BATCH_SIZE.key -> "1") {
      withParquetTable((0 until 50).map(i => (i, i + 1)), "tbl_a") {
        withParquetTable((0 until 50).map(i => (i, i + 1)), "tbl_b") {
          val df = sql("SELECT tbl_a._1, tbl_b._2 FROM tbl_a JOIN tbl_b LIMIT 2")
          checkSparkAnswerAndOperator(
            df,
            classOf[CollectLimitExec],
            classOf[BroadcastNestedLoopJoinExec],
            classOf[BroadcastExchangeExec])
        }
      }
    }
  }

  test("limit less than rows") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_a") {
      withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl_b") {
        val df = sql(
          "SELECT tbl_a._1, tbl_b._2 FROM tbl_a JOIN tbl_b " +
            "WHERE tbl_a._1 > tbl_a._2 LIMIT 2")
        checkSparkAnswerAndOperator(
          df,
          classOf[CollectLimitExec],
          classOf[BroadcastNestedLoopJoinExec],
          classOf[BroadcastExchangeExec])
      }
    }
  }

  test("empty-column input (read schema is empty)") {
    withTable("t1") {
      Seq((1, true), (2, false))
        .toDF("l", "b")
        .repartition(2)
        .write
        .saveAsTable("t1")
      val query = spark.table("t1").selectExpr("IF(l > 1 AND null, 5, 1) AS out")
      checkSparkAnswerAndOperator(query)
    }
  }

  test("empty-column aggregation") {
    withTable("t1") {
      Seq((1, true), (2, false))
        .toDF("l", "b")
        .repartition(2)
        .write
        .saveAsTable("t1")
      val query = sql("SELECT count(1) FROM t1")
      checkSparkAnswerAndOperator(query)
    }
  }

  test("null handling") {
    Seq("true", "false").foreach { pushDown =>
      val table = "t1"
      withSQLConf(SQLConf.PARQUET_FILTER_PUSHDOWN_ENABLED.key -> pushDown) {
        withTable(table) {
          sql(s"create table $table(a int, b int, c int) using parquet")
          sql(s"insert into $table values(1,0,0)")
          sql(s"insert into $table values(2,0,1)")
          sql(s"insert into $table values(3,1,0)")
          sql(s"insert into $table values(4,1,1)")
          sql(s"insert into $table values(5,null,0)")
          sql(s"insert into $table values(6,null,1)")
          sql(s"insert into $table values(7,null,null)")

          val query = sql(s"select a+120 from $table where b<10 OR c=1")
          checkSparkAnswerAndOperator(query)
        }
      }
    }
  }

  test("float4.sql") {
    val table = "t1"
    withTable(table) {
      sql(s"CREATE TABLE $table (f1  float) USING parquet")
      sql(s"INSERT INTO $table VALUES (float('    0.0'))")
      sql(s"INSERT INTO $table VALUES (float('1004.30   '))")
      sql(s"INSERT INTO $table VALUES (float('     -34.84    '))")
      sql(s"INSERT INTO $table VALUES (float('1.2345678901234e+20'))")
      sql(s"INSERT INTO $table VALUES (float('1.2345678901234e-20'))")

      val query = sql(s"SELECT '' AS four, f.* FROM $table f WHERE '1004.3' > f.f1")
      checkSparkAnswerAndOperator(query)
    }
  }

  test("NaN in predicate expression") {
    val t = "test_table"

    withTable(t) {
      Seq[(Integer, java.lang.Short, java.lang.Float)](
        (1, 100.toShort, 3.14.toFloat),
        (2, Short.MaxValue, Float.NaN),
        (3, Short.MinValue, Float.PositiveInfinity),
        (4, 0.toShort, Float.MaxValue),
        (5, null, null))
        .toDF("c1", "c2", "c3")
        .write
        .saveAsTable(t)

      val df = spark.table(t)

      var query = df.where("c3 > double('nan')").select("c1")
      checkSparkAnswer(query)
      // Empty result will be optimized to a local relation. No CometExec involved.
      // checkCometExec(query, 0, cometExecs => {})

      query = df.where("c3 >= double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)
      // checkCometExec(query, 1, cometExecs => {})

      query = df.where("c3 == double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)

      query = df.where("c3 <=> double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)

      query = df.where("c3 != double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)

      query = df.where("c3 <= double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)

      query = df.where("c3 < double('nan')").select("c1")
      checkSparkAnswerAndOperator(query)
    }
  }

  test("table statistics") {
    withTempDatabase { database =>
      spark.catalog.setCurrentDatabase(database)
      withTempDir { dir =>
        withTable("t1", "t2") {
          spark.range(10).write.saveAsTable("t1")
          sql(
            s"CREATE EXTERNAL TABLE t2 USING parquet LOCATION '${dir.toURI}' " +
              "AS SELECT * FROM range(20)")

          sql(s"ANALYZE TABLES IN $database COMPUTE STATISTICS NOSCAN")
          checkTableStats("t1", hasSizeInBytes = true, expectedRowCounts = None)
          checkTableStats("t2", hasSizeInBytes = true, expectedRowCounts = None)

          sql("ANALYZE TABLES COMPUTE STATISTICS")
          checkTableStats("t1", hasSizeInBytes = true, expectedRowCounts = Some(10))
          checkTableStats("t2", hasSizeInBytes = true, expectedRowCounts = Some(20))
        }
      }
    }
  }

  test("like (LikeSimplification disabled)") {
    val table = "names"
    withSQLConf(
      SQLConf.OPTIMIZER_EXCLUDED_RULES.key -> "org.apache.spark.sql.catalyst.optimizer.LikeSimplification") {
      withTable(table) {
        sql(s"create table $table(id int, name varchar(20)) using parquet")
        sql(s"insert into $table values(1,'James Smith')")
        sql(s"insert into $table values(2,'Michael Rose')")
        sql(s"insert into $table values(3,'Robert Williams')")
        sql(s"insert into $table values(4,'Rames Rose')")
        sql(s"insert into $table values(5,'Rames rose')")

        // Filter column having values 'Rames _ose', where any character matches for '_'
        val query = sql(s"select id from $table where name like 'Rames _ose'")
        checkSparkAnswerAndOperator(query)

        // Filter rows that contains 'rose' in 'name' column
        val queryContains = sql(s"select id from $table where name like '%rose%'")
        checkSparkAnswerAndOperator(queryContains)

        // Filter rows that starts with 'R' following by any characters
        val queryStartsWith = sql(s"select id from $table where name like 'R%'")
        checkSparkAnswerAndOperator(queryStartsWith)

        // Filter rows that ends with 's' following by any characters
        val queryEndsWith = sql(s"select id from $table where name like '%s'")
        checkSparkAnswerAndOperator(queryEndsWith)
      }
    }
  }

  test("sum overflow (ANSI disable)") {
    Seq("true", "false").foreach { dictionary =>
      withSQLConf(
        SQLConf.ANSI_ENABLED.key -> "false",
        "parquet.enable.dictionary" -> dictionary) {
        withParquetTable(Seq((Long.MaxValue, 1), (Long.MaxValue, 2)), "tbl") {
          val df = sql("SELECT sum(_1) FROM tbl")
          checkSparkAnswerAndOperator(df)
        }
      }
    }
  }

  test("partition col") {
    withSQLConf(SESSION_LOCAL_TIMEZONE.key -> "Asia/Kathmandu") {
      withTable("t1") {
        sql("""
            | CREATE TABLE t1(name STRING, part1 TIMESTAMP)
            | USING PARQUET PARTITIONED BY (part1)
       """.stripMargin)

        sql("""
            | INSERT OVERWRITE t1 PARTITION(
            | part1 = timestamp'2019-01-01 11:11:11'
            | ) VALUES('a')
      """.stripMargin)
        checkSparkAnswerAndOperator(sql("""
            | SELECT
            |   name,
            |   CAST(part1 AS STRING)
            | FROM t1
      """.stripMargin))
      }
    }
  }

  test("SPARK-33474: Support typed literals as partition spec values") {
    withSQLConf(SESSION_LOCAL_TIMEZONE.key -> "Asia/Kathmandu") {
      withTable("t1") {
        val binaryStr = "Spark SQL"
        val binaryHexStr = Hex.hex(UTF8String.fromString(binaryStr).getBytes).toString
        sql("""
            | CREATE TABLE t1(name STRING, part1 DATE, part2 TIMESTAMP, part3 BINARY,
            |  part4 STRING, part5 STRING, part6 STRING, part7 STRING)
            | USING PARQUET PARTITIONED BY (part1, part2, part3, part4, part5, part6, part7)
         """.stripMargin)

        sql(s"""
             | INSERT OVERWRITE t1 PARTITION(
             | part1 = date'2019-01-01',
             | part2 = timestamp'2019-01-01 11:11:11',
             | part3 = X'$binaryHexStr',
             | part4 = 'p1',
             | part5 = date'2019-01-01',
             | part6 = timestamp'2019-01-01 11:11:11',
             | part7 = X'$binaryHexStr'
             | ) VALUES('a')
        """.stripMargin)
        checkSparkAnswerAndOperator(sql("""
              | SELECT
              |   name,
              |   CAST(part1 AS STRING),
              |   CAST(part2 as STRING),
              |   CAST(part3 as STRING),
              |   part4,
              |   part5,
              |   part6,
              |   part7
              | FROM t1
        """.stripMargin))

        val e = intercept[AnalysisException] {
          sql("CREATE TABLE t2(name STRING, part INTERVAL) USING PARQUET PARTITIONED BY (part)")
        }.getMessage
        assert(e.contains("Cannot use interval"))
      }
    }
  }

  def getCatalogTable(tableName: String): CatalogTable = {
    spark.sessionState.catalog.getTableMetadata(TableIdentifier(tableName))
  }

  def checkTableStats(
      tableName: String,
      hasSizeInBytes: Boolean,
      expectedRowCounts: Option[Int]): Option[CatalogStatistics] = {
    val stats = getCatalogTable(tableName).stats
    if (hasSizeInBytes || expectedRowCounts.nonEmpty) {
      assert(stats.isDefined)
      assert(stats.get.sizeInBytes >= 0)
      assert(stats.get.rowCount === expectedRowCounts)
    } else {
      assert(stats.isEmpty)
    }

    stats
  }

  def joinCondition(joinCols: Seq[String])(left: DataFrame, right: DataFrame): Column = {
    joinCols.map(col => left(col) === right(col)).reduce(_ && _)
  }

  def testBucketing(
      bucketedTableTestSpecLeft: BucketedTableTestSpec,
      bucketedTableTestSpecRight: BucketedTableTestSpec,
      joinType: String = "inner",
      joinCondition: (DataFrame, DataFrame) => Column): Unit = {
    val df1 =
      (0 until 50).map(i => (i % 5, i % 13, i.toString)).toDF("i", "j", "k").as("df1")
    val df2 =
      (0 until 50).map(i => (i % 7, i % 11, i.toString)).toDF("i", "j", "k").as("df2")

    val BucketedTableTestSpec(
      bucketSpecLeft,
      numPartitionsLeft,
      shuffleLeft,
      sortLeft,
      numOutputPartitionsLeft) = bucketedTableTestSpecLeft

    val BucketedTableTestSpec(
      bucketSpecRight,
      numPartitionsRight,
      shuffleRight,
      sortRight,
      numOutputPartitionsRight) = bucketedTableTestSpecRight

    withTable("bucketed_table1", "bucketed_table2") {
      withBucket(df1.repartition(numPartitionsLeft).write.format("parquet"), bucketSpecLeft)
        .saveAsTable("bucketed_table1")
      withBucket(df2.repartition(numPartitionsRight).write.format("parquet"), bucketSpecRight)
        .saveAsTable("bucketed_table2")

      withSQLConf(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "0",
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        val t1 = spark.table("bucketed_table1")
        val t2 = spark.table("bucketed_table2")
        val joined = t1.join(t2, joinCondition(t1, t2), joinType)

        val df = joined.sort("bucketed_table1.k", "bucketed_table2.k")
        checkSparkAnswer(df)

        // The sub-plan contains should contain all native operators except a SMJ
        val subPlan = stripAQEPlan(df.queryExecution.executedPlan).collectFirst {
          case s: SortMergeJoinExec => s
        }
        assert(subPlan.isDefined)
        checkCometOperators(subPlan.get, classOf[SortMergeJoinExec])
      }
    }
  }

  test("bucketed table") {
    val bucketSpec = Some(BucketSpec(8, Seq("i", "j"), Nil))
    val bucketedTableTestSpecLeft = BucketedTableTestSpec(bucketSpec, expectedShuffle = false)
    val bucketedTableTestSpecRight = BucketedTableTestSpec(bucketSpec, expectedShuffle = false)

    testBucketing(
      bucketedTableTestSpecLeft = bucketedTableTestSpecLeft,
      bucketedTableTestSpecRight = bucketedTableTestSpecRight,
      joinCondition = joinCondition(Seq("i", "j")))
  }

  def withBucket(
      writer: DataFrameWriter[Row],
      bucketSpec: Option[BucketSpec]): DataFrameWriter[Row] = {
    bucketSpec
      .map { spec =>
        writer.bucketBy(
          spec.numBuckets,
          spec.bucketColumnNames.head,
          spec.bucketColumnNames.tail: _*)

        if (spec.sortColumnNames.nonEmpty) {
          writer.sortBy(spec.sortColumnNames.head, spec.sortColumnNames.tail: _*)
        } else {
          writer
        }
      }
      .getOrElse(writer)
  }

  test("union") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df1 = sql("select * FROM tbl where _1 >= 2").select("_1")
      val df2 = sql("select * FROM tbl where _1 >= 2").select("_2")
      val df3 = sql("select * FROM tbl where _1 >= 3").select("_2")

      val unionDf1 = df1.union(df2)
      checkSparkAnswerAndOperator(unionDf1)

      // Test union with different number of rows from inputs
      val unionDf2 = df1.union(df3)
      checkSparkAnswerAndOperator(unionDf2)

      val unionDf3 = df1.union(df2).union(df3)
      checkSparkAnswerAndOperator(unionDf3)
    }
  }

  test("native execution after union") {
    withParquetTable((0 until 5).map(i => (i, i + 1)), "tbl") {
      val df1 = sql("select * FROM tbl where _1 >= 2").select("_1")
      val df2 = sql("select * FROM tbl where _1 >= 2").select("_2")
      val df3 = sql("select * FROM tbl where _1 >= 3").select("_2")

      val unionDf1 = df1.union(df2).select($"_1" + 1).sortWithinPartitions($"_1")
      checkSparkAnswerAndOperator(unionDf1)

      // Test union with different number of rows from inputs
      val unionDf2 = df1.union(df3).select($"_1" + 1).sortWithinPartitions($"_1")
      checkSparkAnswerAndOperator(unionDf2)

      val unionDf3 = df1.union(df2).union(df3).select($"_1" + 1).sortWithinPartitions($"_1")
      checkSparkAnswerAndOperator(unionDf3)
    }
  }

  test("native execution after coalesce") {
    withTable("t1") {
      (0 until 5)
        .map(i => (i, (i + 1).toLong))
        .toDF("l", "b")
        .write
        .saveAsTable("t1")

      val df = sql("SELECT * FROM t1")
        .sortWithinPartitions($"l".desc)
        .repartition(10, $"l")

      val rdd = df.rdd
      assert(rdd.partitions.length == 10)

      val coalesced = df.coalesce(2).select($"l" + 1).sortWithinPartitions($"l")
      checkSparkAnswerAndOperator(coalesced)
    }
  }

  test("disabled/unsupported exec with multiple children should not disappear") {
    withSQLConf(
      CometConf.COMET_EXEC_ALL_OPERATOR_ENABLED.key -> "false",
      CometConf.COMET_EXEC_CONFIG_PREFIX + ".project.enabled" -> "true",
      CometConf.COMET_EXEC_CONFIG_PREFIX + ".union.enabled" -> "false") {
      withParquetDataFrame((0 until 5).map(Tuple1(_))) { df =>
        val projected = df.selectExpr("_1 as x")
        val unioned = projected.union(df)
        val p = unioned.queryExecution.executedPlan.find(_.isInstanceOf[UnionExec])
        assert(p.get.collectLeaves().forall(_.isInstanceOf[CometScanExec]))
      }
    }
  }

  test("coalesce") {
    withSQLConf(CometConf.COMET_EXEC_SHUFFLE_ENABLED.key -> "true") {
      withTable("t1") {
        (0 until 5)
          .map(i => (i, (i + 1).toLong))
          .toDF("l", "b")
          .write
          .saveAsTable("t1")

        val df = sql("SELECT * FROM t1")
          .sortWithinPartitions($"l".desc)
          .repartition(10, $"l")

        val rdd = df.rdd
        assert(rdd.getNumPartitions == 10)

        val coalesced = df.coalesce(2)
        checkSparkAnswerAndOperator(coalesced)

        val coalescedRdd = coalesced.rdd
        assert(coalescedRdd.getNumPartitions == 2)
      }
    }
  }

  test("TakeOrderedAndProjectExec") {
    Seq("true", "false").foreach(aqeEnabled =>
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled) {
        withTable("t1") {
          val numRows = 10
          spark
            .range(numRows)
            .selectExpr("if (id % 2 = 0, null, id) AS a", s"$numRows - id AS b")
            .repartition(3) // Force repartition to test data will come to single partition
            .write
            .saveAsTable("t1")

          val df1 = spark.sql("""
                                |SELECT a, b, ROW_NUMBER() OVER(ORDER BY a, b) AS rn
                                |FROM t1 LIMIT 3
                                |""".stripMargin)

          assert(df1.rdd.getNumPartitions == 1)
          checkSparkAnswerAndOperator(df1, classOf[WindowExec])

          val df2 = spark.sql("""
                                |SELECT b, RANK() OVER(ORDER BY a, b) AS rk, DENSE_RANK(b) OVER(ORDER BY a, b) AS s
                                |FROM t1 LIMIT 2
                                |""".stripMargin)
          assert(df2.rdd.getNumPartitions == 1)
          checkSparkAnswerAndOperator(df2, classOf[WindowExec], classOf[ProjectExec])

          // Other Comet native operator can take input from `CometTakeOrderedAndProjectExec`.
          val df3 = sql("SELECT * FROM t1 ORDER BY a, b LIMIT 3").groupBy($"a").sum("b")
          checkSparkAnswerAndOperator(df3)
        }
      })
  }

  test("TakeOrderedAndProjectExec without sorting") {
    Seq("true", "false").foreach(aqeEnabled =>
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled,
        SQLConf.OPTIMIZER_EXCLUDED_RULES.key ->
          "org.apache.spark.sql.catalyst.optimizer.EliminateSorts") {
        withTable("t1") {
          val numRows = 10
          spark
            .range(numRows)
            .selectExpr("if (id % 2 = 0, null, id) AS a", s"$numRows - id AS b")
            .repartition(3) // Force repartition to test data will come to single partition
            .write
            .saveAsTable("t1")

          val df = spark
            .table("t1")
            .select("a", "b")
            .sortWithinPartitions("b", "a")
            .orderBy("b")
            .select($"b" + 1, $"a")
            .limit(3)

          val takeOrdered = stripAQEPlan(df.queryExecution.executedPlan).collect {
            case b: CometTakeOrderedAndProjectExec => b
          }
          assert(takeOrdered.length == 1)
          assert(takeOrdered.head.orderingSatisfies)

          checkSparkAnswerAndOperator(df)
        }
      })
  }

  test("Fallback to Spark for TakeOrderedAndProjectExec with offset") {
    assume(isSpark34Plus)
    Seq("true", "false").foreach(aqeEnabled =>
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled) {
        withTable("t1") {
          val numRows = 10
          spark
            .range(numRows)
            .selectExpr("if (id % 2 = 0, null, id) AS a", s"$numRows - id AS b")
            .repartition(3) // Force repartition to test data will come to single partition
            .write
            .saveAsTable("t1")

          val df = sql("SELECT * FROM t1 ORDER BY a, b LIMIT 3 OFFSET 1").groupBy($"a").sum("b")
          checkSparkAnswer(df)
        }
      })
  }
}

case class BucketedTableTestSpec(
    bucketSpec: Option[BucketSpec],
    numPartitions: Int = 10,
    expectedShuffle: Boolean = true,
    expectedSort: Boolean = true,
    expectedNumOutputPartitions: Option[Int] = None)
