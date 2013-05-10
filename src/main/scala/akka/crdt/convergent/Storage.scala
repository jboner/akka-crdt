/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.util.Try
import scala.reflect.ClassTag

/**
 * 'findById' is called concurrently, 'store' always from a single actor.
 */
trait Storage {
  def findById[T : ClassTag](id: String): Try[T]
  def store(counter: GCounter): Unit
  def store(counter: PNCounter): Unit
  def store(set: GSet): Unit
  def store(set: TwoPhaseSet): Unit
}

class InMemoryStorage extends Storage {
  @volatile private var gCounters    = Map.empty[String, GCounter]
  @volatile private var pnCounters   = Map.empty[String, PNCounter]
  @volatile private var gSets        = Map.empty[String, GSet]
  @volatile private var twoPhaseSets = Map.empty[String, TwoPhaseSet]

  def findById[T : ClassTag](id: String): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val result = 
    	if (classOf[GCounter].isAssignableFrom(clazz)) 			   gCounters.get(id)
	    else if (classOf[PNCounter].isAssignableFrom(clazz))   pnCounters.get(id)
	    else if (classOf[GSet].isAssignableFrom(clazz)) 			 gSets.get(id)
	    else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) twoPhaseSets.get(id)
	    else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
    result.getOrElse(throw new NoSuchElementException(s"Could not fint CRDT with id [$id]")).asInstanceOf[T]
  }
 
  def store(counter: GCounter): Unit =  gCounters = gCounters + (counter.id -> counter)
  def store(counter: PNCounter): Unit = pnCounters = pnCounters + (counter.id -> counter)
  def store(set: GSet): Unit = 				  gSets = gSets + (set.id -> set)
  def store(set: TwoPhaseSet): Unit = 	twoPhaseSets = twoPhaseSets + (set.id -> set)
}

class BytecaskStorage extends Storage {

  def findById[T : ClassTag](id: String): Try[T] = Try {
  	null.asInstanceOf[T]
  }

  def store(counter: GCounter): Unit = {
  }

  def store(counter: PNCounter): Unit = {
  }

  def store(set: GSet): Unit = {
  }

  def store(set: TwoPhaseSet): Unit = {
  }
}
