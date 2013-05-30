# Akka CRDT

**Server-managed CRDT storage for Akka.**

A full outline of what CRDTs are all about is out of scope for this documentation. For good introduction to the subject read the excellent paper [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf) by Mark Shapiro et. al. 

## Architecture Overview

The implementation is provided as an [Akka Extension](http://doc.akka.io/docs/akka/snapshot/scala/extending-akka.html) and build on top of [Akka Cluster](http://doc.akka.io/docs/akka/snapshot/common/cluster.html#cluster), which means that the cluster is fully elastic - allowing you to add and remove nodes on the fly as-needed. The storage system is pluggable with a default implementation of [LevelDB](http://code.google.com/p/leveldb/) (both native and Java port). 

The CRDTs are immutable and can be queried, updated and managed either directly using the Akka Extension from within actors running on each node or from the "outside" through a REST API serving JSON over HTTP (see below for details). The serialisation protocol/format is pure JSON to make it possible to integrate with other CRDT client libraries. The JSON library used is [play-json](http://www.playframework.com/documentation/2.1.1/ScalaJson).

There are two different implementations: 

* **CvRDTs** - Convergent Replicated Data Types (completed as PoC)
* **CmRDTs** - Commutative Replicated Data Types (to be implemented)

### CvRDTs

CvRDTs are _state-based_ and do not require a fully reliable broadcast since every instance keeps the full history and is therefore self-contained and fault-tolerant by design. The implementation is based on Akka Cluster's Publish Subscribe, which is very efficient, have high-throughput and low latency, but is not reliable. In the CvRDT implementation the change sets are batched up (using a configurable batching window - default is 10 ms) and replicated out to the rest of the cluster nodes in an eventually consistent fashion. The eventual consistent nature of the system means that there will be a slight delay in the consistency between the nodes, however, the system guarantees that you read your writes if you talk to a single cluster node. 

### CmRDTs

**Not implemented yet** 

CmRDT are _operations-based_ and do require a fully reliable broadcast since only the events are stored and a CmRDT is brought up to its current state by replaying the event log. This implementation is based on a persistent transaction log realised through the [eventsourced](https://github.com/eligosource/eventsourced) library.

## Scala Server API

You can create the ``ConvergentReplicatedDataTypeDatabase`` Extension like this (from within an actor): 

    val storage = ConvergentReplicatedDataTypeDatabase(context.system)

Get (or create a new) CRDT ``g-counter`` by id:

    val nrOfUsers = storage.getOrCreate[GCounter]("users")

Create a new CRDT with a random id:

    val nrOfUsers = storage.getOrCreate[GCounter]

Store the updated CRDT:

    storage update updatedNrOfUsers

Shut down the ``ConvergentReplicatedDataTypeDatabase`` Extension:

    storage.shutdown()

Although it will also be shut down automatically when the actor system is shut down.
    
## REST Server

The REST server will be started automatically by the ``ConvergentReplicatedDataTypeDatabase`` Extension if the ``akka.crdt.rest-server.run`` is set to ``on``.  

There is also a demo server you can run by invoking ``sbt run`` in the project root directory or by other means starting up the ``akka.crdt.convergent.DemoRestServer`` main class. If needed we can create  fully fledged configurable command line server as well for those that just want a REST based CRDT storage and are not using it from within/alongside an Akka application. 

Each CRDT has a read-only JSON view representation which is used in the REST API for querying data. For details on the REST API and the different JSON views see the section with the different CRDT descriptions below. 

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

Create a ``g-counter`` in Scala: 

    val nrOfUsers = GCounter(id = "users")

Increment the counter by 1: 

    val nodeId = "some-unique-node-id"
    
    val updatedNrOfUsers = nrOfUsers + nodeId

Increment the counter with a delta: 

    val updatedNrOfUsers = nrOfUsers + (nodeId, 5)

Get the value of the counter: 

    val count = nrOfUsers.value

Merge two counters: 

    val mergedCounter = thisCounter merge thatCounter

Get JSON view of the counter: 

    val json = nrOfUsers.view

#### REST API

##### GET
Find g-counter by id, or create it if it does not exist.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/g-counter/hits

Create a g-counter with a random id.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/g-counter

##### POST
Increment the g-counter with 'delta'

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "delta=1" \
        http://127.0.0.1:9000/g-counter/hits
        
##### JSON View
    {
      'type': 'g-counter',
      'id': 'hits',
      'value': 1
    }

#### Serialization Format
This is the internal representation of a ``g-counter``:

    {
        "type": "g-counter",
        "id": "hits",
        "state": {
            "node1": 2,
            "node2": 3
        }
    }

### PN-Counter

#### High-Level Description

A ``pn-counter`` allows the counter to be decreased by tracking the
increments (P) separate from the decrements (N), both represented as
internal G-Counters.  Merge is handled by merging the internal P and N
counters. The value of the counter is the value of the P counter minus
the value of the N counter.

#### Scala API

Create a ``pn-counter`` in Scala: 

    val nrOfUsers = PNCounter(id = "users")

Increment the counter by 1: 

    val nodeId = "some-unique-node-id"
    
    val updatedNrOfUsers = nrOfUsers + nodeId

Decrement the counter by 1: 

    val nodeId = "some-unique-node-id"
    
    val updatedNrOfUsers = nrOfUsers - nodeId

Increment the counter with a delta: 

    val updatedNrOfUsers = nrOfUsers + (nodeId, 5)

Decrement the counter with a delta: 

    val updatedNrOfUsers = nrOfUsers - (nodeId, 7)

Get the value of the counter: 

    val count = nrOfUsers.value

Merge two counters: 

    val mergedCounter = thisCounter merge thatCounter

Get JSON view of the counter: 

    val json = nrOfUsers.view

#### REST API

##### GET
Find pn-counter by id, or create it if it does not exist.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/pn-counter/users

Create a pn-counter with a random id.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/pn-counter

##### POST
Increment the pn-counter with 'delta' > 0

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "delta=1" \
        http://127.0.0.1:9000/pn-counter/users
        
Decrement the pn-counter with 'delta' < 0

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "delta=-1" \
        http://127.0.0.1:9000/pn-counter/users
        
##### JSON View
    {
      'type': 'pn-counter',
      'id': 'active-users',
      'value': 1
    }

#### Serialization Format
This is the internal representation of a ``pn-counter``:

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
        
### G-Set

#### High-Level Description

Set union is commutative and convergent; hence it is always safe to have 
simultaneous writes to a set *which only allows addition*. You cannot 
remove an element of a ``g-set``.

#### Scala API

Create a ``g-set`` in Scala: 

    val users = GSet(id = "users")

Add JSON element to the set: 

    val user = Json.parse("""{"username":"john","password":"coltrane"}""")
    
    val updatedUsers = users + user

Merge two sets: 

    val mergedSet = thisSet merge thatSet

Get JSON view of the set: 

    val json = users.view

#### REST API

##### GET
Find g-set by id, or create it if it does not exist.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/g-set/users

Create a g-set with a random id.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/g-set

##### POST
Add JSON data to the g-set.

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
        http://127.0.0.1:9000/g-set/users/add
        
##### JSON View
    {
      'type': 'g-set',
      'id' : 'users',      
      'value': [{'username': 'john','password': 'coltrane'}]
    }

#### Serialization Format
This is the internal representation of a ``g-set``:

    {
        "type": "g-set",
        "id": "users",
        "state": [{
                "username": "john",
                "password": "coltrane"
            }, {
                "username": "sonny",
                "password": "rollins"
            }
        ]
    }
    
### 2P-Set

#### High-Level Description

``2p-set`` sets consist of two ``g-sets``: one for adding, and another for removing. 
To add an element, add it to the add set A. To remove e, add e to the remove 
set R.  An element can only be added once, and only removed once. Elements can 
only be removed if they are present in the set. Removes take precedence over adds.

#### Scala API

Create a ``2p-set`` in Scala: 

    val users = TwoPhaseSet(id = "users")

Add JSON element to the set: 

    val user = Json.parse("""{"username":"john","password":"coltrane"}""")
    
    val updatedUsers = users + user

Remove a JSON element from the set: 

    val updatedUsers = users - user

Merge two sets: 

    val mergedSet = thisSet merge thatSet

Get JSON view of the set: 

    val json = users.view

#### REST API

##### GET
Find 2p-set by id, or create it if it does not exist.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/2p-set/users

Create a 2p-set with a random id.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/2p-set

##### POST
Add JSON data to the 2p-set.

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
        http://127.0.0.1:9000/2p-set/users/add
        
Remove JSON data from the 2p-set.

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "{"username":"john","password":"coltrane"}" \
        http://127.0.0.1:9000/2p-set/users/remove
        
##### JSON View
    {
      'type': '2p-set',
      'id' : 'users',      
      'value': [{'username': 'john','password': 'coltrane'}]
    }

#### Serialization Format
This is the internal representation of a ``2p-set``:

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

## Commutative Replicated Data Types (CmRDTs)
**Operations-based**

To be implemented. 

## Configuration 

This is the configuration where you can configure the REST server, backend storage systems etc. 

    akka {
      crdt {
        rest-server {
          run      = on
          hostname = "127.0.0.1"
          port     = 9000
        }
        convergent {
          batching-window = 10ms
          
          # needs to implement the 'akka.crdt.convergent.Storage' trait
          storage-class   = akka.crdt.convergent.LevelDbStorage
        
          # if native version is found that it is used - 
          # else it falls back to Java port of LevelDB    
          leveldb {                       
            storage-path     = "./leveldb"  # directory for the database storage files  
            use-fsync        = off          # use fsync on write
            verify-checksums = off          # verify checksums on write
            use-native       = off          # try to find native LevelDB, if not 
                                            # found then Java port will be used
            cache-size       = 104857600    # max size of the in-memory cache
          }
        }
        commutative {
        }
      }
    }

