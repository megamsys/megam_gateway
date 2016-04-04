/*
** Copyright [2013-2016] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps
import scala.collection.mutable.ListBuffer

import cache._
import db._
import models.json.tosca._
import models.json.tosca.carton._
import models.Constants._
import io.megam.auth.funnel.FunnelErrors._
import app.MConfig
import models.base._
import models.tosca._

import io.megam.util.Time
import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

import com.datastax.driver.core.{ ResultSet, Row }
import com.websudos.phantom.dsl._
import scala.concurrent.{ Future => ScalaFuture }
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * @author rajthilak
 *
 */
case class Operation(operation_type: String, description: String, properties: models.tosca.KeyValueList, status: String) {
}

case class AssemblyResult(
    id: String,
    org_id: String,
    account_id: String,
    name: String,
    components: models.tosca.ComponentLinks,
    tosca_type: String,
    policies: models.tosca.PoliciesList,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList,
    status: String,
    json_claz: String,
    created_at: String) {
}

sealed class AssemblySacks extends CassandraTable[AssemblySacks, AssemblyResult] {

  implicit val formats = DefaultFormats

  object id extends StringColumn(this) with PrimaryKey[String]
  object org_id extends StringColumn(this) with PartitionKey[String]
  object account_id extends StringColumn(this) 
  object name extends StringColumn(this)
  object components extends ListColumn[AssemblySacks, AssemblyResult, String](this)
  object tosca_type extends StringColumn(this)

  object policies extends JsonListColumn[AssemblySacks, AssemblyResult, Policy](this) {
    override def fromJson(obj: String): Policy = {
      JsonParser.parse(obj).extract[Policy]
    }
    override def toJson(obj: Policy): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object inputs extends JsonListColumn[AssemblySacks, AssemblyResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object outputs extends JsonListColumn[AssemblySacks, AssemblyResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object status extends StringColumn(this)
  object json_claz extends StringColumn(this)
  object created_at extends StringColumn(this)

  def fromRow(row: Row): AssemblyResult = {
    AssemblyResult(
      id(row),
      org_id(row),
      account_id(row),
      name(row),
      components(row),
      tosca_type(row),
      policies(row),
      inputs(row),
      outputs(row),
      status(row),
      json_claz(row),
      created_at(row))
  }
}

abstract class ConcreteAssembly extends AssemblySacks with RootConnector {
  // you can even rename the table in the schema to whatever you like.
  override lazy val tableName = "assembly"
  override implicit def space: KeySpace = scyllaConnection.space
  override implicit def session: Session = scyllaConnection.session

  def insertNewRecord(ams: AssemblyResult): ValidationNel[Throwable, ResultSet] = {
    val res = insert.value(_.id, ams.id)
      .value(_.org_id, ams.org_id)
      .value(_.account_id, ams.account_id)
      .value(_.name, ams.name)
      .value(_.components, ams.components)
      .value(_.tosca_type, ams.tosca_type)
      .value(_.policies, ams.policies)
      .value(_.inputs, ams.inputs)
      .value(_.outputs, ams.outputs)
      .value(_.status, ams.status)
      .value(_.json_claz, ams.json_claz)
      .value(_.created_at, ams.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

  def listRecords(email: String, org: String): ValidationNel[Throwable, Seq[AssemblyResult]] = {
    val res = select.allowFiltering().where(_.org_id eqs org).fetch()
    Await.result(res, 5.seconds).successNel
  }

  def getRecord(id: String): ValidationNel[Throwable, Option[AssemblyResult]] = {
    val res = select.allowFiltering().where(_.id eqs id).one()
    Await.result(res, 5.seconds).successNel
  }

  def updateRecord(org_id: String, rip: AssemblyResult): ValidationNel[Throwable, ResultSet] = {
    val res = update.where(_.id eqs rip.id).and(_.org_id eqs org_id)
      .modify(_.name setTo rip.name)
      .and(_.components setTo rip.components)
      .and(_.tosca_type setTo rip.tosca_type)
      .and(_.policies setTo rip.policies)
      .and(_.inputs setTo rip.inputs)
      .and(_.outputs setTo rip.outputs)
      .and(_.status setTo rip.status)
      .and(_.created_at setTo rip.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

}

case class Policy(name: String, ptype: String, members: models.tosca.MembersList) {
}

case class Assembly(name: String,
    components: models.tosca.ComponentsList,
    tosca_type: String,
    policies: models.tosca.PoliciesList,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList,
    status: String) {
}

case class AssemblyUpdateInput(id: String,
    org_id: String,
    name: String,
    components: models.tosca.ComponentLinks,
    tosca_type: String,
    policies: models.tosca.PoliciesList,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList, status: String) {
}

case class WrapAssemblyResult(thatGS: Option[AssemblyResult]) {

  implicit val formats = DefaultFormats

  val asm = thatGS.get
  val cattype = asm.tosca_type.split('.')(1)
  val domain = asm.inputs.find(_.key.equalsIgnoreCase(DOMAIN))
  val alma = asm.name + "." + domain.get.value //None is ignored here. dangerous.

}

object Assembly extends ConcreteAssembly {

  //def empty: Assembly = new Assembly(new String(), ComponentsList.empty, new String(), PoliciesList.empty, KeyValueList.empty, KeyValueList.empty, new String())

  def findById(assemblyID: Option[List[String]]): ValidationNel[Throwable, AssemblyResults] = {
    (assemblyID map {
      _.map { asm_id =>
        play.api.Logger.debug(("%-20s -->[%s]").format("Assembly Id", asm_id))
        (getRecord(asm_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(asm_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[AssemblyResult] =>
          xso match {
            case Some(xs) => {
              play.api.Logger.debug(("%-20s -->[%s]").format("Assembly Result", xs))
              Validation.success[Throwable, AssemblyResults](List(xs.some)).toValidationNel //screwy kishore, every element in a list ?
            }
            case None => {
              Validation.failure[Throwable, AssemblyResults](new ResourceItemNotFound(asm_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((AssemblyResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head.
  }

  private def updateAssemblySack(input: String): ValidationNel[Throwable, Option[AssemblyResult]] = {
    val ripNel: ValidationNel[Throwable, AssemblyUpdateInput] = (Validation.fromTryCatchThrowable[AssemblyUpdateInput, Throwable] {
      parse(input).extract[AssemblyUpdateInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      rip <- ripNel
      asm_collection <- (Assembly.findById(List(rip.id).some) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val asm = asm_collection.head
      val json = AssemblyResult(rip.id, rip.org_id, asm.get.account_id, asm.get.name, asm.get.components, asm.get.tosca_type, rip.policies ::: asm.get.policies, rip.inputs ::: asm.get.inputs, asm.get.outputs, asm.get.status, asm.get.json_claz, asm.get.created_at)
      json.some
    }
  }

  def update(org_id: String, input: String): ValidationNel[Throwable, Option[AssemblyResult]] = {

    for {
      gs <- (updateAssemblySack(input) leftMap { err: NonEmptyList[Throwable] => err })
      set <- (updateRecord(org_id, gs.get) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Assembly.updated successfully", Console.RESET))
      gs
    }
  }

  def upgrade(email: String, id: String): ValidationNel[Throwable, AssemblyResult] = {
    for {
      asm_collection <- (Assembly.findById(List(id).some) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val asm = asm_collection.head
      pub(email, WrapAssemblyResult(asm))
    }
  }

  /* Lets clean it up in 1.0 using Messageable  */
  private def pub(email: String, wa: WrapAssemblyResult): AssemblyResult = {
    models.base.Requests.createAndPub(email,
      RequestInput(wa.asm.id, wa.cattype, wa.alma, UPGRADE, OPERTATIONS).json)
    wa.asm
  }
}

object AssemblysList extends ConcreteAssembly {

  implicit def AssemblysListsSemigroup: Semigroup[AssemblysLists] = Semigroup.instance((f1, f2) => f1.append(f2))

  def apply(assemblyList: List[Assembly]): AssemblysList = { assemblyList }

  def createLinks(authBag: Option[io.megam.auth.stack.AuthBag], input: AssemblysList): ValidationNel[Throwable, AssemblysLists] = {
    val res = (input map {
      asminp => (create(authBag, asminp))
    }).foldRight((AssemblysLists.empty).successNel[Throwable])(_ +++ _)
    play.api.Logger.debug(("%-20s -->[%s]").format("AssemblysLists", res))
    res.getOrElse(new ResourceItemNotFound(authBag.get.email, "assembly = ah. ouh. for some reason.").failureNel[AssemblysLists])
    res
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name assemblies name will point to the "csars" bucket
   */
  def create(authBag: Option[io.megam.auth.stack.AuthBag], input: Assembly): ValidationNel[Throwable, AssemblysLists] = {
    for {
      ogsi <- mkAssemblySack(authBag, input) leftMap { err: NonEmptyList[Throwable] => err }
      set <- (insertNewRecord(ogsi.get) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Assembly.created successfully", Console.RESET))
      nels(ogsi)
    }
  }

  private def mkAssemblySack(authBag: Option[io.megam.auth.stack.AuthBag], rip: Assembly): ValidationNel[Throwable, Option[AssemblyResult]] = {
    var outlist = rip.outputs
    for {
      uir <- (UID("asm").get leftMap { ut: NonEmptyList[Throwable] => ut })
      com <- (ComponentsList.createLinks(authBag, rip.components, (uir.get._1 + uir.get._2)) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      var components_links = new ListBuffer[String]()
      if (com.size > 1) {
        for (component <- com) {
          component match {
            case Some(value) => components_links += value.id
            case None => components_links
          }
        }
      }
      val json = AssemblyResult(uir.get._1 + uir.get._2, authBag.get.org_id, authBag.get.email, rip.name, components_links.toList, rip.tosca_type, rip.policies, rip.inputs, outlist, rip.status, "Megam::Assembly", Time.now.toString)
      json.some
    }
  }

}
