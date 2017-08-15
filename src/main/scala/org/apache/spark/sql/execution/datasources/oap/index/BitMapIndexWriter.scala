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

package org.apache.spark.sql.execution.datasources.oap.index

import java.io.{ByteArrayOutputStream, ObjectOutputStream}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.rdd.InputFileNameHolder
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.FromUnsafeProjection
import org.apache.spark.sql.execution.datasources.oap.io.IndexFile
import org.apache.spark.sql.execution.datasources.oap.statistics._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.collection.BitSet

import scala.collection.mutable

// TODO respect `sparkSession.conf.get(SQLConf.PARTITION_MAX_FILES)`
private[oap] class BitMapIndexWriter(
    indexColumns: Array[IndexColumn],
    keySchema: StructType,
    indexName: String,
    time: String,
    isAppend: Boolean) extends IndexWriter {

  override def setHadoopConf(hadoopConf: Configuration) = {
    val filename = InputFileNameHolder.getInputFileName().toString
    hadoopConf.set(IndexWriter.INPUT_FILE_NAME, filename)
    hadoopConf.set(IndexWriter.INDEX_NAME, indexName)
    hadoopConf.set(IndexWriter.INDEX_TIME, time)
  }

  override def writeIndexFromRows(description: WriteJobDescription,
      writer: IndexOutputWriter, iterator: Iterator[InternalRow]): Seq[IndexBuildResult] = {
    var taskReturn: Seq[IndexBuildResult] = Nil
    var writeNewFile = false
    val configuration = description.serializableHadoopConf.value
    // to get input filename
    if (!iterator.hasNext) return Nil
    if (isAppend) {
      val fs = FileSystem.get(configuration)
      var skip = true
      var nextFile = InputFileNameHolder.getInputFileName().toString
      iterator.next()
      while(iterator.hasNext && skip) {
        val cacheFile = nextFile
        nextFile = InputFileNameHolder.getInputFileName().toString
        // avoid calling `fs.exists` for every row
        skip = cacheFile == nextFile || fs.exists(new Path(nextFile))
        iterator.next()
      }
      if (skip) return Nil
    }
    val filename = InputFileNameHolder.getInputFileName().toString

    def writeTask(): Seq[IndexBuildResult] = {
      val statisticsManager = new StatisticsManager
      statisticsManager.initialize(BitMapIndexType, keySchema, configuration)
      // Current impl just for fast proving the effect of BitMap Index,
      // we can do the optimize below:
      // 1. each bitset in hashmap value has same length, we can save the
      //    hash map in raw bits in file, like B+ Index above
      // 2. use the BitMap with bit compress like javaewah
      // TODO: BitMap Index storage format optimize
      // get the tmpMap and total rowCnt in first travers
      val tmpMap = new mutable.HashMap[InternalRow, mutable.ListBuffer[Int]]()
      var rowCnt = 0
      while (iterator.hasNext && !writeNewFile) {
        val fname = InputFileNameHolder.getInputFileName().toString
        if (fname != filename) {
          taskReturn = taskReturn ++: writeIndexFromRows(description, writer, iterator)
          writeNewFile = true
        } else {
          val v = genericProjector(iterator.next()).copy()
          statisticsManager.addOapKey(v)
          if (!tmpMap.contains(v)) {
            val list = new mutable.ListBuffer[Int]()
            list += rowCnt
            tmpMap.put(v, list)
          } else {
            tmpMap.get(v).get += rowCnt
          }
          rowCnt += 1
        }
      }
      // generate the bitset hashmap
      val hashMap = new mutable.HashMap[InternalRow, BitSet]()
      tmpMap.foreach(kv => {
        val bs = new BitSet(rowCnt)
        kv._2.foreach(bs.set)
        hashMap.put(kv._1, bs)
      })
      val header = writeHead(writer, IndexFile.INDEX_VERSION)
      // serialize hashMap and get length
      val writeBuf = new ByteArrayOutputStream()
      val out = new ObjectOutputStream(writeBuf)
      out.writeObject(hashMap)
      out.flush()
      val objLen = writeBuf.size()
      // write byteArray length and byteArray
      IndexUtils.writeInt(writer, objLen)
      writer.write(writeBuf.toByteArray)
      out.close()
      val indexEnd = 4 + objLen + header
      val offset: Long = indexEnd

      statisticsManager.write(writer)

      // write index file footer
      IndexUtils.writeLong(writer, indexEnd) // statistics start pos
      IndexUtils.writeLong(writer, offset) // index file end offset
      IndexUtils.writeLong(writer, indexEnd) // dataEnd


      // writer.close()
      taskReturn :+ IndexBuildResult(filename, rowCnt, "", new Path(filename).getParent.toString)
    }

    writeTask()
  }
  @transient private lazy val genericProjector = FromUnsafeProjection(keySchema)
}