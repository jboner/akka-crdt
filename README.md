# Akka CRDT

Server-managed CRDT storage for Akka

For more info about CRDTs read [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf) by Mark Shapiro et. al.

## CvRDTs - Convergent Replicated Data Types 

**State-based**

CvRDT descriptions taken from the [MeanGirls](https://github.com/aphyr/meangirls) README.

### G-Counter

A G-Counter is a grow-only counter (inspired by vector clocks) in
which only increment and merge are possible. Incrementing the counter
adds 1 to the count for the current actor. Divergent histories are
resolved by taking the maximum count for each actor (like a vector
clock merge).  The value of the counter is the sum of all actor
counts.

#### API

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
        
##### JSON
    {
      'type': 'g-counter',
      'id': 'hits',
      'value': 1
    }

### PN-Counter

PN-Counters allow the counter to be decreased by tracking the
increments (P) separate from the decrements (N), both represented as
internal G-Counters.  Merge is handled by merging the internal P and N
counters. The value of the counter is the value of the P counter minus
the value of the N counter.

##### GET
Find pn-counter by id, or create it if it does not exist.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/pn-counter/jonas

Create a pn-counter with a random id.

    curl -i -H "Accept: application/json" http://127.0.0.1:9000/pn-counter

##### POST
Increment the pn-counter with 'delta' > 0

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "delta=1" \
        http://127.0.0.1:9000/pn-counter/active-users
        
Decrement the pn-counter with 'delta' < 0

    curl -i -H "Accept: application/json" \
        -X POST -d "node=darkstar" -d "delta=-1" \
        http://127.0.0.1:9000/pn-counter/active-users
        
##### JSON
    {
      'type': 'pn-counter',
      'id': 'active-users',
      'value': 1
    }

### G-Set

Set union is commutative and convergent; hence it is always safe to have 
simultaneous writes to a set *which only allows addition*. You cannot 
remove an element of a G-Set.

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
        
##### JSON
    {
      'type': 'g-set',
      'id' : 'users',      
      'value': [{'username': 'john','password': 'coltrane'}]
    }

### 2P-Set

2-phase sets consist of two g-sets: one for adding, and another for removing. 
To add an element, add it to the add set A. To remove e, add e to the remove 
set R.  An element can only be added once, and only removed once. Elements can 
only be removed if they are present in the set. Removes take precedence over adds.

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
        
##### JSON
    {
      'type': '2p-set',
      'id' : 'users',      
      'value': [{'username': 'john','password': 'coltrane'}]
    }

## CmRDTs - Commutative Replicated Data Types 
**Operations-based**


TODO