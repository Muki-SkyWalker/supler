package org.supler.field

import org.json4s.JsonAST.JValue
import org.json4s._
import org.supler.errors._
import org.supler.{FieldPath, Form, Util}

case class SubformField[T, U, Cont[_]](
  c: SubformContainer[Cont],
  name: String,
  read: T => Cont[U],
  write: (T, Cont[U]) => T,
  _label: Option[String],
  embeddedForm: Form[U],
  // if not specified, `embeddedForm.createEmpty` will be used
  createEmpty: Option[() => U],
  renderHint: RenderHint with SubformFieldCompatible) extends Field[T] {
  
  import c._
  
  def label(newLabel: String) = this.copy(_label = Some(newLabel))

  def renderHint(newRenderHint: RenderHint with SubformFieldCompatible) = this.copy(renderHint = newRenderHint)

  private[supler] def generateJSON(parentPath: FieldPath, obj: T) = {
    val valuesAsJValue = read(obj).zipWithIndex.map { case (v, indexOpt) =>
      embeddedForm.generateJSON(pathWithOptionalIndex(parentPath, indexOpt), v)
    }

    c.combineJValues(valuesAsJValue).map { combinedValuesAsJValue =>
      import JSONFieldNames._
      List(JField(name, JObject(
        JField(Type, JString(SpecialFieldTypes.Subform)),
        JField(RenderHint, JObject(JField("name", JString(renderHint.name)) :: renderHint.extraJSON)),
        JField(Multiple, JBool(value = true)),
        JField(Label, JString(_label.getOrElse(""))),
        JField(Path, JString(parentPath.append(name).toString)),
        JField(Value, combinedValuesAsJValue)
      )))
    }.getOrElse(Nil)
  }

  override private[supler] def applyJSONValues(parentPath: FieldPath, obj: T, jsonFields: Map[String, JValue]): PartiallyAppliedObj[T] = {
    def valuesWithIndex = c.valuesWithIndexFromJSON(jsonFields.get(name))
    val paos = valuesWithIndex.map { case (formJValue, indexOpt) =>
      embeddedForm.applyJSONValues(pathWithOptionalIndex(parentPath, indexOpt),
        createEmpty.getOrElse(embeddedForm.createEmpty)(), formJValue)
    }

    c.combinePaos(paos).map(write(obj, _))
  }

  override private[supler] def doValidate(parentPath: FieldPath, obj: T, scope: ValidationScope) = {
    val valuesWithIndex = read(obj).zipWithIndex

    val errorLists = valuesWithIndex.map { case (el, indexOpt) =>
      embeddedForm.doValidate(pathWithOptionalIndex(parentPath, indexOpt), el, scope)
    }

    errorLists.toList.flatten
  }

  override private[supler] def findAction(
    parentPath: FieldPath,
    obj: T,
    jsonFields: Map[String, JValue],
    ctx: RunActionContext) = {

    val values = read(obj)
    val valuesList = read(obj).toList
    val jvaluesWithIndex = c.valuesWithIndexFromJSON(jsonFields.get(name)).toList

    val valuesJValuesIndex = valuesList.zip(jvaluesWithIndex)

    Util
      .findFirstMapped[(U, (JValue, Option[Int])), Option[RunnableAction]](valuesJValuesIndex, { case (v, (jvalue, indexOpt)) =>
        val i = indexOpt.getOrElse(0)
        val updatedCtx = ctx.push(obj, i, (v: U) => write(obj, values.update(v, i)))
        // assuming that the values matches the json (that is, that the json values were previously applied)
        embeddedForm.findAction(pathWithOptionalIndex(parentPath, indexOpt), valuesList(i), jvalue, updatedCtx)
      },
      _.isDefined).flatten
  }

  private def pathWithOptionalIndex(parentPath: FieldPath, indexOpt: Option[Int]) = indexOpt match {
    case None => parentPath.append(name)
    case Some(i) => parentPath.appendWithIndex(name, i)
  }
}

trait SubformContainer[Cont[_]] {
  // operations on any value type
  def map[R, S](c: Cont[R])(f: R => S): Cont[S]
  def toList[R](c: Cont[R]): List[R]
  def update[R](cont: Cont[R])(v: R, i: Int): Cont[R]
  def zipWithIndex[R](values: Cont[R]): Cont[(R, Option[Int])]

  implicit class ContainerOps[R](c: Cont[R]) {
    def map[S](f: R => S) = SubformContainer.this.map(c)(f)
    def toList = SubformContainer.this.toList(c)
    def update(v: R, i: Int) = SubformContainer.this.update(c)(v, i)
    def zipWithIndex = SubformContainer.this.zipWithIndex(c)
  }

  // operations on specific types
  def valuesWithIndexFromJSON(jvalue: Option[JValue]): Cont[(JValue, Option[Int])]
  def combineJValues(jvalues: Cont[JValue]): Option[JValue]
  def combinePaos[R](paosInCont: Cont[PartiallyAppliedObj[R]]): PartiallyAppliedObj[Cont[R]]
}

object SubformContainer {
  implicit object SingleSubformContainer extends SubformContainer[({type Id[a]=a})#Id] {
    def map[R, S](c: R)(f: (R) => S) = f(c)
    def toList[R](c: R) = List(c)
    def update[R](cont: R)(v: R, i: Int) = v
    def zipWithIndex[R](values: R) = (values, None)

    def valuesWithIndexFromJSON(jvalue: Option[JValue]) = (jvalue.getOrElse(JNothing), None)
    def combineJValues(jvalues: JValue) = Some(jvalues)
    def combinePaos[R](paosInCont: PartiallyAppliedObj[R]) = paosInCont
  }

  implicit object OptionSubformContainer extends SubformContainer[Option] {
    def map[R, S](c: Option[R])(f: (R) => S) = c.map(f)
    def toList[R](c: Option[R]) = c.toList
    def zipWithIndex[R](values: Option[R]) = values.map((_, None))
    def update[R](cont: Option[R])(v: R, i: Int) = Some(v)

    def valuesWithIndexFromJSON(jvalue: Option[JValue]) = jvalue.map((_, None))
    def combineJValues(jvalues: Option[JValue]) = jvalues
    def combinePaos[R](paosInCont: Option[PartiallyAppliedObj[R]]) = paosInCont match {
      case None => PartiallyAppliedObj.full(None)
      case Some(paos) => paos.map(Some(_))
    }
  }

  implicit object ListSubformContainer extends SubformContainer[List] {
    def map[R, S](c: List[R])(f: (R) => S) = c.map(f)
    def toList[R](c: List[R]) = c
    def zipWithIndex[R](values: List[R]) = values.zipWithIndex.map { case (v, i) => (v, Some(i))}
    def update[R](cont: List[R])(v: R, i: Int) = cont.updated(i, v)

    def valuesWithIndexFromJSON(jvalue: Option[JValue]) = jvalue match {
      case Some(JArray(jvalues)) => jvalues.zipWithIndex.map { case (v, i) => (v, Some(i))}
      case _ => Nil
    }
    def combineJValues(jvalues: List[JValue]) = Some(JArray(jvalues))
    def combinePaos[R](paosInCont: List[PartiallyAppliedObj[R]]) = PartiallyAppliedObj.flatten(paosInCont)
  }
}