package org.supler

import org.json4s.JField
import org.json4s.JsonAST.{JBool, JInt}

trait Validator[T, U] {
  def doValidate(objValue: T, fieldValue: U): List[ValidationError]
  def generateJSONSchema: List[JField]
}

case class ValidationError(key: String, params: Any*)

trait Validators {
  def minLength[T](minLength: Int) =
    fieldValidator[T, String](_.length < minLength)(_ => ValidationError("Too short"))(List(JField("minLength", JInt(minLength))))

  def maxLength[T](maxLength: Int) =
    fieldValidator[T, String](_.length > maxLength)(_ => ValidationError("Too long"))(List(JField("maxLength", JInt(maxLength))))

  def gt[T](than: Int) =
    fieldValidator[T, Int](_ > than)(_ => ValidationError(s"Must be greater than $than"))(
      List(JField("miniminum", JInt(than)), JField("exclusiveMinimum", JBool(value = true))))

  def lt[T](than: Int) =
    fieldValidator[T, Int](_ < than)(_ => ValidationError(s"Must be less than $than"))(
      List(JField("maximum", JInt(than)), JField("exclusiveMaximum", JBool(value = true))))

  def ge[T](than: Int) =
    fieldValidator[T, Int](_ >= than)(_ => ValidationError(s"Must be greater or equal to $than"))(
      List(JField("miniminum", JInt(than)), JField("exclusiveMinimum", JBool(value = false))))

  def le[T](than: Int) =
    fieldValidator[T, Int](_ <= than)(_ => ValidationError(s"Must be less or equal to $than"))(
      List(JField("maximum", JInt(than)), JField("exclusiveMaximum", JBool(value = false))))


  def custom[T, U](test: (T, U) => Boolean, createError: (T, U) => ValidationError): Validator[T, U] = new Validator[T, U] {
    override def doValidate(objValue: T, fieldValue: U) = {
      if (test(objValue, fieldValue)) {
        List(createError(objValue, fieldValue))
      } else {
        Nil
      }
    }
    override def generateJSONSchema = Nil
  }

  private def fieldValidator[T, U](test: U => Boolean)(createError: U => ValidationError)(jsonSchema: List[JField]) =
    new Validator[T, U] {
      override def doValidate(objValue: T, fieldValue: U) = {
        if (!test(fieldValue)) {
          List(createError(fieldValue))
        } else {
          Nil
        }
      }

      override def generateJSONSchema = jsonSchema
    }
}