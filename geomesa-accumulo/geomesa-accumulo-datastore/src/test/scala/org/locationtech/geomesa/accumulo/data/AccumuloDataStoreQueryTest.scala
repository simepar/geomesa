/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.data

import java.util.Date

import com.vividsolutions.jts.geom.Coordinate
import org.geotools.data._
import org.geotools.factory.{CommonFactoryFinder, Hints}
import org.geotools.feature.NameImpl
import org.geotools.filter.text.cql2.CQL
import org.geotools.filter.text.ecql.ECQL
import org.geotools.geometry.jts.JTSFactoryFinder
import org.geotools.util.Converters
import org.joda.time.{DateTime, DateTimeZone}
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithMultipleSfts
import org.locationtech.geomesa.accumulo.index.QueryHints._
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType
import org.locationtech.geomesa.accumulo.index.{ExplainString, JoinPlan, QueryHints, QueryPlanner}
import org.locationtech.geomesa.accumulo.iterators.{BinAggregatingIterator, TestData}
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.filter.function.{BasicValues, Convert2ViewerFunction}
import org.locationtech.geomesa.utils.filters.Filters
import org.locationtech.geomesa.utils.geotools.Conversions._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.filter.Filter
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.time.Duration

import scala.collection.JavaConversions._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class AccumuloDataStoreQueryTest extends Specification with TestWithMultipleSfts {

  sequential

  val ff = CommonFactoryFinder.getFilterFactory2
  val defaultSft = createNewSchema("name:String:index=join,geom:Point:srid=4326,dtg:Date")
  addFeature(defaultSft, ScalaSimpleFeature.create(defaultSft, "fid-1", "name1", "POINT(45 49)", "2010-05-07T12:30:00.000Z"))

  "AccumuloDataStore" should {
    "return an empty iterator correctly" in {
      val fs = ds.getFeatureSource(defaultSft.getTypeName)

      // compose a CQL query that uses a polygon that is disjoint with the feature bounds
      val cqlFilter = CQL.toFilter(s"BBOX(geom, 64.9,68.9,65.1,69.1)")
      val query = new Query(defaultSft.getTypeName, cqlFilter)

      // Let's read out what we wrote.
      val results = fs.getFeatures(query)
      val features = results.features

      "where schema matches" >> { results.getSchema mustEqual defaultSft }
      "and there are no results" >> { features.hasNext must beFalse }
    }

    "process a DWithin query correctly" in {
      // compose a CQL query that uses a polygon that is disjoint with the feature bounds
      val geomFactory = JTSFactoryFinder.getGeometryFactory
      val q = ff.dwithin(ff.property("geom"),
        ff.literal(geomFactory.createPoint(new Coordinate(45.000001, 48.99999))), 100.0, "meters")
      val query = new Query(defaultSft.getTypeName, q)

      // Let's read out what we wrote.
      val results = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(query)
      val features = results.features

      "with correct result" >> {
        features.hasNext must beTrue
        features.next().getID mustEqual "fid-1"
        features.hasNext must beFalse
      }
    }

    "process a DWithin of a Linestring and dtg During query correctly" >> {
      val lineOfBufferCoords: Array[Coordinate] = Array(new Coordinate(-45, 0), new Coordinate(-90, 45))
      val geomFactory = JTSFactoryFinder.getGeometryFactory

      // create the data store
      val sftPoints = createNewSchema("*geom:Point:srid=4326,dtg:Date")

      // add the 150 excluded points
      TestData.excludedDwithinPoints.zipWithIndex.foreach{ case (p, i) =>
        addFeature(sftPoints, ScalaSimpleFeature.create(sftPoints, s"exfid$i", p, "2014-06-07T12:00:00.000Z"))
      }

      // add the 50 included points
      TestData.includedDwithinPoints.zipWithIndex.foreach{ case (p, i) =>
        addFeature(sftPoints, ScalaSimpleFeature.create(sftPoints, "infid$i", p, "2014-06-07T12:00:00.000Z"))
      }

      // compose the query
      val start   = new DateTime(2014, 6, 7, 11, 0, 0, DateTimeZone.forID("UTC"))
      val end     = new DateTime(2014, 6, 7, 13, 0, 0, DateTimeZone.forID("UTC"))
      val during  = ff.during(ff.property("dtg"), Filters.dts2lit(start, end))

      "with correct result when using a dwithin of degrees" >> {
        val dwithinUsingDegrees = ff.dwithin(ff.property("geom"),
          ff.literal(geomFactory.createLineString(lineOfBufferCoords)), 1.0, "degrees")
        val filterUsingDegrees  = ff.and(during, dwithinUsingDegrees)
        val queryUsingDegrees   = new Query(sftPoints.getTypeName, filterUsingDegrees)
        val resultsUsingDegrees = ds.getFeatureSource(sftPoints.getTypeName).getFeatures(queryUsingDegrees)
        resultsUsingDegrees.features.length mustEqual 50
      }.pendingUntilFixed("Fixed Z3 'During And Dwithin' queries for a buffer created with unit degrees")

      "with correct result when using a dwithin of meters" >> {
        val dwithinUsingMeters = ff.dwithin(ff.property("geom"),
          ff.literal(geomFactory.createLineString(lineOfBufferCoords)), 150000, "meters")
        val filterUsingMeters  = ff.and(during, dwithinUsingMeters)
        val queryUsingMeters   = new Query(sftPoints.getTypeName, filterUsingMeters)
        val resultsUsingMeters = ds.getFeatureSource(sftPoints.getTypeName).getFeatures(queryUsingMeters)
        resultsUsingMeters.features.length mustEqual 50
      }
    }

    "handle bboxes without property name" in {
      val filterNull = ff.bbox(ff.property(null.asInstanceOf[String]), 40, 44, 50, 54, "EPSG:4326")
      val filterEmpty = ff.bbox(ff.property(""), 40, 44, 50, 54, "EPSG:4326")
      val queryNull = new Query(defaultSft.getTypeName, filterNull)
      val queryEmpty = new Query(defaultSft.getTypeName, filterEmpty)

      val explainNull = {
        val o = new ExplainString
        ds.getQueryPlan(queryNull, explainer = o)
        o.toString()
      }
      val explainEmpty = {
        val o = new ExplainString
        ds.getQueryPlan(queryEmpty, explainer = o)
        o.toString()
      }

      explainNull must contain("Strategy filter: Z2[BBOX(geom, 40.0,44.0,50.0,54.0)][None]")
      explainEmpty must contain("Strategy filter: Z2[BBOX(geom, 40.0,44.0,50.0,54.0)][None]")

      val featuresNull = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(queryNull).features.toSeq
      val featuresEmpty = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(queryEmpty).features.toSeq

      featuresNull.map(_.getID) mustEqual Seq("fid-1")
      featuresEmpty.map(_.getID) mustEqual Seq("fid-1")
    }

    "process an OR query correctly obeying inclusion-exclusion principle" >> {
      val sft = createNewSchema("name:String,geom:Point:srid=4326,dtg:Date")

      val randVal: (Double, Double) => Double = {
        val r = new Random(System.nanoTime())
        (low, high) => {
          (r.nextDouble() * (high - low)) + low
        }
      }
      val features = (0 until 1000).map { i =>
        val lat = randVal(-0.001, 0.001)
        val lon = randVal(-0.001, 0.001)
        ScalaSimpleFeature.create(sft, s"fid-$i", "testType", s"POINT($lat $lon)")
      }
      addFeatures(sft, features)

      val fs = ds.getFeatureSource(sft.getTypeName)

      val geomFactory = JTSFactoryFinder.getGeometryFactory
      val urq = ff.dwithin(ff.property("geom"),
        ff.literal(geomFactory.createPoint(new Coordinate( 0.0005,  0.0005))), 150.0, "meters")
      val llq = ff.dwithin(ff.property("geom"),
        ff.literal(geomFactory.createPoint(new Coordinate(-0.0005, -0.0005))), 150.0, "meters")
      val orq = ff.or(urq, llq)
      val andq = ff.and(urq, llq)
      val urQuery  = new Query(sft.getTypeName,  urq)
      val llQuery  = new Query(sft.getTypeName,  llq)
      val orQuery  = new Query(sft.getTypeName,  orq)
      val andQuery = new Query(sft.getTypeName, andq)

      val urNum  = fs.getFeatures(urQuery).features.length
      val llNum  = fs.getFeatures(llQuery).features.length
      val orNum  = fs.getFeatures(orQuery).features.length
      val andNum = fs.getFeatures(andQuery).features.length

      (urNum + llNum) mustEqual (orNum + andNum)
    }

    "handle between intra-day queries" in {
      val filter =
        CQL.toFilter("bbox(geom,40,40,60,60) AND dtg BETWEEN '2010-05-07T12:00:00.000Z' AND '2010-05-07T13:00:00.000Z'")
      val query = new Query(defaultSft.getTypeName, filter)
      val features = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(query).features.toList
      features.map(DataUtilities.encodeFeature) mustEqual List("fid-1=name1|POINT (45 49)|2010-05-07T12:30:00.000Z")
    }

    "handle large ranges" in {
      skipped("takes ~10 seconds")
      val filter = ECQL.toFilter("contains(POLYGON ((40 40, 50 40, 50 50, 40 50, 40 40)), geom) AND " +
          "dtg BETWEEN '2010-01-01T00:00:00.000Z' AND '2010-12-31T23:59:59.000Z'")
      val query = new Query(defaultSft.getTypeName, filter)
      val features = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(query).features.toList
      features.map(DataUtilities.encodeFeature) mustEqual List("fid-1=name1|POINT (45 49)|2010-05-07T12:30:00.000Z")
    }

    "handle out-of-bound longitude and in-bounds latitude bboxes" in {
      val filter = ECQL.toFilter("BBOX(geom, -266.8359375,-75.5859375,279.4921875,162.7734375)")
      val query = new Query(defaultSft.getTypeName, filter)
      val features = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(query).features.toList
      features.map(DataUtilities.encodeFeature) mustEqual List("fid-1=name1|POINT (45 49)|2010-05-07T12:30:00.000Z")
    }

    "handle requests with namespaces" in {
      // create the data store
      val ns = "mytestns"
      val typeName = "namespacetest"
      val sft = SimpleFeatureTypes.createType(ns, typeName, "name:String,geom:Point:srid=4326,dtg:Date")
      ds.createSchema(sft)

      val schemaWithoutNs = ds.getSchema(sft.getTypeName)

      schemaWithoutNs.getName.getNamespaceURI must beNull
      schemaWithoutNs.getName.getLocalPart mustEqual sft.getTypeName

      val schemaWithNs = ds.getSchema(new NameImpl(ns, sft.getTypeName))

      schemaWithNs.getName.getNamespaceURI mustEqual ns
      schemaWithNs.getName.getLocalPart mustEqual sft.getTypeName
    }

    "handle cql functions" in {
      val sftName = defaultSft.getTypeName
      val filters = Seq("name = 'name1'", "IN('fid-1')", "bbox(geom, 44, 48, 46, 50)",
        "bbox(geom, 44, 48, 46, 50) AND dtg DURING 2010-05-07T12:00:00.000Z/2010-05-07T13:00:00.000Z")
      val positives = filters.map(f => new Query(sftName, ECQL.toFilter(s"$f AND geometryType(geom) = 'Point'")))
      val negatives = filters.map(f => new Query(sftName, ECQL.toFilter(s"$f AND geometryType(geom) = 'Polygon'")))

      val pStrategies = positives.map(ds.getQueryPlan(_))
      val nStrategies = negatives.map(ds.getQueryPlan(_))

      forall(pStrategies ++ nStrategies)(_ must haveLength(1))
      pStrategies.map(_.head.filter.strategy) mustEqual
          Seq(StrategyType.ATTRIBUTE, StrategyType.RECORD, StrategyType.Z2, StrategyType.Z3)
      nStrategies.map(_.head.filter.strategy) mustEqual
          Seq(StrategyType.ATTRIBUTE, StrategyType.RECORD, StrategyType.Z2, StrategyType.Z3)

      forall(positives) { query =>
        val result = ds.getFeatureSource(sftName).getFeatures(query).features().toList
        result must haveLength(1)
        result.head.getID mustEqual "fid-1"
      }
      forall(negatives) { query =>
        val result = ds.getFeatureSource(sftName).getFeatures(query).features().toList
        result must beEmpty
      }
    }

    "avoid deduplication when possible" in {
      val sft = createNewSchema(s"name:String:index=join:cardinality=high,dtg:Date,*geom:Point:srid=4326")
      addFeature(sft, ScalaSimpleFeature.create(sft, "1", "bob", "2010-05-07T12:00:00.000Z", "POINT(45 45)"))

      val filter = "bbox(geom,-180,-90,180,90) AND dtg DURING 2010-05-07T00:00:00.000Z/2010-05-08T00:00:00.000Z" +
          " AND (name = 'alice' OR name = 'bob' OR name = 'charlie')"
      val query = new Query(sft.getTypeName, ECQL.toFilter(filter))

      val plans = ds.getQueryPlan(query)
      plans must haveLength(1)
      plans.head.hasDuplicates must beFalse
      plans.head must beAnInstanceOf[JoinPlan]
      plans.head.asInstanceOf[JoinPlan].joinQuery.hasDuplicates must beFalse

      val features = ds.getFeatureSource(sft.getTypeName).getFeatures(query).features().toList
      features must haveLength(1)
      features.head.getID mustEqual "1"
    }

    "support bin queries" in {
      import BinAggregatingIterator.BIN_ATTRIBUTE_INDEX
      val sft = createNewSchema(s"name:String,dtg:Date,*geom:Point:srid=4326")

      addFeature(sft, ScalaSimpleFeature.create(sft, "1", "name1", "2010-05-07T00:00:00.000Z", "POINT(45 45)"))
      addFeature(sft, ScalaSimpleFeature.create(sft, "2", "name2", "2010-05-07T01:00:00.000Z", "POINT(45 45)"))

      val query = new Query(sft.getTypeName, ECQL.toFilter("BBOX(geom,40,40,50,50)"))
      query.getHints.put(BIN_TRACK_KEY, "name")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 1000)
      val queryPlanner = new QueryPlanner(sft, ds)
      val results = queryPlanner.runQuery(query, Some(StrategyType.Z2)).map(_.getAttribute(BIN_ATTRIBUTE_INDEX)).toSeq
      forall(results)(_ must beAnInstanceOf[Array[Byte]])
      val bins = results.flatMap(_.asInstanceOf[Array[Byte]].grouped(16).map(Convert2ViewerFunction.decode))
      bins must haveSize(2)
      bins.map(_.trackId) must containAllOf(Seq("name1", "name2").map(_.hashCode.toString))
    }

    "support bin queries with linestrings" in {
      import BinAggregatingIterator.BIN_ATTRIBUTE_INDEX
      val sft = createNewSchema(s"name:String,dtgs:List[Date],dtg:Date,*geom:LineString:srid=4326")
      val dtgs1 = new java.util.ArrayList[Date]
      dtgs1.add(Converters.convert("2010-05-07T00:00:00.000Z", classOf[Date]))
      dtgs1.add(Converters.convert("2010-05-07T00:01:00.000Z", classOf[Date]))
      dtgs1.add(Converters.convert("2010-05-07T00:02:00.000Z", classOf[Date]))
      dtgs1.add(Converters.convert("2010-05-07T00:03:00.000Z", classOf[Date]))
      val dtgs2 = new java.util.ArrayList[Date]
      dtgs2.add(Converters.convert("2010-05-07T01:00:00.000Z", classOf[Date]))
      dtgs2.add(Converters.convert("2010-05-07T01:01:00.000Z", classOf[Date]))
      dtgs2.add(Converters.convert("2010-05-07T01:02:00.000Z", classOf[Date]))
      addFeature(sft, ScalaSimpleFeature.create(sft, "1", "name1", dtgs1, "2010-05-07T00:00:00.000Z", "LINESTRING(40 41, 42 43, 44 45, 46 47)"))
      addFeature(sft, ScalaSimpleFeature.create(sft, "2", "name2", dtgs2, "2010-05-07T01:00:00.000Z", "LINESTRING(50 50, 51 51, 52 52)"))

      val query = new Query(sft.getTypeName, ECQL.toFilter("BBOX(geom,40,40,55,55)"))
      query.getHints.put(BIN_TRACK_KEY, "name")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 1000)
      query.getHints.put(BIN_DTG_KEY, "dtgs")

      val bytes = ds.getFeatureSource(sft.getTypeName).getFeatures(query).features().map(_.getAttribute(BIN_ATTRIBUTE_INDEX)).toList
      forall(bytes)(_ must beAnInstanceOf[Array[Byte]])
      val bins = bytes.flatMap(_.asInstanceOf[Array[Byte]].grouped(16).map(Convert2ViewerFunction.decode))
      bins must haveSize(7)
      val sorted = bins.sortBy(_.dtg)
      sorted(0) mustEqual BasicValues(41, 40, dtgs1(0).getTime, "name1".hashCode.toString)
      sorted(1) mustEqual BasicValues(43, 42, dtgs1(1).getTime, "name1".hashCode.toString)
      sorted(2) mustEqual BasicValues(45, 44, dtgs1(2).getTime, "name1".hashCode.toString)
      sorted(3) mustEqual BasicValues(47, 46, dtgs1(3).getTime, "name1".hashCode.toString)
      sorted(4) mustEqual BasicValues(50, 50, dtgs2(0).getTime, "name2".hashCode.toString)
      sorted(5) mustEqual BasicValues(51, 51, dtgs2(1).getTime, "name2".hashCode.toString)
      sorted(6) mustEqual BasicValues(52, 52, dtgs2(2).getTime, "name2".hashCode.toString)
    }.pendingUntilFixed("GEOMESA-1162 - not supported yet with z-indices")

    "support IN queries without dtg on non-indexed string attributes" in {
      val sft = createNewSchema(s"name:String,dtg:Date,*geom:Point:srid=4326")

      addFeature(sft, ScalaSimpleFeature.create(sft, "1", "name1", "2010-05-07T00:00:00.000Z", "POINT(45 45)"))
      addFeature(sft, ScalaSimpleFeature.create(sft, "2", "name2", "2010-05-07T01:00:00.000Z", "POINT(45 46)"))

      val filter = ECQL.toFilter("name IN('name1','name2') AND BBOX(geom, 40.0,40.0,50.0,50.0)")
      val query = new Query(sft.getTypeName, filter)
      val features = ds.getFeatureSource(sft.getTypeName).getFeatures(query).features.toList
      features.map(DataUtilities.encodeFeature) must containTheSameElementsAs {
        List("1=name1|2010-05-07T00:00:00.000Z|POINT (45 45)", "2=name2|2010-05-07T01:00:00.000Z|POINT (45 46)")
      }
    }

    "support IN queries without dtg on indexed string attributes" in {
      val sft = createNewSchema("name:String:index=true,dtg:Date,*geom:Point:srid=4326")

      addFeature(sft, ScalaSimpleFeature.create(sft, "1", "name1", "2010-05-07T00:00:00.000Z", "POINT(45 45)"))
      addFeature(sft, ScalaSimpleFeature.create(sft, "2", "name2", "2010-05-07T01:00:00.000Z", "POINT(45 46)"))

      val filter = ECQL.toFilter("name IN('name1','name2') AND BBOX(geom, -180.0,-90.0,180.0,90.0)")
      val query = new Query(sft.getTypeName, filter)
      val features = ds.getFeatureSource(sft.getTypeName).getFeatures(query).features.toList
      features.map(DataUtilities.encodeFeature).sorted mustEqual List("1=name1|2010-05-07T00:00:00.000Z|POINT (45 45)", "2=name2|2010-05-07T01:00:00.000Z|POINT (45 46)").sorted
    }

    "kill queries after a configurable timeout" in {
      val params = Map(
        "connector" -> ds.connector,
        "tableName" -> ds.catalogTable,
        AccumuloDataStoreParams.queryTimeoutParam.getName -> "1"
      )

      val dsWithTimeout = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
      val reader = dsWithTimeout.getFeatureReader(new Query(defaultSft.getTypeName, Filter.INCLUDE), Transaction.AUTO_COMMIT)
      reader.isClosed must beFalse
      reader.isClosed must eventually(10, new Duration(1000))(beTrue) // reaper thread runs every 5 seconds
    }

    "allow query strategy to be specified via view params" in {
      val filter = "BBOX(geom,40,40,50,50) and dtg during 2010-05-07T00:00:00.000Z/2010-05-08T00:00:00.000Z and name='name1'"
      val query = new Query(defaultSft.getTypeName, ECQL.toFilter(filter))

      def expectStrategy(strategy: String) = {
        val explain = new ExplainString
        ds.getQueryPlan(query, explainer = explain)
        explain.toString().split("\n").map(_.trim).filter(_.startsWith("Strategy 1 of 1:")) mustEqual Array(s"Strategy 1 of 1: $strategy")
        val res = ds.getFeatureSource(defaultSft.getTypeName).getFeatures(query).features().map(_.getID).toList
        res must containTheSameElementsAs(Seq("fid-1"))
      }

      query.getHints.put(QUERY_STRATEGY_KEY, StrategyType.ATTRIBUTE)
      expectStrategy("AttributeIdxStrategy")

      query.getHints.put(QUERY_STRATEGY_KEY, StrategyType.Z2)
      expectStrategy("Z2IdxStrategy")

      query.getHints.put(QUERY_STRATEGY_KEY, StrategyType.Z3)
      expectStrategy("Z3IdxStrategy")

      query.getHints.put(QUERY_STRATEGY_KEY, StrategyType.RECORD)
      expectStrategy("RecordIdxStrategy")

      val viewParams =  new java.util.HashMap[String, String]
      query.getHints.put(Hints.VIRTUAL_TABLE_PARAMETERS, viewParams)

      query.getHints.remove(QUERY_STRATEGY_KEY)
      viewParams.put("STRATEGY", "attribute")
      expectStrategy("AttributeIdxStrategy")

      query.getHints.remove(QUERY_STRATEGY_KEY)
      viewParams.put("STRATEGY", "Z2")
      expectStrategy("Z2IdxStrategy")

      query.getHints.remove(QUERY_STRATEGY_KEY)
      viewParams.put("STRATEGY", "Z3")
      expectStrategy("Z3IdxStrategy")

      query.getHints.remove(QUERY_STRATEGY_KEY)
      viewParams.put("STRATEGY", "RECORD")
      expectStrategy("RecordIdxStrategy")

      success
    }

    "allow for loose bounding box config" >> {

      val bbox = "bbox(geom,45.000000001,49.000000001,46,50)"
      val z2Query = new Query(defaultSft.getTypeName, ECQL.toFilter(bbox))
      val z3Query = new Query(defaultSft.getTypeName,
        ECQL.toFilter(s"$bbox AND dtg DURING 2010-05-07T12:25:00.000Z/2010-05-07T12:35:00.000Z"))

      val params = Map(
        "connector" -> ds.connector,
        "tableName" -> ds.catalogTable,
        AccumuloDataStoreParams.looseBBoxParam.getName -> "false"
      )

      val strictDs = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]

      "with loose bbox as default" >> {
        "for z2 index" >> {
          val looseReader = ds.getFeatureReader(z2Query, Transaction.AUTO_COMMIT)
          try {
            looseReader.hasNext must beTrue
          } finally {
            looseReader.close()
          }
        }
        "for z3 index" >> {
          val looseReader = ds.getFeatureReader(z3Query, Transaction.AUTO_COMMIT)
          try {
            looseReader.hasNext must beTrue
          } finally {
            looseReader.close()
          }
        }
      }

      "with strict configuration through data store params" >> {
        "for z2 index" >> {
          val strictReader = strictDs.getFeatureReader(z2Query, Transaction.AUTO_COMMIT)
          try {
            strictReader.hasNext must beFalse
          } finally {
            strictReader.close()
          }
        }
        "for z3 index" >> {
          val strictReader = strictDs.getFeatureReader(z3Query, Transaction.AUTO_COMMIT)
          try {
            strictReader.hasNext must beFalse
          } finally {
            strictReader.close()
          }
        }
      }

      "with query hints" >> {
        "overriding loose config" >> {
          "for z2 index" >> {
            val strictZ2Query = new Query(z2Query)
            strictZ2Query.getHints.put(QueryHints.LOOSE_BBOX, java.lang.Boolean.FALSE)
            val strictReader = ds.getFeatureReader(strictZ2Query, Transaction.AUTO_COMMIT)
            try {
              strictReader.hasNext must beFalse
            } finally {
              strictReader.close()
            }
          }
          "for z3 index" >> {
            val strictZ3Query = new Query(z3Query)
            strictZ3Query.getHints.put(QueryHints.LOOSE_BBOX, java.lang.Boolean.FALSE)
            val strictReader = ds.getFeatureReader(strictZ3Query, Transaction.AUTO_COMMIT)
            try {
              strictReader.hasNext must beFalse
            } finally {
              strictReader.close()
            }
          }
        }

        "overriding strict config" >> {
          "for z2 index" >> {
            val looseZ2Query = new Query(z2Query)
            looseZ2Query.getHints.put(QueryHints.LOOSE_BBOX, java.lang.Boolean.TRUE)
            val looseReader = strictDs.getFeatureReader(looseZ2Query, Transaction.AUTO_COMMIT)
            try {
              looseReader.hasNext must beTrue
            } finally {
              looseReader.close()
            }
          }

          "for z3 index" >> {
            val looseZ3Query = new Query(z3Query)
            looseZ3Query.getHints.put(QueryHints.LOOSE_BBOX, java.lang.Boolean.TRUE)
            val looseReader = strictDs.getFeatureReader(looseZ3Query, Transaction.AUTO_COMMIT)
            try {
              looseReader.hasNext must beTrue
            } finally {
              looseReader.close()
            }
          }
        }
      }
    }

    "be able to run explainQuery" in {
      val filter = ECQL.toFilter("INTERSECTS(geom, POLYGON ((41 28, 42 28, 42 29, 41 29, 41 28)))")
      val query = new Query(defaultSft.getTypeName, filter)

      val out = new ExplainString()
      ds.getQueryPlan(query, explainer = out)

      val explanation = out.toString()
      explanation must not be null
      explanation.trim must not(beEmpty)
    }
  }
}
