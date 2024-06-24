/*
 *
 *  * Copyright 2023 ZETARIS Pty Ltd
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *  * associated documentation files (the "Software"), to deal in the Software without restriction,
 *  * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 *  * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 *  * subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all copies
 *  * or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 *  * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *  * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 *  * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.zetaris.lightning.datasources.v2

import com.zetaris.lightning.datasources.v2.UnstructuredData.{MetaData, createFilter}
import org.apache.hadoop.fs.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.sql.catalyst.{FileSourceOptions, InternalRow}
import org.apache.spark.sql.connector.read.PartitionReader
import org.apache.spark.sql.execution.datasources.PartitionedFile
import org.apache.spark.sql.execution.datasources.v2._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.SerializableConfiguration

case class UnstructuredFilePartitionReaderFactory(broadcastedConf: Broadcast[SerializableConfiguration],
                                                  sparkSession: SparkSession,
                                                  sqlConf: SQLConf,
                                                  readDataSchema: StructType,
                                                  partitionSchema: StructType,
                                                  rootPathsSpecified: Seq[Path],
                                                  pushedFilters: Array[Filter],
                                                  options: FileSourceOptions,
                                                  previewLen: Long,
                                                  isContentTable: Boolean = false) extends FilePartitionReaderFactory {

  private def pdfTextFromBinary(content: Array[Byte], previewLen: Int): String = {
    val document = Loader.loadPDF(content)
    val pdfStripper = new PDFTextStripper()
    val pdfText = pdfStripper.getText(document)
    if (previewLen > 0 && pdfText.length > previewLen) {
      pdfStripper.getText(document).substring(0, previewLen)
    } else {
      pdfStripper.getText(document)
    }
  }

  private def buildMetaDataReader(file: PartitionedFile): PartitionReader[InternalRow] = {
    val confValue = broadcastedConf.value.value
    val reader = new HadoopBinaryFileReader(file, confValue)

    Option(TaskContext.get()).foreach(_.addTaskCompletionListener[Unit](_ => reader.close()))

    val iter = if (readDataSchema.isEmpty) {
      val emptyUnsafeRow = new UnsafeRow(0)
      reader.map(_ => emptyUnsafeRow)
    } else {
      val writer = new UnsafeRowWriter(readDataSchema.length)
      writer.resetRowWriter()
      var md = MetaData("pdf", "", -1L, -1L, "", "")

      readDataSchema.fields.map(_.name).zipWithIndex.foreach {
        case (UnstructuredData.FILETYPE, index) =>
          val filePath = file.toPath.toString
          val typeIndex = filePath.lastIndexOf(".")
          val typeStr = if (typeIndex > 0) {
            filePath.substring(typeIndex + 1)
          } else {
            null
          }
          md = md.copy(fileType = typeStr)
          writer.write(index, UTF8String.fromString(typeStr))
        case (UnstructuredData.PATH, index) =>
          val path = file.toPath.toUri.toString
          md = md.copy(path = path)
          writer.write(index, UTF8String.fromString(path))
        case (UnstructuredData.MODIFIEDAT, index) =>
          val modifiedAt = file.modificationTime * 1000
          md = md.copy(modifiedAt = modifiedAt)
          writer.write(index, modifiedAt)
        case (UnstructuredData.SIZEINBYTES, index) =>
          md = md.copy(sizeInBytes = file.length)
          writer.write(index, file.length)
        case (UnstructuredData.PREVIEW, index) =>
          val preview = pdfTextFromBinary(reader.next(), previewLen.toInt)
          md = md.copy(preview = preview)
          writer.write(index, UTF8String.fromString(preview))
        case (UnstructuredData.SUBDIR, index) =>
          val fullPath = file.toPath.toUri.getPath
          val rootPath = rootPathsSpecified.find(path => fullPath.startsWith(path.toUri.getPath)).get.toUri.getPath

          val lastIndex = fullPath.lastIndexOf("/")
          val subDir = if (lastIndex > 0) {
            fullPath.substring(rootPath.length, lastIndex)
          } else {
            ""
          }
          md = md.copy(subDir = subDir)
          writer.write(index, UTF8String.fromString(subDir))
      }

      if (pushedFilters.isEmpty) {
        Iterator.single(writer.getRow)
      } else {
        if (pushedFilters.map(createFilter(_)(md)).reduce(_ && _)) {
          Iterator.single(writer.getRow)
        } else {
          Iterator.empty
        }
      }
    }

    val fileReader = new PartitionReaderFromIterator[InternalRow](iter)
    new PartitionReaderWithPartitionValues(fileReader, readDataSchema, partitionSchema, file.partitionValues)
  }

  private def buildContentReader(file: PartitionedFile): PartitionReader[InternalRow] = {
    val confValue = broadcastedConf.value.value
    val reader = new HadoopBinaryFileReader(file, confValue)

    Option(TaskContext.get()).foreach(_.addTaskCompletionListener[Unit](_ => reader.close()))

    val iter = if (readDataSchema.isEmpty) {
      val emptyUnsafeRow = new UnsafeRow(0)
      reader.map(_ => emptyUnsafeRow)
    } else {
      val writer = new UnsafeRowWriter(readDataSchema.length)
      writer.resetRowWriter()
      var md = MetaData("pdf", "", -1L, -1L, "", "")

      val contentNeed = readDataSchema.fields.find(_.name.toLowerCase == UnstructuredData.TEXTCONTENT).isDefined ||
        readDataSchema.fields.find(_.name.toLowerCase == UnstructuredData.TEXTCONTENT).isDefined
      val binContents: Array[Byte] = if (contentNeed) {
        reader.next()
      } else {
        Array()
      }

      readDataSchema.fields.map(_.name).zipWithIndex.foreach {
        case (UnstructuredData.PATH, index) =>
          val path = file.toPath.toUri.toString
          md = md.copy(path = path)
          writer.write(index, UTF8String.fromString(path))
        case (UnstructuredData.SUBDIR, index) =>
          val fullPath = file.toPath.toUri.getPath
          val rootPath = rootPathsSpecified.find(path => fullPath.startsWith(path.toUri.getPath)).get.toUri.getPath

          val lastIndex = fullPath.lastIndexOf("/")
          val subDir = if (lastIndex > 0) {
            fullPath.substring(rootPath.length, lastIndex)
          } else {
            ""
          }
          md = md.copy(subDir = subDir)
          writer.write(index, UTF8String.fromString(subDir))
        case (UnstructuredData.TEXTCONTENT, index) =>
          val content = pdfTextFromBinary(binContents, previewLen.toInt)
          md = md.copy(textcontent = content)
          writer.write(index, UTF8String.fromString(content))
        case (UnstructuredData.BINCONTENT, index) =>
          md = md.copy(bincontent = binContents)
          writer.write(index, binContents)
      }

      if (pushedFilters.isEmpty) {
        Iterator.single(writer.getRow)
      } else {
        if (pushedFilters.map(createFilter(_)(md)).reduce(_ && _)) {
          Iterator.single(writer.getRow)
        } else {
          Iterator.empty
        }
      }
    }

    val fileReader = new PartitionReaderFromIterator[InternalRow](iter)
    new PartitionReaderWithPartitionValues(fileReader, readDataSchema, partitionSchema, file.partitionValues)
  }

  override def buildReader(file: PartitionedFile): PartitionReader[InternalRow] = {
    if (isContentTable) {
      buildContentReader(file)
    } else {
      buildMetaDataReader(file)
    }
  }
}
