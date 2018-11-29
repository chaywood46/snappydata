/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package io.snappydata.impl

import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.{Callable, ExecutionException, ExecutorService, Executors, Future, TimeUnit}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import com.gemstone.gemfire.cache.RegionDestroyedException
import com.gemstone.gemfire.internal.LogWriterImpl
import com.gemstone.gemfire.internal.cache.{ExternalTableMetaData, GemfireCacheHelper, LocalRegion, PolicyTableData}
import com.gemstone.gemfire.internal.shared.SystemProperties
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.diag.HiveTablesVTI
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState
import io.snappydata.Constant
import io.snappydata.sql.catalog.{CatalogObjectType, ConnectorExternalCatalog, SnappyExternalCatalog}
import io.snappydata.thrift._
import org.apache.log4j.{Level, LogManager}

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.{CatalogDatabase, CatalogTable}
import org.apache.spark.sql.collection.ToolsCallbackInit
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils.CaseInsensitiveMutableHashMap
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.hive.{HiveClientUtil, SnappyHiveExternalCatalog}
import org.apache.spark.sql.policy.PolicyProperties
import org.apache.spark.sql.sources.{DataSourceRegister, JdbcExtendedUtils}
import org.apache.spark.{Logging, SparkConf}

class StoreHiveCatalog extends ExternalCatalog with Logging {

  private val THREAD_GROUP_NAME = "StoreCatalog Client Group"

  private val INIT = 0
  private val COLUMN_TABLE_SCHEMA = 1
  // all hive tables that are expected to be in DataDictionary
  // this will exclude external tables like parquet tables, stream tables
  private val GET_ALL_TABLES_MANAGED_IN_DD = 2
  private val REMOVE_TABLE = 3
  private val GET_COL_TABLE = 4
  private val GET_TABLE = 5
  private val GET_HIVE_TABLES = 6
  private val GET_POLICIES = 7
  private val GET_METADATA = 8
  private val UPDATE_METADATA = 9
  private val CLOSE_HMC = 10

  private val catalogQueriesExecutorService: ExecutorService = {
    val hmsThreadGroup = LogWriterImpl.createThreadGroup(THREAD_GROUP_NAME, Misc.getI18NLogWriter)
    val hmsClientThreadFactory = GemfireCacheHelper.createThreadFactory(
      hmsThreadGroup, "StoreCatalog Client")
    Executors.newFixedThreadPool(1, hmsClientThreadFactory)
  }

  private val initFuture: Future[_] = {
    // run a task to initialize the hive catalog for the thread
    // Assumption is that this should be outside any lock
    val q = new CatalogQuery[Unit](INIT, tableName = null, schemaName = null, skipLocks = false)
    catalogQueriesExecutorService.submit(q)
  }

  private var externalCatalog: SnappyHiveExternalCatalog = _

  override def waitForInitialization(): Boolean = {
    // skip for call from within initHMC
    !Thread.currentThread().getThreadGroup.getName.equals(
      THREAD_GROUP_NAME) && GemFireStore.handleCatalogInit(this.initFuture)
  }

  override def isColumnTable(schema: String, tableName: String, skipLocks: Boolean): Boolean = {
    val q = new CatalogQuery[CatalogTable](GET_TABLE, tableName, schema, skipLocks)
    val table = handleFutureResult(catalogQueriesExecutorService.submit(q))
    (table ne null) && CatalogObjectType.isColumnTable(CatalogObjectType.getTableType(table))
  }

  override def isRowTable(table: CatalogTableObject): Boolean = {
    CatalogObjectType.getTableType(table.getTableType,
      table.getProperties.asScala.toMap, table.getStorage.getProperties.asScala.toMap,
      Option(table.getProvider)) == CatalogObjectType.Row
  }

  override def getCatalogTables(skipLocks: Boolean): java.util.List[ExternalTableMetaData] = {
    // skip if this is already the catalog lookup thread (Hive dropTable
    //   invokes getTables again)
    if (java.lang.Boolean.TRUE.equals(HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.get())) {
      return Collections.emptyList()
    }
    val q = new CatalogQuery[Seq[ExternalTableMetaData]](GET_HIVE_TABLES,
      tableName = null, schemaName = null, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q)).asJava
  }

  override def getColumnTableSchemaAsJson(schema: String, tableName: String,
      skipLocks: Boolean): String = {
    val q = new CatalogQuery[String](COLUMN_TABLE_SCHEMA, tableName, schema, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q))
  }

  override def getPolicies(skipLocks: Boolean): java.util.List[PolicyTableData] = {
    // skip if this is already the catalog lookup thread (Hive dropTable
    //   invokes getTables again)
    if (java.lang.Boolean.TRUE.equals(HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.get())) {
      return Collections.emptyList()
    }
    val q = new CatalogQuery[Seq[PolicyTableData]](GET_POLICIES, tableName = null,
      schemaName = null, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q)).asJava
  }

  override def getCatalogTableMetadata(schema: String, tableName: String,
      skipLocks: Boolean): ExternalTableMetaData = {
    val q = new CatalogQuery[ExternalTableMetaData](GET_COL_TABLE, tableName, schema, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q))
  }

  override def fillCatalogMetadata(operation: Int, request: CatalogMetadataRequest,
      result: CatalogMetadataDetails, skipLocks: Boolean): LocalRegion = {
    val q = new CatalogQuery[LocalRegion](GET_METADATA,
      tableName = null, schemaName = null, skipLocks, operation, request, result)
    handleFutureResult(catalogQueriesExecutorService.submit(q))
  }

  override def updateCatalogMetadata(operation: Int, request: CatalogMetadataDetails,
      user: String, skipLocks: Boolean): Unit = {
    val q = new CatalogQuery[Unit](UPDATE_METADATA,
      tableName = null, schemaName = null, skipLocks, operation, getRequest = null, request, user)
    handleFutureResult(catalogQueriesExecutorService.submit(q))
  }

  override def getCatalogSchemaVersion: Long = externalCatalog.getCatalogSchemaVersion

  override def getAllStoreTablesInCatalog(
      skipLocks: Boolean): java.util.Map[String, java.util.List[String]] = {
    val q = new CatalogQuery[Seq[(String, String)]](GET_ALL_TABLES_MANAGED_IN_DD,
      tableName = null, schemaName = null, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q)).groupBy(p => p._1)
        .mapValues(_.map(_._2).asJava).asJava
  }

  override def removeTableIfExists(schema: String, table: String, skipLocks: Boolean): Unit = {
    val q = new CatalogQuery[Unit](REMOVE_TABLE, table, schema, skipLocks)
    handleFutureResult(catalogQueriesExecutorService.submit(q))
  }

  override def catalogSchemaName: String = SystemProperties.SNAPPY_HIVE_METASTORE

  override def close(): Unit = {
    val q = new CatalogQuery[Unit](CLOSE_HMC, tableName = null, schemaName = null, skipLocks = true)
    try {
      this.catalogQueriesExecutorService.submit(q).get(5, TimeUnit.SECONDS)
    } catch {
      case _: Exception => // ignore
    }
    this.catalogQueriesExecutorService.shutdown()
    try {
      this.catalogQueriesExecutorService.awaitTermination(5, TimeUnit.SECONDS)
    } catch {
      case _: InterruptedException => // ignore
    }
  }

  private def handleFutureResult[T](f: Future[T]): T = {
    try {
      f.get()
    } catch {
      case e: ExecutionException => throw new RuntimeException(e.getCause)
      case e: Exception => throw new RuntimeException(e)
    }
  }

  private final class CatalogQuery[R](qType: Int, tableName: String, schemaName: String,
      skipLocks: Boolean, catalogOperation: Int = 0, getRequest: CatalogMetadataRequest = null,
      updateRequestOrResult: CatalogMetadataDetails = null, user: String = null)
      extends Callable[R] {

    override def call(): R = {
      HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.set(java.lang.Boolean.TRUE)
      try {
        if (skipLocks) {
          GfxdDataDictionary.SKIP_LOCKS.set(true)
        }
        invoke()
      } finally {
        if (skipLocks) {
          GfxdDataDictionary.SKIP_LOCKS.set(false)
        }
      }
    }

    private def invoke(): R = qType match {
      case INIT =>
        // Take read/write lock on metastore. Because of this all the servers
        // will initiate their hive client one by one. This is important as we
        // have downgraded the ISOLATION LEVEL from SERIALIZABLE to REPEATABLE READ
        val hiveClientObject = "StoreCatalogClient"
        val lockService = Misc.getMemStoreBooting.getDDLLockService

        val lockOwner = lockService.newCurrentOwner()
        var writeLock = false
        var dlockTaken = lockService.lock(hiveClientObject,
          GfxdLockSet.MAX_LOCKWAIT_VAL, -1)
        var lockTaken = false
        try {
          // downgrade dlock to a read lock if hive metastore has already
          // been initialized by some other server
          if (dlockTaken && (Misc.getRegionByPath("/" + Misc
              .SNAPPY_HIVE_METASTORE + "/FUNCS", false) ne null)) {
            lockService.unlock(hiveClientObject)
            dlockTaken = false
            lockTaken = lockService.readLock(hiveClientObject, lockOwner,
              GfxdLockSet.MAX_LOCKWAIT_VAL)
            // reduce log4j level to avoid "function exists" warnings
            val log4jLogger = LogManager.getRootLogger
            if (log4jLogger.getEffectiveLevel == Level.WARN) {
              // log4jLogger.setLevel(Level.ERROR) SW:
            }
          } else {
            lockTaken = lockService.writeLock(hiveClientObject, lockOwner,
              GfxdLockSet.MAX_LOCKWAIT_VAL, -1)
            writeLock = true
          }
          initCatalog().asInstanceOf[R]
        } finally {
          if (lockTaken) {
            if (writeLock) {
              lockService.writeUnlock(hiveClientObject, lockOwner)
            } else {
              lockService.readUnlock(hiveClientObject)
            }
          }
          if (dlockTaken) {
            lockService.unlock(hiveClientObject)
          }
        }

      case COLUMN_TABLE_SCHEMA => externalCatalog.getTableOption(schemaName, tableName) match {
        case None => null.asInstanceOf[R]
        case Some(t) => t.schema.json.asInstanceOf[R]
      }

      case GET_TABLE => externalCatalog.getTableOption(schemaName, tableName) match {
        case None => null.asInstanceOf[R]
        case Some(t) => t.asInstanceOf[R]
      }

      case GET_HIVE_TABLES =>
        // exclude row tables and policies from the list of hive tables
        val hiveTables = new mutable.ArrayBuffer[ExternalTableMetaData]
        for (table <- externalCatalog.getAllTables()) {
          val tableType = CatalogObjectType.getTableType(table)
          if (tableType != CatalogObjectType.Row && tableType != CatalogObjectType.Policy) {
            val storageProperties = table.storage.properties
            val tblDataSourcePath = getDataSourcePath(storageProperties)
            val driverClass = storageProperties.get("driver") match {
              case None => ""
              case Some(c) => c
            }
            // exclude policies also from the list of hive tables
            val metaData = new ExternalTableMetaData(table.identifier.table,
              table.database, tableType.toString, null, -1,
              -1, null, null, null, null,
              tblDataSourcePath, driverClass)
            metaData.provider = table.provider match {
              case None => ""
              case Some(p) => p
            }
            metaData.shortProvider = metaData.provider
            try {
              val c = DataSource.lookupDataSource(metaData.provider)
              if (classOf[DataSourceRegister].isAssignableFrom(c)) {
                metaData.shortProvider = c.newInstance.asInstanceOf[DataSourceRegister].shortName()
              }
            } catch {
              case NonFatal(_) => // ignore
            }
            metaData.columns = ExternalStoreUtils.getColumnMetadata(table.schema)
            if (tableType == CatalogObjectType.View) {
              metaData.viewText = table.viewOriginalText match {
                case None => table.viewText match {
                  case None => ""
                  case Some(t) => t
                }
                case Some(t) => t
              }
            }
            hiveTables += metaData
          }
        }
        hiveTables.asInstanceOf[R]

      case GET_POLICIES => externalCatalog.getAllTables().collect {
        case table if CatalogObjectType.isPolicy(table) =>
          val tableProperties = table.properties
          val policyFor = tableProperties(PolicyProperties.policyFor)
          val policyApplyTo = tableProperties(PolicyProperties.policyApplyTo)
          val targetTable = tableProperties(PolicyProperties.targetTable)
          val filter = tableProperties(PolicyProperties.filterString)
          val owner = tableProperties(PolicyProperties.policyOwner)
          val metaData = new PolicyTableData(table.identifier.table,
            policyFor, policyApplyTo, targetTable, filter, owner)
          metaData.columns = ExternalStoreUtils.getColumnMetadata(table.schema)
          metaData
      }.asInstanceOf[R]

      case GET_ALL_TABLES_MANAGED_IN_DD => externalCatalog.getAllTables().collect {
        case table if CatalogObjectType.isTableBackedByRegion(
          CatalogObjectType.getTableType(table)) => table.database -> table.identifier.table
      }.asInstanceOf[R]

      case REMOVE_TABLE => externalCatalog.dropTable(schemaName, tableName,
        ignoreIfNotExists = true, purge = false).asInstanceOf[R]

      case GET_COL_TABLE => externalCatalog.getTableOption(schemaName, tableName) match {
        case None => null.asInstanceOf[R]
        case Some(table) =>
          val qualifiedName = table.identifier.unquotedString
          val schema = table.schema
          val parameters = new CaseInsensitiveMutableHashMap[String](table.storage.properties)
          val partitions = parameters.get(ExternalStoreUtils.BUCKETS) match {
            case None =>
              // get the partitions from the actual table if not in catalog
              val pattrs = Misc.getRegionForTableByPath(qualifiedName, true)
                  .getAttributes.getPartitionAttributes
              if (pattrs ne null) pattrs.getTotalNumBuckets else 1
            case Some(buckets) => buckets.toInt
          }
          val baseTable = parameters.get(SnappyExternalCatalog.BASETABLE_PROPERTY) match {
            case None => ""
            case Some(b) => b
          }
          val dmls = JdbcExtendedUtils.
              getInsertOrPutString(qualifiedName, schema, putInto = false)
          val dependentRelations = SnappyExternalCatalog.getDependents(table.properties)
          val columnBatchSize = parameters.get(ExternalStoreUtils.COLUMN_BATCH_SIZE) match {
            case None => 0
            case Some(s) => ExternalStoreUtils.sizeAsBytes(s, ExternalStoreUtils.COLUMN_BATCH_SIZE)
          }
          val columnMaxDeltaRows = parameters.get(ExternalStoreUtils.COLUMN_MAX_DELTA_ROWS) match {
            case None => 0
            case Some(d) => ExternalStoreUtils.checkPositiveNum(
              d.toInt, ExternalStoreUtils.COLUMN_MAX_DELTA_ROWS)
          }
          val tableType = CatalogObjectType.getTableType(table)
          val compressionCodec = parameters.get(ExternalStoreUtils.COMPRESSION_CODEC) match {
            case None =>
              if (tableType == CatalogObjectType.Column) Constant.DEFAULT_CODEC else "NA"
            case Some(c) => c
          }
          val tblDataSourcePath = getDataSourcePath(parameters)
          val driverClass = parameters.get("driver") match {
            case None => ""
            case Some(c) => c
          }
          new ExternalTableMetaData(qualifiedName, schema, tableType.toString,
            ExternalStoreUtils.getExternalStoreOnExecutor(parameters, partitions, qualifiedName,
              schema), columnBatchSize, columnMaxDeltaRows, compressionCodec, baseTable, dmls,
            dependentRelations, tblDataSourcePath, driverClass).asInstanceOf[R]
      }

      case GET_METADATA =>
        fillCatalogMetadata(catalogOperation, getRequest, updateRequestOrResult).asInstanceOf[R]

      case UPDATE_METADATA =>
        updateCatalogMetadata(catalogOperation, updateRequestOrResult, user).asInstanceOf[R]

      case CLOSE_HMC => SnappyHiveExternalCatalog.close().asInstanceOf[R]

      case q => throw new IllegalStateException(s"StoreCatalogClient: unknown query option $q")
    }

    override def toString: String = {
      if ((getRequest eq null) && (updateRequestOrResult eq null)) {
        s"StoreCatalog: queryType = $qType schema = $schemaName table = $tableName " +
            s"skipLocks = $skipLocks"
      } else {
        val request = if (getRequest ne null) getRequest else updateRequestOrResult
        s"StoreCatalog: operation = $catalogOperation request=$request skipLocks = $skipLocks"
      }
    }

    private def initCatalog(): Unit = {
      ExternalStoreUtils.registerBuiltinDrivers()

      // wait for stats sampler initialization
      Misc.waitForSamplerInitialization()
      val numRetries = 40
      var count = 0
      var done = false
      while (!done) {
        try {
          val conf = new SparkConf
          for ((k, v) <- Misc.getMemStoreBooting.getBootProperties.asScala) {
            val key = k.toString
            if ((v ne null) && (key.startsWith(Constant.SPARK_PREFIX) ||
                key.startsWith(Constant.PROPERTY_PREFIX))) {
              conf.set(key, v.toString)
            }
          }
          externalCatalog = HiveClientUtil.getOrCreateExternalCatalog(null, conf)
          done = true
        } catch {
          case e: Exception =>
            var t: Throwable = e
            var noDataStoreFound = false
            while ((t ne null) && !noDataStoreFound) {
              noDataStoreFound = t.isInstanceOf[SQLException] &&
                  SQLState.NO_DATASTORE_FOUND.startsWith(
                    t.asInstanceOf[SQLException].getSQLState)
              t = t.getCause
            }
            // wait for some time and retry if no data store found error
            // is thrown due to region not being initialized
            if (count < numRetries && noDataStoreFound) {
              logWarning("StoreCatalog.HMSQuery.initCatalog: No datastore found " +
                  "while initializing Hive metastore client. " +
                  "Will retry initialization after 3 seconds. " +
                  "Exception received is " + t)
              if (isDebugEnabled) {
                logWarning("Exception stacktrace:", e)
              }
              count += 1
              Thread.sleep(3000)
            } else {
              throw new IllegalStateException(e)
            }
        }
      }
    }

    private def getDataSourcePath(properties: scala.collection.Map[String, String]): String = {
      properties.get("path") match {
        case Some(p) if !p.isEmpty => p
        // for external connectors like GemFire
        case _ => properties.get("region.path") match {
          case None => ""
          case Some(p) => p
        }
      }
    }
  }

  private def metadata(result: CatalogMetadataDetails): LocalRegion = {
    result.setCatalogSchemaVersion(externalCatalog.getCatalogSchemaVersion)
    // dummy null result for LocalRegion to avoid adding a return null in all calling points
    null
  }

  private def pattern(request: CatalogMetadataRequest): String =
    if (request.isSetNameOrPattern) request.getNameOrPattern else "*"

  private def columnList(columns: Array[String]): java.util.List[String] = {
    if (columns.length == 0) Collections.emptyList() else java.util.Arrays.asList(columns: _*)
  }

  private def partitionSpec(request: CatalogMetadataRequest): Option[TablePartitionSpec] =
    if (request.isSetProperties) Some(request.getProperties.asScala.toMap) else None

  private def fillCatalogMetadata(operation: Int, request: CatalogMetadataRequest,
      result: CatalogMetadataDetails): LocalRegion = operation match {

    case snappydataConstants.CATALOG_GET_SCHEMA =>
      try {
        val catalogSchema = externalCatalog.getDatabase(request.getSchemaName)
        val schemaObj = new CatalogSchemaObject(catalogSchema.name, catalogSchema.description,
          catalogSchema.locationUri, catalogSchema.properties.asJava)
        metadata(result.setCatalogSchema(schemaObj))
      } catch {
        case _: AnalysisException => metadata(result)
      }

    case snappydataConstants.CATALOG_SCHEMA_EXISTS =>
      metadata(result.setExists(externalCatalog.databaseExists(request.getSchemaName)))

    case snappydataConstants.CATALOG_LIST_SCHEMAS =>
      metadata(result.setNames(externalCatalog.listDatabases(pattern(request)).asJava))

    case snappydataConstants.CATALOG_GET_TABLE =>
      externalCatalog.getTableOption(request.getSchemaName, request.getNameOrPattern) match {
        case None => metadata(result)
        case Some(table) =>
          val tableObj = ConnectorExternalCatalog.convertFromCatalogTable(table)
          val tableType = CatalogObjectType.getTableType(table)
          if (CatalogObjectType.isTableBackedByRegion(tableType)) {
            // a query during drop table from client can show region as destroyed here which
            // is indicated by a lack of catalog schema version in the result
            try {
              val (relationInfo, regionOpt) = externalCatalog.getRelationInfo(
                table.identifier.unquotedString, !CatalogObjectType.isColumnTable(tableType))
              tableObj.setPartitionColumns(columnList(relationInfo.partitioningCols))
              tableObj.setIndexColumns(columnList(relationInfo.indexCols))
              tableObj.setPrimaryKeyColumns(columnList(relationInfo.pkCols))
              tableObj.setNumBuckets(if (relationInfo.isPartitioned) relationInfo.numBuckets else 0)
              metadata(result.setCatalogTable(tableObj))
              if (regionOpt.isDefined) regionOpt.get else null
            } catch {
              case _: RegionDestroyedException => result.setCatalogTable(tableObj); null
            }
          } else metadata(result.setCatalogTable(tableObj))
      }

    case snappydataConstants.CATALOG_TABLE_EXISTS =>
      metadata(result.setExists(externalCatalog.tableExists(request.getSchemaName,
        request.getNameOrPattern)))

    case snappydataConstants.CATALOG_LIST_TABLES =>
      metadata(result.setNames(externalCatalog.listTables(request.getSchemaName,
        pattern(request)).asJava))

    case snappydataConstants.CATALOG_GET_PARTITION =>
      externalCatalog.getPartitionOption(request.getSchemaName, request.getNameOrPattern,
        partitionSpec(request).get) match {
        case None => metadata(result)
        case Some(partition) => metadata(result.setCatalogPartitions(Collections.singletonList(
          ConnectorExternalCatalog.convertFromCatalogPartition(partition))))
      }

    case snappydataConstants.CATALOG_LIST_PARTITION_NAMES =>
      metadata(result.setNames(externalCatalog.listPartitionNames(request.getSchemaName,
        request.getNameOrPattern, partitionSpec(request)).asJava))

    case snappydataConstants.CATALOG_LIST_PARTITIONS =>
      metadata(result.setCatalogPartitions(externalCatalog.listPartitions(request.getSchemaName,
        request.getNameOrPattern, partitionSpec(request)).map(
        ConnectorExternalCatalog.convertFromCatalogPartition).asJava))

    case snappydataConstants.CATALOG_GET_FUNCTION =>
      try {
        val function = externalCatalog.getFunction(request.getSchemaName, request.getNameOrPattern)
        metadata(result.setCatalogFunction(
          ConnectorExternalCatalog.convertFromCatalogFunction(function)))
      } catch {
        case _: AnalysisException => metadata(result)
      }

    case snappydataConstants.CATALOG_FUNCTION_EXISTS =>
      metadata(result.setExists(externalCatalog.functionExists(request.getSchemaName,
        request.getNameOrPattern)))

    case snappydataConstants.CATALOG_LIST_FUNCTIONS =>
      metadata(result.setNames(externalCatalog.listFunctions(request.getSchemaName,
        request.getNameOrPattern).asJava))

    case _ => throw new IllegalArgumentException(
      s"Unexpected catalog metadata read operation = $operation")
  }

  private def checkSchemaPermission(schema: String, name: String, user: String): Unit = {
    val callbacks = ToolsCallbackInit.toolsCallback
    if (callbacks ne null) {
      // allow creating entry for dummy table by anyone
      if (!(schema.equalsIgnoreCase(SchemaDescriptor.IBM_SYSTEM_SCHEMA_NAME)
          && name.equalsIgnoreCase(JdbcExtendedUtils.DUMMY_TABLE_NAME))) {
        callbacks.checkSchemaPermission(schema, user)
      }
    }
  }

  private def getCatalogTableForWrite(request: CatalogMetadataDetails,
      user: String): CatalogTable = {
    val tableObj = request.getCatalogTable
    // bucket owners must be null/empty here since it is not part of catalog itself and
    // RelationInfo is neither required nor can be obtained here due to absence of "session"
    assert(tableObj.getBucketOwnersSize == 0, "unexpected bucket owners in updateCatalogMetadata")
    checkSchemaPermission(tableObj.getSchemaName, tableObj.getTableName, user)
    ConnectorExternalCatalog.convertToCatalogTable(request, session = null)._1
  }

  private def updateCatalogMetadata(operation: Int, request: CatalogMetadataDetails,
      user: String): Unit = operation match {

    case snappydataConstants.CATALOG_CREATE_SCHEMA =>
      assert(request.isSetCatalogSchema, "CREATE SCHEMA: expected catalogSchema to be set")
      val schemaObj = request.getCatalogSchema
      val catalogSchema = CatalogDatabase(schemaObj.getName, schemaObj.getDescription,
        schemaObj.getLocationUri, schemaObj.getProperties.asScala.toMap)
      externalCatalog.createDatabase(catalogSchema, request.exists)

    case snappydataConstants.CATALOG_DROP_SCHEMA =>
      assert(request.getNamesSize == 1, "DROP SCHEMA: unexpected names = " + request.getNames)
      externalCatalog.dropDatabase(request.getNames.get(0), request.exists, request.updateFlag)

    case snappydataConstants.CATALOG_CREATE_TABLE =>
      assert(request.isSetCatalogTable, "CREATE TABLE: expected catalogTable to be set")
      externalCatalog.createTable(getCatalogTableForWrite(request, user), request.exists)

    case snappydataConstants.CATALOG_DROP_TABLE =>
      assert(request.getNamesSize == 2, "DROP TABLE: unexpected names = " + request.getNames)
      val schema = request.getNames.get(0)
      val table = request.getNames.get(1)
      checkSchemaPermission(schema, table, user)
      externalCatalog.dropTable(schema, table, request.exists, request.updateFlag)

    case snappydataConstants.CATALOG_ALTER_TABLE =>
      assert(request.isSetCatalogTable, "ALTER TABLE: expected catalogTable to be set")
      externalCatalog.alterTable(getCatalogTableForWrite(request, user))

    case snappydataConstants.CATALOG_CREATE_FUNCTION =>
      assert(request.isSetCatalogFunction, "CREATE FUNCTION: expected catalogFunction to be set")
      val functionObj = request.getCatalogFunction
      val schema = functionObj.getSchemaName
      checkSchemaPermission(schema, functionObj.getFunctionName, user)
      externalCatalog.createFunction(schema,
        ConnectorExternalCatalog.convertToCatalogFunction(functionObj))

    case snappydataConstants.CATALOG_DROP_FUNCTION =>
      assert(request.getNamesSize == 2, "DROP FUNCTION: unexpected names = " + request.getNames)
      val schema = request.getNames.get(0)
      val function = request.getNames.get(1)
      checkSchemaPermission(schema, function, user)
      externalCatalog.dropFunction(schema, function)

    case snappydataConstants.CATALOG_RENAME_FUNCTION =>
      assert(request.getNamesSize == 3, "RENAME FUNCTION: unexpected names = " + request.getNames)
      val schema = request.getNames.get(0)
      val oldName = request.getNames.get(1)
      val newName = request.getNames.get(2)
      checkSchemaPermission(schema, newName, user)
      externalCatalog.renameFunction(schema, oldName, newName)

    case _ => throw new IllegalArgumentException(
      s"Unexpected catalog metadata write operation = $operation, args = $request")
  }
}