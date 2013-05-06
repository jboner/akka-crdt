# Akka CRDT

Server-managed CRDT implementation for Akka

# TODO

The "Specification" below refers to the paper [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf) by Mark Shapiro et. al.

## Convergent (state-based)

### Counters

* DONE Specification 6  State-based increment-only counter
* DONE Specification 7  State-based PN-Counter

### Registers

* Specification 8       State-based Last-Writer-Wins Register (LWW Register)
* Specification 10      State-based Multi-Value Register (MV-Register)

### Sets

* DONE Specification 11 State-based grow-only Set (G-Set)
* DONE Specification 12 State-based 2P-Set

### Implementation details

* DONE Use DistributedPubSub for broadcast of changes
* Implement batching of the change sets
* Investigate into using gossip based change set propagation

## Commutative (ops-based)

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

## Misc Implementation Details

### Garbage Collection

* Needs consensus? Performed by Leader/Singleton?

### Reliable Broadcast

* Using eventsourced. [This branch](https://github.com/eligosource/eventsourced/tree/wip-akka-2.2) for now.

### Misc

* Add "format": "string"/"json"/"binary"
* Is java.util.UUID a good id generator? 
* How should we manage JSON marshalling errors?
* Fix documentation
* Support DELETE of a CRDT?

# DOCUMENTATION

Based on README from [MeanGirls](https://github.com/aphyr/meangirls).

## Convergent Data Structures (state-based)

### G-Counter

A G-Counter is a grow-only counter (inspired by vector clocks) in
which only increment and merge are possible. Incrementing the counter
adds 1 to the count for the current actor. Divergent histories are
resolved by taking the maximum count for each actor (like a vector
clock merge).  The value of the counter is the sum of all actor
counts.

JSON:

    {
      'type': 'g-counter',
      'e': {
        'a': 1,
        'b': 5,
        'c': 2
      }
    }

- The counter value is 8.

### PN-Counter

PN-Counters allow the counter to be decreased by tracking the
increments (P) separate from the decrements (N), both represented as
internal G-Counters.  Merge is handled by merging the internal P and N
counters. The value of the counter is the value of the P counter minus
the value of the N counter.

JSON:

    {
      'type': 'pn-counter',
      'p': {
        'a': 10,
        'b': 2
      },
      'n': {
        'c': 5,
        'a': 1
      }
    }

- P=12, N=6, so the value is 6.

### G-Set

Set union is commutative and convergent; hence it is always safe to have simultaneous writes to a set *which only allows addition*. You cannot remove an element of a G-Set.

JSON:

    {
      'type': 'g-set',
      'e': ['a', 'b', 'c']
    }

### 2P-Set

2-phase sets consist of two g-sets: one for adding, and another for removing. To add an element, add it to the add set A. To remove e, add e to the remove set R.  An element can only be added once, and only removed once. Elements can only be removed if they are present in the set. Removes take precedence over adds.

JSON:

    {
      'type': '2p-set',
      'a': ['a', 'b'],
      'r': ['b']
    }

In this set, only 'a' is present.


## Convergent Data Structures (state-based)

### OR-Set

Observed-Removed Sets support adding and removing elements in a causally
consistent manner. It resembles LWW-Set, except that instead of times, unique
tags are associated with each insertion or deletion. In the case of conflicting
add and delete, add wins. An element is a member of the set iff the set of
insertion tags less the set of deletion tags is nonempty.

We write the set as a list of 2- or 3- tuples: [element, [add-tags]] or
[element, [add-tags], [remove-tags]]

To insert e, generate a unique tag, and add it to the insertion tag set for e.

To remove e, take all insertion tags for e, and insert them into the deletion
tags for e.

To merge two OR-Sets, for each element in either set, take the union of the
insertion tags and the union of the deletion tags.

Tags may be any primitive: strings, ints, floats, etc.

JSON:

    {
      'type': 'or-set',
      'e': [
        ['a', [1]],
        ['b', [1], [1]],
        ['c', [1, 2], [2, 3]]
      ]
    }

- a exists.
- b's only insertion was deleted, so it does not exist.
- c has two insertions, only one of which was deleted. It exists.

### Max-Change-Sets

MC-Sets resolve divergent histories for an element by choosing the value which
has changed the most. You cannot delete an element which is not present, and
cannot add an element which is already present. MC-sets are compact and do the
right thing when changes to elements are infrequent compared to the conflict
resolution window, but behave arbitrarily when divergent histories each include
many changes.

Each element e is associated with an integer n, implicitly assumed to be zero.
When n is even, the element is absent from the set. When n is odd, the element
is present. To add an element to the set, increment n from an even value by
one; to remove an element, increment n from an odd value by one. To merge sets,
take each element and choose the maximum value of n from each history.

When n is limited to [0, 2], Max-Change-Sets collapse to 2P-Sets. Unlike
2P-Sets, however, one can add and remove an arbitrary number of times. The
disadvantage is that there is no bias towards preserving adds or removes.
Instead, whichever history has incremented further (undergone more changes) is
preferred.

In JSON, max-change sets are represented as a list of [element, n] tuples.

JSON:

    {
      'type': 'mc-set',
      'e': [
        ['a', 1],
        ['b', 2],
        ['c', 3]
      ]
    }

- a is present
- b is absent
- c is present
