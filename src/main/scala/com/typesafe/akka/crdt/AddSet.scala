/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

/**
 * Implements a CRDT G-Set.
 *
 * Set union is commutative and convergent; hence it is always safe to have
 * simultaneous writes to a set *which only allows addition*. You cannot remove an
 * element of a G-Set.
 */
case class AddSet[T](private[crdt] val state: Set[T] = Set.empty[T]) extends CRDTSet {

  def +(element: T): AddSet[T] = AddSet(state + element)

  def merge(that: AddSet[T]): AddSet[T] = AddSet(that.state ++ this.state)
}
