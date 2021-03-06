/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.route

import java.util.NoSuchElementException

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, MalformedRequestContentRejection, RejectionHandler}
import com.fasterxml.jackson.core.JsonParseException
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.joda.time.DateTime
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.ext._
import org.json4s.jackson.JsonMethods._

import scala.reflect.ClassTag
import scalaz.Scalaz._
import scalaz._

case class JsonErrors(errors: List[String])

trait JsonValidationRejection extends Directives {
  implicit def validationRejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        case MalformedRequestContentRejection(msg, MappingException(_, _)) =>
          complete(
            HttpResponse(
              status = StatusCodes.BadRequest,
              entity = HttpEntity(ContentTypes.`application/json`, msg)
            ))
      }
      .result()
}

// TODO: An update to json4s changed the date time formatting.  In order to stay compatible, had to
// revert the date time formatting here.  When changing to circe (updating to java8 instant),
// be sure to check the format of date time
object VinylDateParser {
  def parse(s: String, format: Formats): Long =
    format.dateFormat
      .parse(s)
      .map(_.getTime)
      .getOrElse(throw new MappingException(s"Invalid date format $s"))
}
case object VinylDateTimeSerializer
    extends CustomSerializer[DateTime](
      format =>
        (
          {
            case JString(s) => new DateTime(VinylDateParser.parse(s, format))
            case JNull => null
          }, {
            case d: DateTime => JString(format.dateFormat.format(d.toDate))
          }
      ))

trait JsonValidationSupport extends Json4sSupport {

  import scala.collection._

  // this is where you define all serializers, custom and validating serializers
  val serializers: Traversable[Serializer[_]]

  // TODO: needed in order to stay backward compatible for date time formatting,
  // should be removed when we upgrade json libs
  val dtSerializers = List(
    DurationSerializer,
    InstantSerializer,
    VinylDateTimeSerializer,
    IntervalSerializer(),
    LocalDateSerializer(),
    LocalTimeSerializer(),
    PeriodSerializer)

  /**
    * Returns an adjusted set of serializers excluding the serializer passed in.  This is needed otherwise
    * we will get a StackOverflow
    *
    * @param ser The serializer to be removed from the formats
    *
    * @return An adjusted Formats without the serializer passed in
    */
  private[route] def adjustedFormats(ser: Serializer[_]) =
    DefaultFormats ++ JodaTimeSerializers.all ++ serializers.filterNot(_.equals(ser))

  implicit def json4sJacksonFormats: Formats = DefaultFormats ++ dtSerializers ++ serializers
}

trait JsonValidation extends JsonValidationSupport {

  type JsonDeserialized[T] = ValidationNel[String, T]

  /**
    * Simplifies creation of a ValidationSerializer
    */
  def JsonV[A: Manifest]: ValidationSerializer[A] = new ValidationSerializer[A] {}

  def JsonV[A: Manifest](validator: JValue => JsonDeserialized[A]): ValidationSerializer[A] =
    new ValidationSerializer[A] {
      override def fromJson(jv: JValue) = validator(jv)
    }

  def JsonV[A: Manifest](
      validator: JValue => JsonDeserialized[A],
      serializer: A => JValue): ValidationSerializer[A] =
    new ValidationSerializer[A] {
      override def fromJson(jv: JValue) = validator(jv)
      override def toJson(a: A) = serializer(a)
    }

  /**
    * Simplifies creation of an Enum Serializer
    */
  def JsonEnumV[E <: Enumeration: ClassTag](enum: E): EnumNameSerializer[E] =
    new EnumNameSerializer[E](enum)

  /**
    * Main guy to support validating serialization.  Extends the json4s [[Serializer]] interface.
    *
    * Has to implement BOTH the serialize AND deserialize methods.
    *
    * Here are some challenges:
    * You cannot simply do something like def serialize(implicit format: Formats) = Extraction.decompose(a)(formats)
    * and try and delegate back to Json4s
    *
    * If you do this, then you will StackOverflow as Extraction.decompose will enter back into its "hunt for the
    * right serializer" and call the same serialize function again.
    *
    * The workaround is that either you MUST implement the serialize method (which stinks), or you have to dynamically
    * remove the current serializer wwhen you delegate (see the toJson method below).
    *
    * Otherwise, you can override the fromJson method and provide a function that takes a JValue and returns
    * a ValidationNel[String, T] (aliased by the type JsonDeserialized[T])
    */
  abstract class ValidationSerializer[A: Manifest] extends Serializer[A] {

    val Class: Class[_] = implicitly[Manifest[A]].runtimeClass

    def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
      case a: A => toJson(a)
    }

    /**
      * A PartialFunction that takes a scala reflect [[TypeInfo]].  The way this is run in Json4s land
      * is that Json4s tests this serializer by saying PartialFunction.isDefinedAt[XXXXType].  So, if the
      * partial function does not match, then Json4s keeps trying other things
      *
      * @param format passed in by Json4s
      *
      * @return A deserialized T
      */
    def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), A] = {
      case (TypeInfo(Class, _), json) =>
        fromJson(json) match {
          case Success(a: A) => a
          case Failure(err) =>
            throw new MappingException(compact(render("errors" -> err.list.toSet)(format)))
        }
    }

    // delegates to Extraction.decompose using a subset of all of the serializers (which is
    // all serializers minus this one; this is how we avoid the StackOverflow
    def toJson(a: A): JValue = Extraction.decompose(a)(subsetFormats)

    /**
      * Override this to define your own custom validations
      *
      * @param js A [[JValue]] to be validated and deserialized
      *
      * @return A ValidationNel[String, T] that will contain either the deserialized type T
      *         or a list of String that contain errors
      */
    def fromJson(js: JValue): JsonDeserialized[A] =
      try {
        Extraction.extract(js, TypeInfo(Class, None))(subsetFormats) match {
          case a: A => a.successNel[String]
          case _ => "Extraction.extract returned unexpected type".failureNel[A]
        }
      } catch {
        case _: MappingException | _: JsonParseException =>
          s"Failed to parse ${Class.getSimpleName.replace("$", "")}".failureNel[A]
      }

    // use a subset of formats that does not include this class or we will StackOverflow
    private def subsetFormats = adjustedFormats(this)
  }

  /**
    * Pimps json4s JValue types to provide the opportunity to add validations
    *
    * @param json The [[JValue]] being pimped
    */
  implicit class JsonValidationImprovements(json: JValue) {

    def extractType[T: Manifest](default: => JsonDeserialized[T]): JsonDeserialized[T] =
      json match {
        case JNothing | JNull => default

        case j =>
          try {
            // Call the Json4s extractor and wrap it in a Success NEL
            j.extract[T].successNel[String]
          } catch {
            case MappingException(err, _) =>
              try {
                // Retrieves nested errors and adds to JSON structure
                (parse(err) \ "errors")
                  .extractOpt[List[String]]
                  .flatMap(_.toNel)
                  .map(_.failure[T])
                  .getOrElse(default)
              } catch {
                case _: JsonParseException =>
                  err.failureNel[T]
              }
            case e: JsonParseException =>
              s"While parsing $json, received unexpected error '${e.getMessage}'".failureNel[T]
          }
      }

    def extractEnum[E <: Enumeration](enum: E)(
        default: => JsonDeserialized[E#Value]): JsonDeserialized[E#Value] = {
      lazy val invalidMsg =
        s"Invalid ${enum.getClass.getSimpleName.replace("$", "")}".failureNel[E#Value]

      json match {
        case JNothing | JNull => default
        case JString(s) =>
          try {
            enum.withName(s).successNel[String]
          } catch {
            case _: NoSuchElementException => invalidMsg
          }
        case _ => invalidMsg
      }
    }

    /**
      * Indicates that the value needs to be present
      *
      * @param msg The message to return if the value is not present
      *
      * @return The type extracted from JSON, or a failure with the message specified if not present
      */
    def required[T: Manifest](msg: => String): JsonDeserialized[T] = extractType(msg.failureNel[T])

    /**
      * Indicates that the value is optional
      *
      * @return The value parsed, or None if the value was not present
      */
    def optional[T: Manifest]: JsonDeserialized[Option[T]] =
      extractType[Option[T]](None.successNel[String])

    /**
      * Sets a default value if the type could not be extracted from Json
      *
      * @param default The default value to set when the value is not present
      *
      * @return The value that was parsed, or the default
      */
    def default[T: Manifest](default: => T): JsonDeserialized[T] =
      extractType[T](default.successNel[String])

    /**
      * Indicates that the value needs to be present
      *
      * @param msg The message to return if the value is not present
      *
      * @return The type extracted from JSON, or a failure with the message specified if not present
      */
    def required[E <: Enumeration](enum: E, msg: => String): JsonDeserialized[E#Value] =
      extractEnum(enum)(msg.failureNel[E#Value])

    /**
      * Indicates that the value is optional
      *
      * @return The value parsed, or None if the value was not present
      */
    def optional[E <: Enumeration](enum: E): JsonDeserialized[Option[E#Value]] =
      extractEnum(enum)(Success(null))
        .map(Option(_))

    /**
      * Sets a default value if the type could not be extracted from Json
      *
      * @param default The default value to set when the value is not present
      *
      * @return The value that was parsed, or the default
      */
    def default[E <: Enumeration](enum: E, default: => E#Value): JsonDeserialized[E#Value] =
      extractEnum(enum)(default.successNel[String])
  }

  /**
    * Extends the ValidationNel to provide a check and findFailure function
    */
  implicit class ValidationNelImprovements[E: Order, A](base: ValidationNel[E, A]) {

    /**
      * Aggregates validations on contained success by checking boolean conditions
      *
      * Takes a map from E (usually strings) to a boolean function on the success type. If the boolean function
      * evaluates false, the left side of the map (again, the string) is returned in a failureNel. These are
      * aggregated to show all failed validations, or the success is returned if all checks passed.
      *
      * @param validations mapping of some orderable class (usually strings) to boolean
      *                    functions on the success type.
      * @return The aggregated failure messages or the successfully validated type
      */
    def check(validations: (E, (A => Boolean))*): ValidationNel[E, A] =
      validations
        .map({ case (err, func) => base.ensure(err.wrapNel)(func) })
        .fold(base)(_.findFailure(_))
        .leftMap(_.distinct)

    def checkif(b: Boolean)(validations: (E, (A => Boolean))*): ValidationNel[E, A] =
      if (b) base.check(validations: _*) else base

    /**
      * Modeled off of `findSuccess` to combine failures and favor failures over successes, returning only the first
      * if both are successful
      */
    def findFailure[EE >: E, AA >: A](that: => ValidationNel[EE, AA]): ValidationNel[EE, AA] =
      (base, that) match {
        case (Failure(a), Failure(b)) => Failure(a.append(b))
        case (Failure(_), _) => base
        case (_, Failure(_)) => that
        case _ => base
      }
  }
}
