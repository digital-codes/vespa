<!-- Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root. -->

## Errata

### 2023-02-09: Rare edge case may cause memory corruption on content nodes when searching an HNSW index together with an index field and using multiple threads per query

Affected version range: **8.78.54** (first release with documented support for the
feature triggering the bug) up to **8.116.25** (first release with a complete fix).

#### Brief

In early November 2022, Vespa-8.78.54 officially added support for multi-threaded
evaluation of filters in combination with the `nearestNeighbor` query operator.
This feature caused a subtle behavior change that could trigger a long-present,
latent bug in boundary condition handling in low-level bit vector code. This bug
allowed for memory corruptions to take place during query evaluation when a
particular set of conditions was satisfied. This was fixed in Vespa-8.116.25 on
2023-01-25. It is highly advised to update to a version that is at least as recent
as this.

Using multiple threads per query is not enabled by default.

Due to the many conditions required to trigger this bug, the Vespa team has only
confirmed observations of this particular bug on one single application.

#### Details

Vespa supports efficient multi-threaded query evaluation where the internal
document space is partitioned across multiple threads and data structures
are dynamically chosen based on the underlying matching document data.
When evaluating an `OR` clause where the field was an index and the term was
present in more than 3% of the documents, Vespa uses a posting list represented
as a bit vector. When this bit vector had no overlap with the partition a
particular query thread was responsible for, erroneous boundary-tagging code
would potentially set some bits in an 8-byte area that did not belong to the
bit vector.

This could happen if the following conditions were met:

 1. You need to have one or more index fields.
 2. You need to query at least one indexed term that is present in more than 3%
    of the documents.
 3. You need to have an HNSW index that is searched with the `nearestNeighbor`
    operator in the same query as searching the index field.
 4. You need to have configured multiple threads per query.
 5. The ratio of new documents—not counting updates—in the memory index to the total
    number of documents, must be larger than 1 / number of threads per search.

Depending on what these 8 bytes were originally used for, memory corruption could
manifest itself in many ways. Most commonly, the observed effect would be random;
otherwise unexplainable crashes in indirectly affected code, caused by segmentation
faults due to corrupted pointers or assertion failures caused by broken invariants.

If memory was corrupted prior to being written to disk, it's possible that corrupted
data has become persistent in the cluster. If you suspect this to be the case, the
most robust solution is to re-feed your document set after upgrading to a Vespa
version containing the fix.

If corrupted data was written to the transaction log, the content
nodes containing a replica of the corrupted document may enter a crash loop since they
can never successfully replay the log upon startup. Wiping the index on the affected
nodes and re-feeding after upgrading Vespa is the suggested remediation.


### 2021-01-06: Data loss during data migration
This bug was introduced more than 10 years ago and remained unobserved until very recently. Fixed in Vespa-7.306.16.

The following needs to happen to trigger the bug:

* The replica placement for a given data bucket is non-ideal on at least 2 nodes.
  This can happen during mass node retirement or if several nodes come back up after being down.
  In this scenario, the system must move all data away from a subset of replicas. This triggers
  a special internal optimization that tries to handle such moves separately to
  minimize unnecessary data transfer. These are known internally as "source-only" replicas.
* The stored document sets on the source-only bucket replicas are (possibly partially) disjoint.
* The total size of document data contained in a source-only bucket replica exceeds the configured
  data merge transfer chunk size (default 4 MiB)

Tracking of successfully merged documents is done by exchanging bitmasks between the nodes, where bit positions
correspond to document presence on the nth node involved in the merge operation.
The bug caused bitmasks for sub-operations optimized to focus on source-only nodes to not be correctly transformed
onto the bitmask tracking documents across all nodes.
Even when not all documents could be transferred from the source-only node due to exceeding chunk transfer limits,
the system would believe it had transferred _all_ remaining such documents due to the erroneous bitmask transform.

This is a silent data loss bug and would be observed by the global document count in the system decreasing
during a period of data merging.

### 2020-11-30: Document inconsistency
This bug was introduced in Vespa-7.277.38, and fixed in Vespa-7.292.82.
The following needs to happen to trigger the bug:

* visibility-delay is non-zero. Note that the default is zero, so for this to trigger,
  [visibility-delay](https://docs.vespa.ai/en/reference/services-content.html#visibility-delay)
  must have been set.
* A new config change is deployed that contains changes to proton.
  This config snapshot is stored in the transaction log on the content node.
* vespa-proton-bin is restarted, and as part of the prepare for restart step,
  at least one attribute vector is not flushed to the current serial number.
* Due to the bug, replay of the transaction log will fail to replay feed operations to attributes after replaying the config change.
  The effect is that all attributes that were not flushed as part of preparing for restart
  will not get any of the updates since the last time they were flushed.
* If a document was previously removed, lid space compaction will move another document to that local document ID.
  Due to later missing updates as part of restarting, we might see values from the removed document for some of the attributes.
* When the problem attributes are later flushed this inconsistency will be permanent.

Solution:
* Upgrade Vespa to minimum Vespa-7.292.82.
* Complete re-feed of the corpus.



### 2020-11-30: Regression introduced in Vespa 7.141 may cause data loss or inconsistencies when using 'create: true' updates
There exists a regression introduced in Vespa 7.141 where updates marked as `create: true` (i.e. create if missing)
may cause data loss or undetected inconsistencies in certain edge cases.
This regression was introduced as part of an optimization effort to greatly reduce the common-case overhead of updates
when replicas are out of sync.

Fixed in Vespa 7.157.9 and beyond.
If running a version affected (7.141 up to and including 7.147) you are strongly advised to upgrade.

See [#11686](https://github.com/vespa-engine/vespa/issues/11686) for details.
