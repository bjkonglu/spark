/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming

import java.util.Optional

import scala.collection.JavaConverters._
import scala.collection.mutable.{Map => MutableMap}

import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.expressions.{Alias, CurrentBatchTimestamp, CurrentDate, CurrentTimestamp}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan, Project}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.datasources.v2.{StreamingDataSourceV2Relation, WriteToDataSourceV2}
import org.apache.spark.sql.execution.streaming.sources.{InternalRowMicroBatchWriter, MicroBatchWriter}
import org.apache.spark.sql.sources.v2.{DataSourceOptions, DataSourceV2, MicroBatchReadSupport, StreamWriteSupport}
import org.apache.spark.sql.sources.v2.reader.streaming.{MicroBatchReader, Offset => OffsetV2}
import org.apache.spark.sql.sources.v2.writer.SupportsWriteInternalRow
import org.apache.spark.sql.streaming.{OutputMode, ProcessingTime, Trigger}
import org.apache.spark.util.{Clock, Utils}

class MicroBatchExecution(
                           sparkSession: SparkSession,
                           name: String,
                           checkpointRoot: String,
                           analyzedPlan: LogicalPlan,
                           sink: BaseStreamingSink,
                           trigger: Trigger,
                           triggerClock: Clock,
                           outputMode: OutputMode,
                           extraOptions: Map[String, String],
                           deleteCheckpointOnStop: Boolean)
  extends StreamExecution(
    sparkSession, name, checkpointRoot, analyzedPlan, sink,
    trigger, triggerClock, outputMode, deleteCheckpointOnStop) {

  @volatile protected var sources: Seq[BaseStreamingSource] = Seq.empty

  private val readerToDataSourceMap =
    MutableMap.empty[MicroBatchReader, (DataSourceV2, Map[String, String])]

  private val triggerExecutor = trigger match {
    case t: ProcessingTime => ProcessingTimeExecutor(t, triggerClock)
    case OneTimeTrigger => OneTimeExecutor()
    case _ => throw new IllegalStateException(s"Unknown type of trigger: $trigger")
  }

  //TODO 在StreamExecution.runStream()被强制初试化， 将预先定义好的的逻辑(logicalPlan)制作出一个副本
  override lazy val logicalPlan: LogicalPlan = {
    assert(queryExecutionThread eq Thread.currentThread,
      "logicalPlan must be initialized in QueryExecutionThread " +
        s"but the current thread was ${Thread.currentThread}")
    var nextSourceId = 0L
    val toExecutionRelationMap = MutableMap[StreamingRelation, StreamingExecutionRelation]()
    val v2ToExecutionRelationMap = MutableMap[StreamingRelationV2, StreamingExecutionRelation]()
    // We transform each distinct streaming relation into a StreamingExecutionRelation, keeping a
    // map as we go to ensure each identical relation gets the same StreamingExecutionRelation
    // object. For each microbatch, the StreamingExecutionRelation will be replaced with a logical
    // plan for the data within that batch.
    // Note that we have to use the previous `output` as attributes in StreamingExecutionRelation,
    // since the existing logical plan has already used those attributes. The per-microbatch
    // transformation is responsible for replacing attributes with their final values.

    val disabledSources =
      sparkSession.sqlContext.conf.disabledV2StreamingMicroBatchReaders.split(",")

    //FIXME 处理传入的LogicalPlan
    val _logicalPlan = analyzedPlan.transform {
      //TODO {..}是一个偏函数
      //FIXME @ 绑定类型
      case streamingRelation@StreamingRelation(dataSourceV1, sourceName, output) =>
        //TODO 获取streamingRelation对应的StreamingExecutionRelation，以下几个case类似
        toExecutionRelationMap.getOrElseUpdate(streamingRelation, {
          // Materialize source to avoid creating it in every batch
          val metadataPath = s"$resolvedCheckpointRoot/sources/$nextSourceId"
          val source = dataSourceV1.createSource(metadataPath)
          nextSourceId += 1
          logInfo(s"Using Source [$source] from DataSourceV1 named '$sourceName' [$dataSourceV1]")
          StreamingExecutionRelation(source, output)(sparkSession)
        })
      case s@StreamingRelationV2(
      dataSourceV2: MicroBatchReadSupport, sourceName, options, output, _) if
      !disabledSources.contains(dataSourceV2.getClass.getCanonicalName) =>
        v2ToExecutionRelationMap.getOrElseUpdate(s, {
          // Materialize source to avoid creating it in every batch
          val metadataPath = s"$resolvedCheckpointRoot/sources/$nextSourceId"
          val reader = dataSourceV2.createMicroBatchReader(
            Optional.empty(), // user specified schema
            metadataPath,
            new DataSourceOptions(options.asJava))
          nextSourceId += 1
          readerToDataSourceMap(reader) = dataSourceV2 -> options
          logInfo(s"Using MicroBatchReader [$reader] from " +
            s"DataSourceV2 named '$sourceName' [$dataSourceV2]")
          StreamingExecutionRelation(reader, output)(sparkSession)
        })
      //TODO 由于kafka的源产生的logicalPlan是StreamingRelationV2, 所以数据源代码入口如下：
      case s@StreamingRelationV2(dataSourceV2, sourceName, _, output, v1Relation) =>
        v2ToExecutionRelationMap.getOrElseUpdate(s, {
          // Materialize source to avoid creating it in every batch
          val metadataPath = s"$resolvedCheckpointRoot/sources/$nextSourceId"
          if (v1Relation.isEmpty) {
            throw new UnsupportedOperationException(
              s"Data source $sourceName does not support microbatch processing.")
          }
          val source = v1Relation.get.dataSource.createSource(metadataPath)
          nextSourceId += 1
          logInfo(s"Using Source [$source] from DataSourceV2 named '$sourceName' [$dataSourceV2]")
          StreamingExecutionRelation(source, output)(sparkSession)
        })
    }
    //FIXME
    sources = _logicalPlan.collect { case s: StreamingExecutionRelation => s.source }
    uniqueSources = sources.distinct
    _logicalPlan
  }

  /**
    * Repeatedly attempts to run batches as data arrives.
    */
  protected def runActivatedStream(sparkSessionForStream: SparkSession): Unit = {
    triggerExecutor.execute(() => {
      startTrigger()
      //TODO 周期执行下面👇逻辑body
      if (isActive) {
        reportTimeTaken("triggerExecution") {
          if (currentBatchId < 0) {
            // We'll do this initialization only once
            populateStartOffsets(sparkSessionForStream)
            sparkSession.sparkContext.setJobDescription(getBatchDescriptionString)
            logDebug(s"Stream running from $committedOffsets to $availableOffsets")
          } else {
            //FIXME 在spark里构建batch
            constructNextBatch()
          }
          if (dataAvailable) {
            currentStatus = currentStatus.copy(isDataAvailable = true)
            updateStatusMessage("Processing new data")
            //TODO 处理micro-batch
            runBatch(sparkSessionForStream)
          }
        }
        // Report trigger as finished and construct progress object.
        finishTrigger(dataAvailable)

        //TODO 处理完batch数据后再提交commit信息
        if (dataAvailable) {
          //TODO 提交 committed的batchId. 提交一个位点信息为{}的记录， 通过hdfs-client写到hdfs中的目录里
          commitLog.add(currentBatchId)
          //TODO 更新committedOffsets值
          committedOffsets ++= availableOffsets
          logDebug(s"batch ${currentBatchId} committed")
          // We'll increase currentBatchId after we complete processing current batch's data
          currentBatchId += 1
          sparkSession.sparkContext.setJobDescription(getBatchDescriptionString)
        } else {
          currentStatus = currentStatus.copy(isDataAvailable = false)
          updateStatusMessage("Waiting for data to arrive")
          Thread.sleep(pollingDelayMs)
        }
      }
      updateStatusMessage("Waiting for next trigger")
      //TODO
      isActive
    })
  }

  /**
    * Populate the start offsets to start the execution at the current offsets stored in the sink
    * (i.e. avoid reprocessing data that we have already processed). This function must be called
    * before any processing occurs and will populate the following fields:
    *  - currentBatchId
    *  - committedOffsets
    *  - availableOffsets
    * The basic structure of this method is as follows:
    *
    * Identify (from the offset log) the offsets used to run the last batch
    * IF last batch exists THEN
    * Set the next batch to be executed as the last recovered batch
    * Check the commit log to see which batch was committed last
    * IF the last batch was committed THEN
    * Call getBatch using the last batch start and end offsets
    * // ^^^^ above line is needed since some sources assume last batch always re-executes
    * Setup for a new batch i.e., start = last batch end, and identify new end
    * DONE
    * ELSE
    * Identify a brand new batch
    * DONE
    */
  private def populateStartOffsets(sparkSessionToRunBatches: SparkSession): Unit = {

    //TODO 到hdfs目录xxx/offsets下获取上次最新的位点信息
    //FIXME 用于故障恢复[冷备份]
    offsetLog.getLatest() match {
      case Some((latestBatchId, nextOffsets)) =>
        /* First assume that we are re-executing the latest known batch
         * in the offset log */
        currentBatchId = latestBatchId
        availableOffsets = nextOffsets.toStreamProgress(sources)
        /* Initialize committed offsets to a committed batch, which at this
         * is the second latest batch id in the offset log. */
        if (latestBatchId != 0) {
          val secondLatestBatchId = offsetLog.get(latestBatchId - 1).getOrElse {
            throw new IllegalStateException(s"batch ${latestBatchId - 1} doesn't exist")
          }
          committedOffsets = secondLatestBatchId.toStreamProgress(sources)
        }

        // update offset metadata
        nextOffsets.metadata.foreach { metadata =>
          OffsetSeqMetadata.setSessionConf(metadata, sparkSessionToRunBatches.conf)
          offsetSeqMetadata = OffsetSeqMetadata(
            metadata.batchWatermarkMs, metadata.batchTimestampMs, sparkSessionToRunBatches.conf)
        }

        /* identify the current batch id: if commit log indicates we successfully processed the
         * latest batch id in the offset log, then we can safely move to the next batch
         * i.e., committedBatchId + 1 */
        //TODO 到hdfs目录下xxx/commits下获取提交的位点
        commitLog.getLatest() match {
          case Some((latestCommittedBatchId, _)) =>
            if (latestBatchId == latestCommittedBatchId) {
              /* The last batch was successfully committed, so we can safely process a
               * new next batch but first:
               * Make a call to getBatch using the offsets from previous batch.
               * because certain sources (e.g., KafkaSource) assume on restart the last
               * batch will be executed before getOffset is called again. */
              availableOffsets.foreach {
                case (source: Source, end: Offset) =>
                  val start = committedOffsets.get(source)
                  //TODO 因为某些源再重启时会拉取上个批次的数据，让数据源略过已提交的批次
                  source.getBatch(start, end)
                case nonV1Tuple =>
                // The V2 API does not have the same edge case requiring getBatch to be called
                // here, so we do nothing here.
              }
              currentBatchId = latestCommittedBatchId + 1
              committedOffsets ++= availableOffsets
              // Construct a new batch be recomputing availableOffsets
              constructNextBatch()
            } else if (latestCommittedBatchId < latestBatchId - 1) {
              logWarning(s"Batch completion log latest batch id is " +
                s"${latestCommittedBatchId}, which is not trailing " +
                s"batchid $latestBatchId by one")
            }
          case None => logInfo("no commit log present")
        }
        logDebug(s"Resuming at batch $currentBatchId with committed offsets " +
          s"$committedOffsets and available offsets $availableOffsets")
      case None => // We are starting this stream for the first time.
        logInfo(s"Starting new streaming query.")
        currentBatchId = 0
        // TODO 第一次时先构建NextBatch()
        constructNextBatch()
    }
  }

  /**
    * Returns true if there is any new data available to be processed.
    */
  private def dataAvailable: Boolean = {
    availableOffsets.exists {
      case (source, available) =>
        committedOffsets
          .get(source)
          .map(committed => committed != available)
          .getOrElse(true)
    }
  }

  /**
    * Queries all of the sources to see if any new data is available. When there is new data the
    * batchId counter is incremented and a new log entry is written with the newest offsets.
    */
  private def constructNextBatch(): Unit = {
    // Check to see what new data is available.
    val hasNewData = {
      //TODO 重入锁, 并发同步保证
      awaitProgressLock.lock()
      try {
        // Generate a map from each unique source to the next available offset.
        val latestOffsets: Map[BaseStreamingSource, Option[Offset]] = uniqueSources.map {
          case s: Source =>
            updateStatusMessage(s"Getting offsets from $s")
            reportTimeTaken("getOffset") {
              //TODO 通过source.getOffset获取最近的位点
              (s, s.getOffset)
            }
          case s: MicroBatchReader =>
            updateStatusMessage(s"Getting offsets from $s")
            reportTimeTaken("setOffsetRange") {
              // Once v1 streaming source execution is gone, we can refactor this away.
              // For now, we set the range here to get the source to infer the available end offset,
              // get that offset, and then set the range again when we later execute.
              s.setOffsetRange(
                toJava(availableOffsets.get(s).map(off => s.deserializeOffset(off.json))),
                Optional.empty())
            }

            val currentOffset = reportTimeTaken("getEndOffset") {
              s.getEndOffset()
            }
            (s, Option(currentOffset))
        }.toMap
        //TODO 初始化availableOffsets
        availableOffsets ++= latestOffsets.filter { case (_, o) => o.nonEmpty }.mapValues(_.get)

        if (dataAvailable) {
          true
        } else {
          noNewData = true
          false
        }
      } finally {
        awaitProgressLock.unlock()
      }
    }

    //TODO 构建完一个batch后，立马提交这个batch的位点信息以及watermark信息
    if (hasNewData) {
      var batchWatermarkMs = offsetSeqMetadata.batchWatermarkMs
      // Update the eventTime watermarks if we find any in the plan.
      if (lastExecution != null) {
        lastExecution.executedPlan.collect {
          case e: EventTimeWatermarkExec => e
        }.zipWithIndex.foreach {
          case (e, index) if e.eventTimeStats.value.count > 0 =>
            logDebug(s"Observed event time stats $index: ${e.eventTimeStats.value}")
            val newWatermarkMs = e.eventTimeStats.value.max - e.delayMs
            val prevWatermarkMs = watermarkMsMap.get(index)
            if (prevWatermarkMs.isEmpty || newWatermarkMs > prevWatermarkMs.get) {
              watermarkMsMap.put(index, newWatermarkMs)
            }

          // Populate 0 if we haven't seen any data yet for this watermark node.
          case (_, index) =>
            if (!watermarkMsMap.isDefinedAt(index)) {
              watermarkMsMap.put(index, 0)
            }
        }

        // Update the global watermark to the minimum of all watermark nodes.
        // This is the safest option, because only the global watermark is fault-tolerant. Making
        // it the minimum of all individual watermarks guarantees it will never advance past where
        // any individual watermark operator would be if it were in a plan by itself.
        if (!watermarkMsMap.isEmpty) {
          val newWatermarkMs = watermarkMsMap.minBy(_._2)._2
          if (newWatermarkMs > batchWatermarkMs) {
            logInfo(s"Updating eventTime watermark to: $newWatermarkMs ms")
            batchWatermarkMs = newWatermarkMs
          } else {
            logDebug(
              s"Event time didn't move: $newWatermarkMs < " +
                s"$batchWatermarkMs")
          }
        }
      }
      offsetSeqMetadata = offsetSeqMetadata.copy(
        batchWatermarkMs = batchWatermarkMs,
        batchTimestampMs = triggerClock.getTimeMillis()) // Current batch timestamp in milliseconds

      updateStatusMessage("Writing offsets to log")
      reportTimeTaken("walCommit") {
        //TODO 写WAL的方式 提交位点信息和watermark信息, 通过hdfs-clinet写入到hdfs:///checkpointPath/xx/offsets
        assert(offsetLog.add(
          currentBatchId,
          availableOffsets.toOffsetSeq(sources, offsetSeqMetadata)),
          s"Concurrent update to the log. Multiple streaming jobs detected for $currentBatchId")
        logInfo(s"Committed offsets for batch $currentBatchId. " +
          s"Metadata ${offsetSeqMetadata.toString}")

        // NOTE: The following code is correct because runStream() processes exactly one
        // batch at a time. If we add pipeline parallelism (multiple batches in flight at
        // the same time), this cleanup logic will need to change.

        // Now that we've updated the scheduler's persistent checkpoint, it is safe for the
        // sources to discard data from the previous batch.
        if (currentBatchId != 0) {
          val prevBatchOff = offsetLog.get(currentBatchId - 1)
          if (prevBatchOff.isDefined) {
            prevBatchOff.get.toStreamProgress(sources).foreach {
              //TODO commit do nothing
              case (src: Source, off) => src.commit(off)
              case (reader: MicroBatchReader, off) =>
                reader.commit(reader.deserializeOffset(off.json))
            }
          } else {
            throw new IllegalStateException(s"batch $currentBatchId doesn't exist")
          }
        }

        // It is now safe to discard the metadata beyond the minimum number to retain.
        // Note that purge is exclusive, i.e. it purges everything before the target ID.

        //TODO 只保留最新的100个batch的位点信息和提交信息
        if (minLogEntriesToMaintain < currentBatchId) {
          offsetLog.purge(currentBatchId - minLogEntriesToMaintain)
          commitLog.purge(currentBatchId - minLogEntriesToMaintain)
        }
      }
    } else {
      awaitProgressLock.lock()
      try {
        // Wake up any threads that are waiting for the stream to progress.
        awaitProgressLockCondition.signalAll()
      } finally {
        awaitProgressLock.unlock()
      }
    }
  }

  /**
    * Processes any data available between `availableOffsets` and `committedOffsets`.
    *
    * @param sparkSessionToRunBatch Isolated [[SparkSession]] to run this batch with.
    */
  private def runBatch(sparkSessionToRunBatch: SparkSession): Unit = {
    // Request unprocessed data from all sources.
    //TODO 从kafka等其他源拉取到的数据
    newData = reportTimeTaken("getBatch") {
      availableOffsets.flatMap {

        case (source: Source, available)
          if committedOffsets.get(source).map(_ != available).getOrElse(true) =>
          val current = committedOffsets.get(source)
          //TODO 根据起始位点和终止位点从源端获取数据
          val batch = source.getBatch(current, available)
          assert(batch.isStreaming,
            s"DataFrame returned by getBatch from $source did not have isStreaming=true\n" +
              s"${batch.queryExecution.logical}")
          logDebug(s"Retrieving data from $source: $current -> $available")
          Some(source -> batch.logicalPlan)

        case (reader: MicroBatchReader, available)
          if committedOffsets.get(reader).map(_ != available).getOrElse(true) =>
          val current = committedOffsets.get(reader).map(off => reader.deserializeOffset(off.json))
          val availableV2: OffsetV2 = available match {
            case v1: SerializedOffset => reader.deserializeOffset(v1.json)
            case v2: OffsetV2 => v2
          }
          reader.setOffsetRange(
            toJava(current),
            Optional.of(availableV2))
          logDebug(s"Retrieving data from $reader: $current -> $availableV2")

          val (source, options) = reader match {
            // `MemoryStream` is special. It's for test only and doesn't have a `DataSourceV2`
            // implementation. We provide a fake one here for explain.
            case _: MemoryStream[_] => MemoryStreamDataSource -> Map.empty[String, String]
            // Provide a fake value here just in case something went wrong, e.g. the reader gives
            // a wrong `equals` implementation.
            case _ => readerToDataSourceMap.getOrElse(reader, {
              FakeDataSourceV2 -> Map.empty[String, String]
            })
          }
          Some(reader -> StreamingDataSourceV2Relation(
            reader.readSchema().toAttributes, source, options, reader))

        case _ => None
      }
    }

    // Replace sources in the logical plan with data that has arrived since the last batch.
    //TODO 此时logicalPlan是静态的DAG模板
    val newBatchesPlan = logicalPlan transform {
      case StreamingExecutionRelation(source, output) =>
        //TODO newData.get(source)是带有数据的logicalPlan，并替换静态DAG模板中的源
        newData.get(source).map { dataPlan =>
          assert(output.size == dataPlan.output.size,
            s"Invalid batch: ${Utils.truncatedString(output, ",")} != " +
              s"${Utils.truncatedString(dataPlan.output, ",")}")

          val aliases = output.zip(dataPlan.output).map { case (to, from) =>
            Alias(from, to.name)(exprId = to.exprId, explicitMetadata = Some(from.metadata))
          }
          Project(aliases, dataPlan)
        }.getOrElse {
          LocalRelation(output, isStreaming = true)
        }
    }

    // Rewire the plan to use the new attributes that were returned by the source.
    //FIXME
    val newAttributePlan = newBatchesPlan transformAllExpressions {
      case ct: CurrentTimestamp =>
        CurrentBatchTimestamp(offsetSeqMetadata.batchTimestampMs,
          ct.dataType)
      case cd: CurrentDate =>
        CurrentBatchTimestamp(offsetSeqMetadata.batchTimestampMs,
          cd.dataType, cd.timeZoneId)
    }

    val triggerLogicalPlan = sink match {
      case _: Sink => newAttributePlan //采用新属性的计划
      case s: StreamWriteSupport =>
        val writer = s.createStreamWriter(
          s"$runId",
          newAttributePlan.schema,
          outputMode,
          new DataSourceOptions(extraOptions.asJava))
        if (writer.isInstanceOf[SupportsWriteInternalRow]) {
          WriteToDataSourceV2(
            new InternalRowMicroBatchWriter(currentBatchId, writer), newAttributePlan)
        } else {
          WriteToDataSourceV2(new MicroBatchWriter(currentBatchId, writer), newAttributePlan)
        }
      case _ => throw new IllegalArgumentException(s"unknown sink type for $sink")
    }

    sparkSessionToRunBatch.sparkContext.setLocalProperty(
      MicroBatchExecution.BATCH_ID_KEY, currentBatchId.toString)

    reportTimeTaken("queryPlanning") {
      //TODO 用新的带有数据的LogicalPlan构建IncrementalExecution
      //FIXME 增量查询
      lastExecution = new IncrementalExecution(
        sparkSessionToRunBatch,
        triggerLogicalPlan,
        outputMode,
        checkpointFile("state"),//保存state信息
        runId,
        currentBatchId,
        offsetSeqMetadata)

      //TODO 增加状态存储物理计划节点，并生成SparkPlan， 并可以通过lastExecution.toRDD得到 RDD DAG
      lastExecution.executedPlan // Force the lazy generation of execution plan
    }

    //FIXME lastExecution.analyzed开始遍历整个计划树
    val nextBatch =
      new Dataset(sparkSessionToRunBatch, lastExecution, RowEncoder(lastExecution.analyzed.schema))

    reportTimeTaken("addBatch") {
      SQLExecution.withNewExecutionId(sparkSessionToRunBatch, lastExecution) {
        sink match {
          //TODO 将数据落地到目标源
          case s: Sink => s.addBatch(currentBatchId, nextBatch)
          case _: StreamWriteSupport =>
            // This doesn't accumulate any data - it just forces execution of the microbatch writer.
            nextBatch.collect()
        }
      }
    }

    awaitProgressLock.lock()
    try {
      // Wake up any threads that are waiting for the stream to progress.
      awaitProgressLockCondition.signalAll()
    } finally {
      awaitProgressLock.unlock()
    }
  }

  /** Execute a function while locking the stream from making an progress */
  private[sql] def withProgressLocked(f: => Unit): Unit = {
    awaitProgressLock.lock()
    try {
      f
    } finally {
      awaitProgressLock.unlock()
    }
  }

  private def toJava(scalaOption: Option[OffsetV2]): Optional[OffsetV2] = {
    Optional.ofNullable(scalaOption.orNull)
  }
}

object MicroBatchExecution {
  val BATCH_ID_KEY = "streaming.sql.batchId"
}

object MemoryStreamDataSource extends DataSourceV2

object FakeDataSourceV2 extends DataSourceV2
