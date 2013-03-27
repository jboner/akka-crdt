/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

import scala.collection.immutable

import play.api.libs.json._

import java.util.UUID

/**
LWW-Element-Set is like 2P-Set: it comprises an add G-Set (A) and a remove
G-Set (R), with a timestamp for each element. To add an element e, add (e,
timestamp) to the add set A. To remove e, add (e, timestamp) to the remove set
R. An element is present iff it is in A, and no *newer* element exists in R.
Merging is accomplished by taking the union of all A and all R, respectively.

Since the last write wins, we can safely take only the largest add, and the
largest delete. All others can be pruned.

When A and R have equal timestamps, the direction of the inequality determines
whether adds or removes win. {'bias': 'a'} indicates that adds win. {'bias':
'r'} indicates that removes win. The default bias is 'a'.

Timestamps may be *any* ordered primitive: integers, floats, strings, etc. If a
coordinated unique timestamp service is used, LWW-Element-Set behaves like a
traditional consistent Set structure. If non-unique timestamps are used, the
resolution of the timestamp determines the window under which conflicts will be
resolved by the bias towards adds or deletes.

TODO: define sorting strategies for strings. By byte value, UTF-8 ordering,
numeric, etc...

In JSON, we write the set as a list of 2- or 3-tuples: [element, add-time] or
[element, add-time, delete-time]

JSON:

``` javascript
{
  'type': 'lww-e-set',
  'bias': 'a',
  'e': [
    ['a', 0],
    ['b', 1, 2],
    ['c', 2, 1],
    ['d', 3, 3]
  ]
}
```

In this set:

- a was created at 0 and still exists.
- b was deleted after creation; it does not exist.
- c was created after deletion; it exists
- d was created and deleted at the same time. Bias a means we prefer adds, so it exists.
*/
case class LastWriterWinsSet[T](
  val id: String,
  private[crdt] val increments: AddSet[T],
  private[crdt] val decrements: AddSet[T]) extends ConvergentReplicatedDataTypeSet[T] {

  val `type`: String = "lww-set"

  def +(element: T): LastWriterWinsSet[T] = {
    if ((increments contains element) && (decrements contains element)) throw new IllegalStateException(s"Can not add $element - already removed from set") // was previously removed
    else new LastWriterWinsSet(id, increments + element, decrements)
  }

  def -(element: T): LastWriterWinsSet[T] = {
    if (increments contains element) new LastWriterWinsSet(id, increments, decrements + element)
    else throw new IllegalStateException(s"Can not remove $element - not in set")
  }

  def merge(that: LastWriterWinsSet[T]): LastWriterWinsSet[T] =
    new LastWriterWinsSet(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  def toSet: immutable.Set[T] = increments.toSet -- decrements.toSet
}

object LastWriterWinsSet {
  def apply[T](): LastWriterWinsSet[T] = {
    apply(UUID.randomUUID.toString)
  }

  def apply[T](id: String): LastWriterWinsSet[T] = {
    new LastWriterWinsSet(id, AddSet[T](id = id + "/inc"), AddSet[T](id = id + "/dec"))
  }

  implicit object stringFormat extends Format[LastWriterWinsSet[String]] {
    def reads(json: JsValue): JsResult[LastWriterWinsSet[String]] = JsSuccess(new LastWriterWinsSet(
      (json \ "id").as[String],
      (json \ "increments").as[AddSet[String]],
      (json \ "decrements").as[AddSet[String]]
    ))

    def writes(set: LastWriterWinsSet[String]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }

  implicit object jsValueFormat extends Format[LastWriterWinsSet[JsValue]] {
    def reads(json: JsValue): JsResult[LastWriterWinsSet[JsValue]] = JsSuccess(new LastWriterWinsSet(
      (json \ "id").as[String],
      (json \ "increments").as[AddSet[JsValue]],
      (json \ "decrements").as[AddSet[JsValue]]
    ))

    def writes(set: LastWriterWinsSet[JsValue]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }
}
