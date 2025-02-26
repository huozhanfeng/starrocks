[sql]
select
    l_shipmode,
    sum(case
            when o_orderpriority = '1-URGENT'
                or o_orderpriority = '2-HIGH'
                then cast (1 as bigint)
            else cast(0 as bigint)
        end) as high_line_count,
    sum(case
            when o_orderpriority <> '1-URGENT'
                and o_orderpriority <> '2-HIGH'
                then cast (1 as bigint)
            else cast(0 as bigint)
        end) as low_line_count
from
    orders,
    lineitem
where
        o_orderkey = l_orderkey
  and l_shipmode in ('REG AIR', 'MAIL')
  and l_commitdate < l_receiptdate
  and l_shipdate < l_commitdate
  and l_receiptdate >= date '1997-01-01'
  and l_receiptdate < date '1998-01-01'
group by
    l_shipmode
order by
    l_shipmode ;
[fragment]
PLAN FRAGMENT 0
OUTPUT EXPRS:25: L_SHIPMODE | 30: sum(28: expr) | 31: sum(29: expr)
PARTITION: UNPARTITIONED

RESULT SINK

10:MERGING-EXCHANGE
use vectorized: true

PLAN FRAGMENT 1
OUTPUT EXPRS:
PARTITION: HASH_PARTITIONED: 25: L_SHIPMODE

STREAM DATA SINK
EXCHANGE ID: 10
UNPARTITIONED

9:SORT
|  order by: <slot 25> 25: L_SHIPMODE ASC
|  offset: 0
|  use vectorized: true
|
8:AGGREGATE (merge finalize)
|  output: sum(30: sum(28: expr)), sum(31: sum(29: expr))
|  group by: 25: L_SHIPMODE
|  use vectorized: true
|
7:EXCHANGE
use vectorized: true

PLAN FRAGMENT 2
OUTPUT EXPRS:
PARTITION: RANDOM

STREAM DATA SINK
EXCHANGE ID: 07
HASH_PARTITIONED: 25: L_SHIPMODE

6:AGGREGATE (update serialize)
|  STREAMING
|  output: sum(28: expr), sum(29: expr)
|  group by: 25: L_SHIPMODE
|  use vectorized: true
|
5:Project
|  <slot 25> : 25: L_SHIPMODE
|  <slot 28> : CASE WHEN (6: O_ORDERPRIORITY = '1-URGENT') OR (6: O_ORDERPRIORITY = '2-HIGH') THEN 1 ELSE 0 END
|  <slot 29> : CASE WHEN (6: O_ORDERPRIORITY != '1-URGENT') AND (6: O_ORDERPRIORITY != '2-HIGH') THEN 1 ELSE 0 END
|  use vectorized: true
|
4:HASH JOIN
|  join op: INNER JOIN (BUCKET_SHUFFLE)
|  hash predicates:
|  colocate: false, reason:
|  equal join conjunct: 1: O_ORDERKEY = 11: L_ORDERKEY
|  use vectorized: true
|
|----3:EXCHANGE
|       use vectorized: true
|
0:OlapScanNode
TABLE: orders
PREAGGREGATION: ON
partitions=1/1
rollup: orders
tabletRatio=10/10
tabletList=10139,10141,10143,10145,10147,10149,10151,10153,10155,10157
cardinality=150000000
avgRowSize=23.0
numNodes=0
use vectorized: true

PLAN FRAGMENT 3
OUTPUT EXPRS:
PARTITION: RANDOM

STREAM DATA SINK
EXCHANGE ID: 03
BUCKET_SHFFULE_HASH_PARTITIONED: 11: L_ORDERKEY

2:Project
|  <slot 25> : 25: L_SHIPMODE
|  <slot 11> : 11: L_ORDERKEY
|  use vectorized: true
|
1:OlapScanNode
TABLE: lineitem
PREAGGREGATION: ON
PREDICATES: 25: L_SHIPMODE IN ('REG AIR', 'MAIL'), 22: L_COMMITDATE < 23: L_RECEIPTDATE, 21: L_SHIPDATE < 22: L_COMMITDATE, 23: L_RECEIPTDATE >= '1997-01-01', 23: L_RECEIPTDATE < '1998-01-01'
partitions=1/1
rollup: lineitem
tabletRatio=20/20
tabletList=10213,10215,10217,10219,10221,10223,10225,10227,10229,10231 ...
cardinality=6124846
avgRowSize=30.0
numNodes=0
use vectorized: true
[end]

