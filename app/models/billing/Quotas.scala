package models.billing

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
import models.Constants._
import io.megam.auth.stack.AccountResult
import io.megam.auth.stack.{ Name, Phone, Password, States, Approval, Dates, Suspend }
import models.tosca.{ KeyValueField, KeyValueList}
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
import controllers.stack.ImplicitJsonFormats


/**
 * @author rajthilak
 *
 */
case class QuotasInput(name: String, account_id: String, allowed: KeyValueList, allocated_to: String, inputs: KeyValueList, quota_type: String, status: String)

case class QuotasResult(
    id: String,
    name: String,
    account_id: String,
    allowed: models.tosca.KeyValueList,
    allocated_to: String,
    inputs: models.tosca.KeyValueList,
    quota_type: String,
    status: String,
    json_claz: String,
    created_at: DateTime,
    updated_at: DateTime) {

    val ALLOWED = List("snapshot")

    val  moveApproval =  (Option(quota_type) match {
      case Some(succ) => ((succ.trim.length >0) && !ALLOWED.contains(succ.trim))
      case None       => false
    })

}

  case class QuotasUpdateInput(
        id: String,
        account_id: String,
        allowed: models.tosca.KeyValueList,
        allocated_to: String,
        inputs: models.tosca.KeyValueList,
        quota_type: String,
        status: String
        )

sealed class QuotasSacks extends CassandraTable[QuotasSacks, QuotasResult] with ImplicitJsonFormats {

  object id extends StringColumn(this) with PrimaryKey[String]
  object name extends StringColumn(this)
  object account_id extends StringColumn(this) with PartitionKey[String]

  object allowed extends JsonListColumn[QuotasSacks, QuotasResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object allocated_to extends StringColumn(this)

  object inputs extends JsonListColumn[QuotasSacks, QuotasResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }
  object quota_type extends StringColumn(this)
  object status extends StringColumn(this)
  object json_claz extends StringColumn(this)
  object created_at extends DateTimeColumn(this) with PrimaryKey[DateTime]
  object updated_at extends DateTimeColumn(this)

  def fromRow(row: Row): QuotasResult = {
    QuotasResult(
      id(row),
      name(row),
      account_id(row),
      allowed(row),
      allocated_to(row),
      inputs(row),
      quota_type(row),
      status(row),
      json_claz(row),
      created_at(row),
      updated_at(row))
  }
}

abstract class ConcreteQuotas extends QuotasSacks with RootConnector {

  override lazy val tableName = "quotas"
  override implicit def space: KeySpace = scyllaConnection.space
  override implicit def session: Session = scyllaConnection.session

  def insertNewRecord(qs: QuotasResult): ValidationNel[Throwable, ResultSet] = {
    val res = insert.value(_.id, qs.id)
      .value(_.name, qs.name)
      .value(_.account_id, qs.account_id)
      .value(_.allowed, qs.allowed)
      .value(_.allocated_to, qs.allocated_to)
      .value(_.inputs, qs.inputs)
      .value(_.quota_type, qs.quota_type)
      .value(_.status, qs.status)
      .value(_.json_claz, qs.json_claz)
      .value(_.created_at, qs.created_at)
      .value(_.updated_at, qs.updated_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

  def updateRecord(email: String, rip: QuotasUpdateInput, aor: Option[QuotasResult]): ValidationNel[Throwable, ResultSet] = {
    val oldallocated_to  = aor.get.allocated_to
    val newallocated_to = rip.allocated_to

    val oldallowed = aor.get.allowed
    val newallowed = rip.allowed

    val res = update.where(_.account_id eqs email).and(_.created_at eqs aor.get.created_at).and(_.id eqs rip.id)
      .modify(_.allocated_to setTo NilorNot(newallocated_to, oldallocated_to))
      //.and(_.allowed setTo NilorNot(newallowed, oldallowed))

      .and(_.status setTo NilorNot((if (rip.status != null && rip.status.trim.length >0) rip.status else null), aor.get.status))
      .and(_.updated_at setTo DateHelper.now())
      .future()
      Await.result(res, 5.seconds).successNel
  }

  def listRecords(email: String): ValidationNel[Throwable, Seq[QuotasResult]] = {
    val res = select.where(_.account_id eqs email).fetch()
    Await.result(res, 5.seconds).successNel
  }

  def getRecord(id: String): ValidationNel[Throwable, Option[QuotasResult]] = {
    val res = select.allowFiltering().where(_.created_at lte DateHelper.now()).and(_.id eqs id).one()
    Await.result(res, 5.seconds).successNel
  }

  def deleteRecords(email: String): ValidationNel[Throwable, ResultSet] = {
    val res = delete.where(_.account_id eqs email).future()
    Await.result(res, 5.seconds).successNel
  }

   def NilorNot(rip: String, bal: String): String = {
    rip == null match {
      case true => return bal
      case false => return rip
    }
  }
}

object Quotas extends ConcreteQuotas {


  private def mkQuotasSack(email: String, input: String): ValidationNel[Throwable, QuotasResult] = {
    val quotasInput: ValidationNel[Throwable, QuotasInput] = (Validation.fromTryCatchThrowable[QuotasInput, Throwable] {
      parse(input).extract[QuotasInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      quota <- quotasInput
      uir <- (UID("quo").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      new QuotasResult(uir.get._1 + uir.get._2, quota.name, email, quota.allowed, quota.allocated_to, quota.inputs, quota.quota_type, quota.status, "Megam::Quotas", DateHelper.now(), DateHelper.now())
    }
  }


  def create(email: String, input: String): ValidationNel[Throwable, Option[QuotasResult]] = {
    for {
      wa <- (mkQuotasSack(email, input) leftMap { err: NonEmptyList[Throwable] => err })
      set <- (insertNewRecord(wa) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      if(wa.moveApproval) atAccUpdate(email)

      play.api.Logger.warn(("%s%s%-20s%s%s").format(Console.GREEN, Console.BOLD, "Quotas","|+| ✔", Console.RESET))
      wa.some
    }
  }

  def update(email: String, input: String): ValidationNel[Throwable, QuotasResult] = {
    val ripNel: ValidationNel[Throwable, QuotasUpdateInput] = (Validation.fromTryCatchThrowable[QuotasUpdateInput,Throwable] {
      parse(input).extract[QuotasUpdateInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel

    for {
      rip <- ripNel
      qor <- (Quotas.findById(rip.id) leftMap { t: NonEmptyList[Throwable] => t })
      set <- updateRecord(email, rip, qor.head)
    } yield {
      qor.head.get
    }
  }


  def findByEmail(email: String): ValidationNel[Throwable, Seq[QuotasResult]] = {
    (listRecords(email) leftMap { t: NonEmptyList[Throwable] =>
      new ResourceItemNotFound(email, "Quotas = nothing found.")
    }).toValidationNel.flatMap { nm: Seq[QuotasResult] =>
      if (!nm.isEmpty)
        Validation.success[Throwable, Seq[QuotasResult]](nm).toValidationNel
      else
        Validation.failure[Throwable, Seq[QuotasResult]](new ResourceItemNotFound(email, "Quotas = nothing found.")).toValidationNel
    }
  }

  def findById(id: String): ValidationNel[Throwable, QuotasResults] = {
    (getRecord(id) leftMap { t: NonEmptyList[Throwable] ⇒
      new ServiceUnavailableError(id, (t.list.map(m ⇒ m.getMessage)).mkString("\n"))
    }).toValidationNel.flatMap { xso: Option[QuotasResult] ⇒
      xso match {
        case Some(xs) ⇒ {
          Validation.success[Throwable, QuotasResults](List(xs.some)).toValidationNel
        }
        case None ⇒ Validation.failure[Throwable, QuotasResults](new ResourceItemNotFound(id, "")).toValidationNel
      }
    }
  }

  def delete(email: String): ValidationNel[Throwable, Option[QuotasResults]] = {
    deleteRecords(email) match {
      case Success(value) => Validation.success[Throwable, Option[QuotasResults]](none).toValidationNel
      case Failure(err) => Validation.success[Throwable, Option[QuotasResults]](none).toValidationNel
    }
  }
}
