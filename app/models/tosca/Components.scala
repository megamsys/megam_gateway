package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._

import cache._
import db._
import models.Constants._
import models.json.tosca._
import models.json.tosca.carton._
import io.megam.auth.funnel.FunnelErrors._

import com.datastax.driver.core.{ ResultSet, Row }
import com.websudos.phantom.dsl._
import scala.concurrent.{ Future => ScalaFuture }
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }
import scala.concurrent.Await
import scala.concurrent.duration._

import utils.DateHelper
import io.megam.util.Time
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.{DateTimeFormat,ISODateTimeFormat}

import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

// The component inputs field have following fields
//    1.domain
//    2.port
//    3.username
//    4.password
//    5.version
//    6.version
//    7.source
//    8.id
//    10.x
//    11.y
//    12.z
//    13.wires
//    14.dbname
//    15.dbpassword
// These fields are presents at inputs array for APP or SERVICE components

case class Artifacts(artifact_type: String, content: String, requirements: KeyValueList)

case class Repo(rtype: String, source: String, oneclick: String, url: String, branch: String)

case class ComponentResult(
    id: String,
    org_id: String,
    name: String,
    tosca_type: String,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList,
    envs: models.tosca.KeyValueList,
    artifacts: Artifacts,
    related_components: List[String],
    operations: models.tosca.OperationList,
    status: String,
    state: String,
    repo: Repo,
    json_claz: String,
    created_at: DateTime) {
}

sealed class ComponentSacks extends CassandraTable[ComponentSacks, ComponentResult] {

  implicit val formats = DefaultFormats

  object org_id extends StringColumn(this) with PartitionKey[String]
  object id extends StringColumn(this) with PrimaryKey[String]

  object name extends StringColumn(this)
  object tosca_type extends StringColumn(this)

  object inputs extends JsonListColumn[ComponentSacks, ComponentResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object outputs extends JsonListColumn[ComponentSacks, ComponentResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object envs extends JsonListColumn[ComponentSacks, ComponentResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object artifacts extends JsonColumn[ComponentSacks, ComponentResult, Artifacts](this) {
    override def fromJson(obj: String): Artifacts = {
      JsonParser.parse(obj).extract[Artifacts]
    }

    override def toJson(obj: Artifacts): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object related_components extends ListColumn[ComponentSacks, ComponentResult, String](this)

  object operations extends JsonListColumn[ComponentSacks, ComponentResult, Operation](this) {
    override def fromJson(obj: String): Operation = {
      JsonParser.parse(obj).extract[Operation]
    }

    override def toJson(obj: Operation): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object status extends StringColumn(this)
  object state extends StringColumn(this)

  object repo extends JsonColumn[ComponentSacks, ComponentResult, Repo](this) {
    override def fromJson(obj: String): Repo = {
      JsonParser.parse(obj).extract[Repo]
    }

    override def toJson(obj: Repo): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object json_claz extends StringColumn(this)
  object created_at extends DateTimeColumn(this)

  def fromRow(row: Row): ComponentResult = {
    ComponentResult(
      id(row),
      org_id(row),
      name(row),
      tosca_type(row),
      inputs(row),
      outputs(row),
      envs(row),
      artifacts(row),
      related_components(row),
      operations(row),
      status(row),
      state(row),
      repo(row),
      json_claz(row),
      created_at(row))
  }
}

abstract class ConcreteComponent extends ComponentSacks with RootConnector {

  override lazy val tableName = "components"
  override implicit def space: KeySpace = scyllaConnection.space
  override implicit def session: Session = scyllaConnection.session

  def insertNewRecord(ams: ComponentResult): ValidationNel[Throwable, ResultSet] = {
    val res = insert.value(_.id, ams.id)
      .value(_.org_id, ams.org_id)
      .value(_.name, ams.name)
      .value(_.tosca_type, ams.tosca_type)
      .value(_.inputs, ams.inputs)
      .value(_.outputs, ams.outputs)
      .value(_.envs, ams.envs)
      .value(_.artifacts, ams.artifacts)
      .value(_.related_components, ams.related_components)
      .value(_.operations, ams.operations)
      .value(_.status, ams.status)
      .value(_.state, ams.state)
      .value(_.repo, ams.repo)
      .value(_.json_claz, ams.json_claz)
      .value(_.created_at, ams.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

  def getRecord(id: String): ValidationNel[Throwable, Option[ComponentResult]] = {
    val res = select.allowFiltering().where(_.id eqs id).one()
    Await.result(res, 5.seconds).successNel
  }

  def deleteRecords(org_id: String): ValidationNel[Throwable, ResultSet] = {
    val res = delete.where(_.org_id eqs org_id).future()
    Await.result(res, 5.seconds).successNel
  }

  def updateRecord(org_id: String, rip: ComponentResult): ValidationNel[Throwable, ResultSet] = {
    val res = update.where(_.id eqs rip.id).and(_.org_id eqs org_id)
      .modify(_.name setTo rip.name)
      .and(_.tosca_type setTo rip.tosca_type)
      .and(_.inputs setTo rip.inputs)
      .and(_.outputs setTo rip.outputs)
      .and(_.envs setTo rip.envs)
      .and(_.artifacts setTo rip.artifacts)
      .and(_.related_components setTo rip.related_components)
      .and(_.operations setTo rip.operations)
      .and(_.status setTo rip.status)
      .and(_.state setTo rip.state)
      .and(_.repo setTo rip.repo)
      .and(_.created_at setTo rip.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

}

case class ComponentUpdateInput(
    id: String,
    name: String,
    tosca_type: String,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList,
    envs: models.tosca.KeyValueList,
    artifacts: Artifacts,
    related_components: models.tosca.BindLinks,
    operations: models.tosca.OperationList,
    status: String,
    state: String,
    repo: Repo) {
}

case class Component(
    name: String,
    tosca_type: String,
    inputs: models.tosca.KeyValueList,
    outputs: models.tosca.KeyValueList,
    envs: models.tosca.KeyValueList,
    artifacts: Artifacts,
    related_components: models.tosca.BindLinks,
    operations: models.tosca.OperationList,
    repo: Repo,
    status: String,
    state: String) {
}

object Component extends ConcreteComponent {


  def findById(componentID: Option[List[String]]): ValidationNel[Throwable, ComponentResults] = {
    (componentID map {
      _.map { asm_id =>
        (getRecord(asm_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(asm_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[ComponentResult] =>
          xso match {
            case Some(xs) => {
              play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Components."+asm_id + " successfully", Console.RESET))
              Validation.success[Throwable, ComponentResults](List(xs.some)).toValidationNel //screwy kishore, every element in a list ?
            }
            case None => {
              Validation.failure[Throwable, ComponentResults](new ResourceItemNotFound(asm_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((ComponentResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head.
  }

  private def updateComponentSack(input: String): ValidationNel[Throwable, Option[ComponentResult]] = {
    val ripNel: ValidationNel[Throwable, ComponentUpdateInput] = (Validation.fromTryCatchThrowable[ComponentUpdateInput, Throwable] {
      parse(input).extract[ComponentUpdateInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      rip <- ripNel
      com_collection <- (Component.findById(List(rip.id).some) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val com = com_collection.head
      val json = ComponentResult(rip.id, com.get.org_id, com.get.name, com.get.tosca_type,  rip.inputs, rip.outputs, rip.envs, rip.artifacts, rip.related_components, rip.operations, rip.status, rip.state, rip.repo, com.get.json_claz, com.get.created_at)
      json.some
    }
  }

  def update(org_id: String, input: String): ValidationNel[Throwable, ComponentResults] = {
    for {
      gs <- (updateComponentSack(input) leftMap { err: NonEmptyList[Throwable] => err })
      set <- (updateRecord(org_id, gs.get) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Component.updated successfully", Console.RESET))
      List(gs)

    }
  }

  def deleteByOrgId(org_id: String): ValidationNel[Throwable, Option[ComponentResult]] = {
    deleteRecords(org_id) match {
      case Success(value) => Validation.success[Throwable, Option[ComponentResult]](none).toValidationNel
      case Failure(err) => Validation.success[Throwable, Option[ComponentResult]](none).toValidationNel
    }
  }

}

object ComponentsList extends ConcreteComponent {

  implicit def ComponentListsSemigroup: Semigroup[ComponentLists] = Semigroup.instance((f1, f2) => f1.append(f2))

  def apply(componentList: List[Component]): ComponentsList = { componentList }

  private def mkComponentSack(authBag: Option[io.megam.auth.stack.AuthBag], input: Component, asm_id: String): ValidationNel[Throwable, Option[ComponentResult]] = {
    for {
      uir <- (UID("com").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      import models.tosca.KeyValueList._

      val json = ComponentResult((uir.get._1 + uir.get._2), authBag.get.org_id, input.name, input.tosca_type, input.inputs, input.outputs,
        KeyValueList.merge(input.envs,
          Map(MKT_FLAG_EMAIL -> authBag.get.email,
            MKT_FLAG_APIKEY -> authBag.get.api_key,
            MKT_FLAG_ASSEMBLY_ID -> asm_id,
            MKT_FLAG_COMP_ID -> (uir.get._1 + uir.get._2))), input.artifacts,
        input.related_components, input.operations, input.status, input.state, input.repo, "Megam::Components", DateHelper.now())
      json.some
    }
  }

  def createLinks(authBag: Option[io.megam.auth.stack.AuthBag], input: ComponentsList, asm_id: String): ValidationNel[Throwable, ComponentLists] = {
    var res = (ComponentLists.empty).successNel[Throwable]
    if (input.isEmpty) {
      res = (ComponentLists.empty).successNel[Throwable]
    } else {
      res = (input map { asminp => (create(authBag, asminp, asm_id))
      }).foldRight((ComponentLists.empty).successNel[Throwable])(_ +++ _)
    }
    res
  }


  def create(authBag: Option[io.megam.auth.stack.AuthBag], input: Component, asm_id: String): ValidationNel[Throwable, ComponentLists] = {
    for {
      ogsi <- mkComponentSack(authBag, input, asm_id) leftMap { err: NonEmptyList[Throwable] => err }
      set <- (insertNewRecord(ogsi.get) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Component.created successfully", Console.RESET))
      nels(ogsi)
    }
  }


}
