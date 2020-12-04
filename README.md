# nosql vclock project

## About

This project is one that deals with the different aspects of maintaining 
a distributed system in the context of the CAP theorem. Our project's main 
scope is to finish implementing the code on the distributed system to support AP. 

## Vector Clocks
Invariably, maintaining the same versions of documents/data replicated across 
many nodes will become an issue. Since they are not a single source, there is 
possibility that conflicts may arise in the nodes on what version of the data is 
correct. 

To handle this issue, I will discuss the concept of vector clocks. 
Vector clocks in principle are simple. Each node will handle the "clock" on a 
data/document record. This "clock" contains two parts of information, the node, 
and the increment. For example in this project's 5 node cluster, each document on
a node has a set of 5 vclocks. When a request to write data comes in to one of the
of the nodes, it will write, then replicate the change to the other nodes via sync
requests. Here is where vclocks come in handy. When the receiving node compares
vclocks, if all the increment values on each node in its vclock are <= the ones on
the incoming request, then the node should accept the request as the incoming
version is likely newer and more up to date. However, if the comparison yields 
both <= and >, then somewhere along the way, that sending was updated with a change
that hasn't yet propogated to the receiving node. So now, it will handle the
conflict according to rules set by the needs of the user. In this project, the rule 
was that the node maintaining the higher vclock increment number on the highest 
node in the document vclock wins. If there is a tie keep going down. For example:

### vclock-incoming vs. vclock-local
### 1:1			1:0
### 2:4			2:4
### 3:1			3:1
### 4:2			4:3
### 5:3			5:3

Since the local vclock version maintains that node 5 updated a third time as does the 
incoming vclock, there is a tie, so it will look at the next node, 4. Here, the 
local vclock maintains a higher version number on node 4 than the incoming, so it
rejects the sync. 


## Coding
I had two particular sections I had to implement as well as fix some other
minor issues. Most importantly was how to handle vclock comparison and merging. 
The vclocks will always merge even if there is a conflict to maintain
consistency. The first was the "update" case in the function sync_document(). 
The second was the "delete" case. Both cases verify if they should sync(accept) the
request with the should_sync() function. If accepted, "update" updates the record 
in the doc and writes it to the db as well. If a "delete" sync is accepted, the 
data in the record will be removed. 



## References used:
Why Vector Clocks are Easy
https://riak.com/why-vector-clocks-are-easy/index.html
Why Vector Clocks are Hard
https://riak.com/posts/technical/why-vector-clocks-are-hard/

These resources helped introduce the concept to me. The second resource especially
was useful as it provided a visual representation of how the nodes would communicate
and where vclocks would come in to help manage the different versions. 



