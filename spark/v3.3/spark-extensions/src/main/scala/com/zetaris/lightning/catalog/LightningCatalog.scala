/*
 * Copyright 2023 ZETARIS Pty Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.zetaris.lightning.catalog

import com.zetaris.lightning.model.LightningModel
import com.zetaris.lightning.model.serde.DataSource.DataSource

import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.connector.catalog.NamespaceChange
import org.apache.spark.sql.connector.catalog.SupportsNamespaces
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.connector.catalog.TableCatalog
import org.apache.spark.sql.connector.catalog.TableChange
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import scala.collection.mutable.ArrayBuilder
import scala.collection.mutable.Map

class LightningCatalog extends TableCatalog with SupportsNamespaces {
  override val name = LightningModel.LIGHTNING_CATALOG_NAME
  private var model: LightningModel.LightningModel = null

  override def initialize(name: String, options: CaseInsensitiveStringMap): Unit = {
    model = LightningModel(options)
  }

  private def loadDataSource(namespace: Array[String], name: String): Option[DataSource] = {
    try {
      model.loadDataSources(namespace, name).headOption
    } catch {
      case _: Throwable => None
    }
  }

  private def findRootDataSource(namespace: Array[String]): Option[DataSource] = {
    var name = namespace.last
    var root = namespace.dropRight(1)
    var found: Option[DataSource] = None

    while (found.isEmpty && root.length > 1) {
      found = loadDataSource(root, name)
      name = root.last
      root = root.dropRight(1)
    }

    found
  }

  override def listTables(namespace: Array[String]): Array[Identifier] = {
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        CatalogUnit(datasource) match {
          case delta @DeltaCatalogUnit(_, _) =>
            delta.listTables(Array(namespace.last))
          case other =>
            val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
            other.listTables(sourceNamespace)
        }
      case None =>
        throw new RuntimeException(s"namespace(${namespace.mkString(".")}) is not defined")
    }
  }

  override def loadTable(ident: Identifier): Table = {
    val namespace = ident.namespace()

    if (namespace.isEmpty) {
      throw new RuntimeException("namespace is not provided")
    }

    namespace(0).toLowerCase match {
      case "lightning" =>
        LightningCatalogUnit(namespace(0), model).loadTable(ident)
      case "datasource" =>
        findRootDataSource(ident.namespace()) match {
          case Some(datasource) =>
            val catalog = CatalogUnit(datasource)
            val sourceNamespace = ident.namespace().drop(datasource.namespace.length + 1)
            catalog.loadTable(Identifier.of(sourceNamespace, ident.name()))
          case None =>
            throw new RuntimeException(s"namespace(${ident.namespace().mkString(".")}) is not defined")
          case _ => throw new RuntimeException(s"invalid namespace : ${namespace(0)}")
        }
    }
  }

  override def createTable(ident: Identifier,
                           schema: StructType,
                           partitions: Array[Transform],
                           properties: java.util.Map[String, String]): Table = {
    findRootDataSource(ident.namespace()) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = ident.namespace().drop(datasource.namespace.length + 1)
        catalog.createTable(Identifier.of(sourceNamespace, ident.name()), schema, partitions, properties)
      case None =>
        throw new RuntimeException(s"namespace(${ident.namespace().mkString(".")}) is not defined")
    }
  }

  override def alterTable(ident: Identifier, changes: TableChange*): Table = {
    throw new RuntimeException("alter table is not supported")
  }

  override def dropTable(ident: Identifier): Boolean = {
    val namespace = ident.namespace()
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.dropTable(Identifier.of(sourceNamespace, ident.name()))
      case None =>
        throw new RuntimeException("drop table is not supported")
    }
  }

  override def renameTable(oldIdent: Identifier, newIdent: Identifier): Unit = {
    throw new RuntimeException("rename table is not supported")
  }

  override def listNamespaces(): Array[Array[String]] = {
    val nameSpacesBuilder = ArrayBuilder.make[Array[String]]

    nameSpacesBuilder += Array("datasource")
    nameSpacesBuilder += Array("metastore")

    nameSpacesBuilder.result()
  }

  override def listNamespaces(namespace: Array[String]): Array[Array[String]] = {
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.listNamespaces(sourceNamespace)
      case None =>
        val nameSpacesBuilder = ArrayBuilder.make[Array[String]]
        model.listNameSpaces(namespace).foreach { ns =>
          nameSpacesBuilder += Array(ns)
        }
        nameSpacesBuilder.result()
    }
  }

  override def loadNamespaceMetadata(namespace: Array[String]): java.util.Map[String, String] = {
    import scala.collection.JavaConverters.mapAsJavaMap
    mapAsJavaMap(Map.empty[String, String])
  }

  override def namespaceExists(namespace: Array[String]): Boolean = {
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.namespaceExists(sourceNamespace)
      case None =>
        val parent = namespace.dropRight(1)
        return model.listNameSpaces(parent).exists(_.equalsIgnoreCase(namespace.last))
    }
  }

  override def createNamespace(namespace: Array[String], metadata: java.util.Map[String, String]): Unit = {
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.createNamespace(sourceNamespace, metadata)
      case None =>
        model.createNamespace(namespace, metadata)
    }
  }

  override def alterNamespace(namespace: Array[String], changes: NamespaceChange*): Unit = {
    throw new RuntimeException("alter namespace is not supported")
  }

  override def dropNamespace(namespace: Array[String], cascade: Boolean): Boolean = {
    if (namespace.length == 1) {
      val toLower = namespace(0).toLowerCase
      if (toLower == "datasource" || toLower == "metastore") {
        throw new RuntimeException("deleting root namespace(datasource, metastore) is not allowed")
      }
    }

    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.dropNamespace(sourceNamespace, cascade)
      case None =>
        model.dropNamespace(namespace, cascade)
        true
    }
  }

  override def tableExists(ident: Identifier): Boolean = {
    val namespace = ident.namespace()
    findRootDataSource(namespace) match {
      case Some(datasource) =>
        val catalog = CatalogUnit(datasource)
        val sourceNamespace = namespace.drop(datasource.namespace.length + 1)
        catalog.tableExists(Identifier.of(sourceNamespace, ident.name()))
      case None =>
        false
    }
  }

}