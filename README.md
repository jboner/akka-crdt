# Akka CRDT

**NOTE**: This project was an experiment conducted in 2013 to build an eventually consistent REST and JSON database using CRDTs, Akka Cluster and LevelDB. Some of its ideas has now been rolled in to Akka itself through the [Akka Distributed Data](http://doc.akka.io/docs/akka/snapshot/scala/distributed-data.html) module (which is production-grade).

**Server-managed CRDT database.**

## Introduction

The goal of this project is to provide a server-managed CRDT database build on top of Akka Cluster. We plan to implement most of the known CRDTs (Conflict-free Replicated Data Types), both CvRDTs and CmRDTs (see below for the difference). It is meant to be used both from within an Akka application and as a stand-alone REST service speaking JSON.

In short; a CRDT is a data type in which operations always commute and/or state changes always converge. CRDTs gives you very scalable eventual consistency "for free". Most CRDTs are a very limited data structures that does not fit all problems, but when they do, they really excel. There are multiple different CRDTs discovered: counters, sets, graphs etc. A data structure that is made up of CRDTs is also a CRDT, which makes it possible to create rich and advanced data structures that have the same nice properties. 

A full outline of what CRDTs are all about is out of scope for this documentation. For good introduction to the subject read the excellent paper [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf) by Mark Shapiro et. al. 

## Simple Example

```scala
// from within an Akka actor
import context._

// get the database extension
val db = ConvergentReplicatedDataTypeDatabase(system)

// find the 'users' GCounter 
val nrOfUsers: Future[GCounter] = db.findById[GCounter]("users") 

// increment the counter by 1 for this actor and then store it
nrOfUsers map { _ + self.path } foreach { _.store(system) }
```

----
**NOTE**: This is __work in progress__ and is currently to be treated as a Proof of Concept. Apart from hardening and bug fixing etc. you can find some of the outstanding issues in the [TODO](https://github.com/jboner/akka-crdt/blob/master/TODO.md) list. If you find this project interesting please join us and help out. 

----

## Architecture Overview

The implementation is provided as an [Akka Extension](http://doc.akka.io/docs/akka/snapshot/scala/extending-akka.html) and build on top of [Akka Cluster](http://doc.akka.io/docs/akka/snapshot/common/cluster.html#cluster), which means that the cluster is fully elastic - allowing you to add and remove nodes on the fly as-needed. The storage system is pluggable with a default implementation of [LevelDB](http://code.google.com/p/leveldb/) (both native and Java port). 

The CRDTs are immutable and can be queried, updated and managed either directly using the Akka Extension from within actors running on each node or from the "outside" through a REST API serving JSON over HTTP (see below for details). The serialisation protocol/format is pure JSON to make it possible to integrate with other CRDT client libraries (for example one in JavaScript - which is on the [TODO](https://github.com/jboner/akka-crdt/blob/master/TODO.md) list). The JSON library used internally is [play-json](http://www.playframework.com/documentation/2.1.1/ScalaJson) and the REST API is implemented using [Unfiltered](http://unfiltered.databinder.net/Unfiltered.html). Testing is done with ScalaTest, Akka Testkit and Dispatch. 

There are two different implementations: 

* **CvRDTs** - Convergent Replicated Data Types (completed as PoC)
* **CmRDTs** - Commutative Replicated Data Types (to be implemented)

### CvRDTs

CvRDTs (Convergent Replicated Data Types) are _state-based_ which means that every instance keeps the full history of all changes that have ever happened to the datastructure and is therefore self-contained and fault-tolerant by design. The convergent nature of the CvRDTs means that state updates do not need to obey any ordering requirements, the only requirement is that all state changes eventually reaches all replicas, and the system will converge. 

In this implementation, which is is based on Akka Cluster and Akka Remote, the change sets are batched up (using a configurable batching window - default is 10 ms) and replicated out to the rest of the cluster nodes in an eventually consistent fashion. The eventual consistent nature of the system means that there will be a slight delay in the consistency between the nodes. Each batch is sent to all the nodes in the cluster and is then awaiting an ACK from each node. If no ACK is received then it will retry every T millis (configurable) indefinitely until either an ACK is received or the node is [removed from the cluster](http://doc.akka.io/docs/akka/snapshot/scala/cluster-usage.html#Leaving).

CvRDTs have some really nice properties that makes them fairly straightforward to implement in a reliable fashion without having too hard requirements on the reliability of the underlying infrastructure. This makes them nice to work with in less reliable environments that allows clients to go offline, doing updates and then rejoin and merge in its state changes, on an ad-hoc basis. However, the simplicity in which state is managed—through keeping the full history in the CvRDT itself—has one major problem; keeping the growth of the history under control. Garbage collection of the history in a CvRDT, in a consistent fashion, across a cluster of nodes is a problem that is still under active research. This means that (for now) one have to either keep the update rate of the CvRDTs low in order to keep the history growth under control, or turn to CmRDTs (see next section). 

### CmRDTs

**Not implemented yet** 

CmRDT (Commutative Replicated Data Types) are _operations-based_ which means that it is the state changing events, and *not* the state changes themselves that are stored. These events are persisted in an event/transaction log and a CmRDT is brought up to its current state by simply replaying the event log. This solves the problem with unbound growth of history, as seen in CvRDTs, since the history is kept in a event log, outside the CmRDT. It also opens up for optimisations like sharding. 

CmRDTs require a reliable broadcast channel. This can be implemented either through a single highly-available replicated event log, or (perhaps more interesting) letting each replica set (group of cluster nodes) have its own event log as a form of sharding in order to avoid introducing a [SPOF](http://en.wikipedia.org/wiki/Single_point_of_failure) (single point of failure—unless a replicated highly-available log is used) and SPOB (single point of bottleneck). This approach also opens up for optimisations like storing a snapshot of the CmRDT and replaying from the snapshot etc.—as in classic [Event Sourcing](http://martinfowler.com/eaaDev/EventSourcing.html). 

Another advantage is that it makes it a lot easier to create dynamic clusters where nodes can leave and join at any point in time. In CvRDTs you have to do full replication of the database including all its history when a new node joins, using buddy-replication or similar, while in the case of CmRDTs you just replay the transaction log from the latest snapshot.

A concrete implementation could based on a persistent transaction log architecture realised through the upcoming [akka-persistence](https://groups.google.com/forum/#!msg/akka-user/k3N_LgJR5WM/CqhsY00KBgkJ) library. 

## Akka Extension & Scala API

You can create the ``ConvergentReplicatedDataTypeDatabase`` Extension like this (from within an actor): 

```scala
val db = ConvergentReplicatedDataTypeDatabase(context.system)
```

Find a new CRDT by id:

```scala
val nrOfUsers: Future[GCounter] = db.findById[GCounter]("users")
```

Shut down the database:

```scala
db.shutdown()
```
    
## REST Server & REST API

You can run the REST server in two different ways. 

1. Run it in stand-alone mode from the command line. Here you run it by invoking ``sbt run`` in the project root directory. You configure it through JVM options, which will override the default configuration values. Example: ``sbt run -Dakka.crdt.rest-server.port=9009``. You shut down the server by invoking ``Control-C`` which cleans up all resources and by default this destroys the LevelDB database (deletes the files), if you don't want this behaviour then start it up with ``-Dakka.crdt.convergent.leveldb.destroy-on-shutdown=off``.

2. Embedded in an Akka application (see the 'Embedded Server' section below for details). To do this just create the extension using ``val db = ConvergentReplicatedDataTypeDatabase(system)`` and off you go. The REST server will be started automatically if the ``akka.crdt.rest-server.run`` is set to ``on``. 

Each CRDT has a read-only JSON view representation which is used in the REST API for querying data. For details on the REST API and the different JSON views see the section with the different CRDT descriptions below. 

The REST client can contact any of the nodes in the cluster to read or write any data. Normally cluster would be deployed with a load balancer in front of it that can balance the load evenly across the cluster.

## LevelDB

[LevelDB](http://code.google.com/p/leveldb/) is the default storage engine, you can configure it to look for a native installation and fall back to a Java port, or it will use the Java port directly. See the configuration file options for details on this and other config options. 

Each node has its own LevelDB database. The database files are by default stored in the ``leveldb`` directory in the root directory of the server, but this is configurable. The name of the database files needs to be unique on the machine you are running it on and is now prefixed with the hostname and port of the Akka Cluster node. Please note that if you don't specify the cluster port in the configuration file then a random one is chosen, which means that you will not be able to read in the same database between system restarts. Therefore, if this is important to you, you have to specify the cluster port explicitly in the configuration file or as JVM option when you boot up the system. 
    
You can configure if the LevelDB database should be destroyed on node shutdown through the ``akka.crdt.convergent.leveldb.destroy-on-shutdown`` option.
    
## Convergent Replicated Data Types (CvRDTs)

**State-based**

CvRDT descriptions below are taken from the [MeanGirls](https://github.com/aphyr/meangirls) README.

A nice property of CRDTs is that a data structure made up by CRDTs is also a CRDT. Which lets you create rich data structures from simple ones. 

### G-Counter

#### High-Level Description

A ``g-counter`` is a grow-only counter (inspired by vector clocks) in
which only increment and merge are possible. Incrementing the counter
adds 1 to the count for the current actor. Divergent histories are
resolved by taking the maximum count for each actor (like a vector
clock merge).  The value of the counter is the sum of all actor
counts.

#### Scala API

Create a ``g-counter``: 

```scala
val nrOfUsers: GCounter = db.create[GCounter]("users")
```

Or: 

```scala
val nrOfUsers: GCounter = GCounter("users")
nrOfUsers.store(context.system)
```

Find a ``g-counter`` by id: 

```scala
val nrOfUsers: Future[GCounter] = db.findById[GCounter]("users")
```

Find or Create a ``g-counter``: 

```scala
val nrOfUsers: Future[GCounter] = db.findOrCreate[GCounter]("users")
```

Increment the counter by 1: 

```scala
val nodeId = "some-unique-node-id"
    
val updatedNrOfUsers: GCounter = nrOfUsers + nodeId
```

Increment the counter with a delta: 

```scala
val updatedNrOfUsers: GCounter = nrOfUsers + (nodeId, 5)
```

Get the value of the counter: 

```scala
val count: Int = nrOfUsers.value
```

Merge two counters: 

```scala
val mergedCounter: GCounter = thisCounter merge thatCounter
```

Store updates to a ``g-counter``: 

```scala
// from within an actor
counter.store(system)
```
Or:

```scala
db.update(counter)
```

Get the view (the current value) of the counter:

```scala
val nrOfUsersView: GCounterView = nrOfUsers.view
```

Get JSON of the view: 

```scala
val json: JsValue = nrOfUsersView.toJson

val jsonAsString: String = nrOfUsersView.toString
```

Get JSON of the counter (internal serialized representation): 

```scala
val json: JsValue = nrOfUsers.toJson

val jsonAsString: String = nrOfUsers.toString
```

#### REST API

##### PUT
Create g-counter by id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/g-counter/users
```

Create a g-counter with a random id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/g-counter
```

##### GET
Find g-counter by id.

```bash
curl -i -H "Accept: application/json" http://127.0.0.1:9009/g-counter/users
```

##### POST
Increment the g-counter with 'delta'

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "delta=1" \
    http://127.0.0.1:9009/g-counter/users
```

##### JSON View 
The JSON returned from the REST calls.

```json
{
    "type": "g-counter",
    "id": "users",
    "value": 1
}
```

#### Serialization Format
This is the internal representation of a ``g-counter``:

```json
{
    "type": "g-counter",
    "id": "users",
    "state": {
        "node1": 2,
        "node2": 3
    }
}
```

### PN-Counter

#### High-Level Description

A ``pn-counter`` allows the counter to be decreased by tracking the
increments (P) separate from the decrements (N), both represented as
internal G-Counters.  Merge is handled by merging the internal P and N
counters. The value of the counter is the value of the P counter minus
the value of the N counter.

#### Scala API

Create a ``pn-counter``: 

```scala
val nrOfUsers: Future[PNCounter] = db.create[PNCounter]("users")
```

Or: 

```scala
val nrOfUsers: PNCounter = PNCounter("users")
nrOfUsers.store(context.system)
```

Find a ``pn-counter`` by id: 

```scala
val nrOfUsers: Future[PNCounter] = db.findById[PNCounter]("users")
```

Increment the counter by 1: 

```scala
val nodeId = "some-unique-node-id"
    
val updatedNrOfUsers: PNCounter = nrOfUsers + nodeId
```

Decrement the counter by 1: 

```scala
val nodeId = "some-unique-node-id"
    
val updatedNrOfUsers: PNCounter = nrOfUsers - nodeId
```

Increment the counter with a delta: 

```scala
val updatedNrOfUsers: PNCounter = nrOfUsers + (nodeId, 5)
```

Decrement the counter with a delta: 

```scala
val updatedNrOfUsers: PNCounter = nrOfUsers - (nodeId, 7)
```

Get the value of the counter: 

```scala
val count: Int = nrOfUsers.value
```

Merge two counters: 

```scala
val mergedCounter: PNCounter = thisCounter merge thatCounter
```

Store updates to a ``pn-counter``: 

```scala
// from within an actor
counter.store(system)
```
Or:

```scala
db.update(counter)
```

Get the view (the current value) of the counter: 

```scala
val nrOfUsersView: PNCounterView = nrOfUsers.view
```

Get JSON of the view: 

```scala
val json: JsValue = nrOfUsersView.toJson

val jsonAsString: String = nrOfUsersView.toString
```

Get JSON of the counter (internal serialized representation): 

```scala
val json: JsValue = nrOfUsers.toJson

val jsonAsString: String = nrOfUsers.toString
```

#### REST API

##### PUT
Create pn-counter by id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/pn-counter/users
```

Create a pn-counter with a random id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/pn-counter
```

##### GET
Find pn-counter by id.

```bash
curl -i -H "Accept: application/json" http://127.0.0.1:9009/pn-counter/users
```

##### POST
Increment the pn-counter with 'delta' > 0

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "delta=1" \
    http://127.0.0.1:9009/pn-counter/users
```

Decrement the pn-counter with 'delta' < 0

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "delta=-1" \
    http://127.0.0.1:9009/pn-counter/users
```

##### JSON View
The JSON returned from the REST calls.

```json
{
    "type": "pn-counter",
    "id": "users",
    "value": 1
}
```

#### Serialization Format
This is the internal representation of a ``pn-counter``:

```json
{
    "type": "pn-counter",
    "id": "users",
    "increments": {
        "type": "g-counter",
        "id": "users/inc",
        "state": {
            "node1": 3,
            "node2": 6
        }
    },
    "decrements": {
        "type": "g-counter",
        "id": "users/dec",
        "state": {
            "node1": 2,
            "node2": 2
        }
    }
}
```
        
### G-Set

#### High-Level Description

Set union is commutative and convergent; hence it is always safe to have 
simultaneous writes to a set *which only allows addition*. You cannot 
remove an element of a ``g-set``. A ``GSet`` can only contain JSON values 
of type ``JsValue`` (play-json).

#### Scala API

Create a ``g-set``: 

```scala
val users: Future[GSet] = db.create[GSet]("users")
```

Or: 

```scala
val users: GSet = GSet("users")
users.store(context.system)
```

Find a ``g-set`` by id: 

```scala
val users: Future[GSet] = db.findById[GSet]("users")
```

Add JSON element to the set: 

```scala
val user: JsValue = Json.parse("""{"username":"john","password":"coltrane"}""")
    
val updatedUsers: GSet = users + user
```

Get the value of the set: 

```scala
val userSet: immutable.Set[JsValue] = users.value
```

Merge two sets: 

```scala
val mergedSet: GSet = thisSet merge thatSet
```

Store updates to a ``g-set``: 

```scala
// from within an actor
set.store(system)
```
Or:

```scala
db.update(set)
```

Get the view (the current value) of the set: 

```scala
val usersView: GSetView = users.view
```

Get JSON of the view: 

```scala
val json: JsValue = usersView.toJson

val jsonAsString: String = usersView.toString
```

Other methods on ``GSet``: 

```scala
def contains(element: JsValue): Boolean
def foreach(f: JsValue ⇒ Unit): Unit
def isEmpty: Boolean
def size: Int
```

Get JSON of the set (internal serialized representation): 

```scala
val json: JsValue = users.toJson

val jsonAsString: String = users.toString
```

#### REST API

##### PUT
Create g-set with specific id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/g-set/users
```

Create g-set with random id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/g-set
```

##### GET
Find g-set by id.

```bash
curl -i -H "Accept: application/json" http://127.0.0.1:9009/g-set/users
```

##### POST
Add JSON data to the g-set.

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
    http://127.0.0.1:9009/g-set/users/add
```

##### JSON View
The JSON returned from the REST calls.

```json
{
    "type": "g-set",
    "id": "users",
    "value": [{
            "username": "john",
            "password": "coltrane"
        }, {
            "username": "charlie",
            "password": "parker"
        }
    ]
}
```

#### Serialization Format
This is the internal representation of a ``g-set``:

```json
{
    "type": "g-set",
    "id": "users",
    "value": [{
            "username": "john",
            "password": "coltrane"
        }, {
            "username": "charlie",
            "password": "parker"
        }
    ]
}
```
    
### 2P-Set

#### High-Level Description

``2p-set`` sets consist of two ``g-sets``: one for adding, and another for removing. 
To add an element, add it to the add set A. To remove e, add e to the remove 
set R.  An element can only be added once, and only removed once. Elements can 
only be removed if they are present in the set. Removes take precedence over adds.
A ``TwoPhaseSet`` can only contain JSON values of type ``JsValue`` (play-json).

#### Scala API

Create a ``2p-set``: 

```scala
val users: Future[TwoPhaseSet] = db.create[TwoPhaseSet]("users")
```

Or: 

```scala
val users: TwoPhaseSet = TwoPhaseSet("users")
users.store(context.system)
```

Find a ``2p-set`` by id: 

```scala
val users: Future[TwoPhaseSet] = db.findById[TwoPhaseSet]("users")
```

Add JSON element to the set: 

```scala
val user: JsValue = Json.parse("""{"username":"john","password":"coltrane"}""")
    
val updatedUsers: TwoPhaseSet = users + user
```

Remove a JSON element from the set: 

```scala
val updatedUsers: TwoPhaseSet = users - user
```

Get the value of the set: 

```scala
val userSet: immutable.Set[JsValue] = users.value
```

Merge two sets: 

```scala
val mergedSet: TwoPhaseSet = thisSet merge thatSet
```

Store updates to a ``2p-set``: 

```scala
// from within an actor
set.store(system)
```
Or:

```scala
db.update(set)
```

Get the view (the current value) of the set: 

```scala
val usersView: TwoPhaseSetView = users.view
```

Get JSON of the view: 

```scala
val json: JsValue = usersView.toJson
```

Get JSON of the view: 

```scala
val json: JsValue = usersView.toJson

val jsonAsString: String = usersView.toString
```

Other methods on ``TwoPhaseSet``: 

```scala
def contains(element: JsValue): Boolean
def foreach(f: JsValue ⇒ Unit): Unit
def isEmpty: Boolean
def size: Int
```

Get JSON of the set (internal serialized representation): 

```scala
val json: JsValue = users.toJson

val jsonAsString: String = users.toString
```

#### REST API

##### PUT
Create 2p-set with specific id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/2p-set/users
```

Create 2p-set with random id.

```bash
curl -i -H "Accept: application/json" -X PUT http://127.0.0.1:9009/2p-set
```

##### GET
FInd a 2p-set by id.

```bash
curl -i -H "Accept: application/json" http://127.0.0.1:9009/2P-Set-set/users
```

##### POST
Add JSON data to the 2p-set.

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
    http://127.0.0.1:9009/2p-set/users/add
```

Remove JSON data from the 2p-set.

```bash
curl -i -H "Accept: application/json" \
    -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
    http://127.0.0.1:9009/2p-set/users/remove
```
        
##### JSON View
The JSON returned from the REST calls.

```json
{
    "type": "2p-set",
    "id": "users",
    "value": [{
            "username": "john",
            "password": "coltrane"
        }, {
            "username": "charlie",
            "password": "parker"
        }
    ]
}
```
    
#### Serialization Format
This is the internal representation of a ``2p-set``:

```json
{
    "type": "2p-set",
    "id": "users",
    "adds": {
        "type": "g-set",
        "id": "users/adds",
        "state": [{
                "username": "john",
                "password": "coltrane"
            }, {
                "username": "sonny",
                "password": "rollins"
            }, {
                "username": "charlie",
                "password": "parker"
            }
        ]
    },
    "removes": {
        "type": "g-set",
        "id": "users/removes",
        "state": [{
                "username": "sonny",
                "password": "rollins"
            }
        ]
    }
}    
```

## Commutative Replicated Data Types (CmRDTs)
**Operations-based**

To be implemented. 

## Subscribe To Updated CRDTs via the EventBus

All changes (newly merged CRDTs) are published to [Akka Event Bus](http://doc.akka.io/docs/akka/snapshot/scala/event-bus.html). If you are interested in getting these events you can just subscribe to them. Here is an example:

```scala
val listener = system.actorOf(Props(new Actor {
  override def preStart(): Unit =
    context.system.eventStream.subscribe(self, classOf[ConvergentReplicatedDataType])
  override def postStop(): Unit =
    context.system.eventStream.unsubscribe(self)

  def receive = {
    case updatedCRDT: ConvergentReplicatedDataType ⇒ 
      // handle the updated CRDT 
  }
}))
```

## Configuration 

This is the configuration where you can configure the REST server, backend storage systems etc. 

```ruby
akka {
  crdt {
    rest-server {
      run      = on
      hostname = "127.0.0.1"
      port     = 9009
    }
    convergent {
      batching-window                  = 10ms
      change-set-resubmission-interval = 1000ms

      # needs to implement the 'akka.crdt.convergent.Storage' trait
      storage-class   = akka.crdt.convergent.LevelDbStorage
    
      # if native version is found that it is used - 
      # else it falls back to Java port of LevelDB    
      leveldb {                       
        storage-path         = "./leveldb"  # directory for the database storage files  
        destroy-on-shutdown  = off          # deletes the database files for the 
                                            # specific node on shutdown 
        use-fsync            = off          # use fsync on write
        verify-checksums     = off          # verify checksums on write
        use-native           = off          # try to find native LevelDB, if not 
                                            # found then Java port will be used
        cache-size           = 104857600    # max size of the in-memory cache
      }
    }
    commutative {
    }
  }
}
```
