# TODO

_Specification \<number\>_ below refers to the section in the paper [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf) describing the CRDT.

Scratched out means DONE.

## Write documentation

Write docs for: 

* <del>The functionality and semantics of the CvRDT</del>
* <del>The JSON view of each CvRDT</del>
* <del>Simple JavaScript demo querying the REST server</del>
* How to start up the cluster service
* LevelDB and other storage options
* REST API 
* Scala CRDT API
* Scala ClusterClient API 
* How to run the DemoRestServer
* Architecture (pub/sub, leveldb) etc.
* Configuration
* More background and concepts around CRDT, CAP and CALM
* Explain difference between CvRDT and CmRDT


## CvRDTs (state-based)

### Counters

* <del>Specification 6  State-based increment-only counter</del>
* <del>Specification 7  State-based PN-Counter</del>

### Sets

* <del>Specification 11 State-based grow-only Set (G-Set)</del>
* <del>Specification 12 State-based 2P-Set</del>

### Registers

* Specification 8       State-based Last-Writer-Wins Register (LWW Register)
* Specification 10      State-based Multi-Value Register (MV-Register)

## CmRDTs (ops-based)

### Counters

* Specification 5 op-based Counter

### Sets

* Specification 9 Op-based LWW-Register
* Specification 13 U-Set: Op-based 2P-Set with unique elements
* Specification 14 Molli, Weiss, Skaf Set
* Specification 15 Op-based Observed-Remove Set (OR-Set)

### Graphs

* Specification 16 2P2P-Graph (op-based)
* Specification 17 Add-only Monotonic DAG (op-based)
* Specification 18 Add-Remove Partial Order

### Array

* Specification 19 Replicated Growable Array (RGA)

### Shopping Cart

* Specification 21 Op-based Observed-Remove Shopping Cart (OR-Cart)

## Misc Stuff

### Garbage Collection

* How? Needs consensus? Performed by Leader/Singleton?

### Reliable Broadcast

* Using eventsourced - [Use the Akka 2.2 branch](https://github.com/eligosource/eventsourced/tree/wip-akka-2.2).

### Client API 
* Write a JavaScript CRDT library. So a client can grab the real JSON representation, modify it, do the merges as needed and then push the result back to the server.
* Java API for CRDT classes/ClusterClient