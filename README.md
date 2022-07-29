# Project 5: Recovery
:::info
💡  第五个proj主要根据ARIES Recovery Algorithm实现 recovery manager的forward processing和restart recovery的功能，总共分为7个task，内容分别是

1. log record的类型为commit abort 或者end时维护transaction table中transaction的状态信息
1. log record类型与part和page相关时维护transaction table和dirty table的状态信息
1. 实现savepoint，用于用户主动回滚
1. 实现check point，用于减少crash后restart recovery的消耗
1. 实现ARIES Recovery Algorithm的崩溃恢复的第一个阶段：Analysis Phase
1. 实现ARIES Recovery Algorithm的崩溃恢复的第二个阶段：Redo Phase
1. 实现ARIES Recovery Algorithm的崩溃恢复的第一个阶段：Undo Phase
   :::
### 复习一下recovery的课程内容

- [x] recovery的功能

数据库有ACID四大特性，recovery主要实现的是A和D即原子性和持久化
原子性：一个transaction只有commit和abort两种状态，没有中间状态
持久化：commit后所有的改动都应该是permanent

- [x] No Steal/Steal，Forced/Not Forced的区别和侧重点

这两种都是实现原子性和持久化两个思路。
Steal特性指的是，由于buffer pages是有限的，当buffer满的时候允许还未commit的transaction page提前写入disk给其他的page腾地方，Not steal则反之，只能在commit时transaction的dirty page才能刷入磁盘，no steal实现较简单，但是有性能问题，buffer满了就会阻塞等待其他transaction commit。**若支持steal需要额外机制用于undo 已写入的内容以实现原子性**
Forced 特性指的每个transaction在commit前必须把所有更改的data page刷回磁盘，这样无需其他的机制就可以保证commit后的持久化，但是这样会带来大量的IO，同样会带来性能问题，**若采用lazy也就是not Forced的方式，需要一定的机制来保证crash后redo 已经commit但是还未刷入磁盘的transaction以实现持久性**

- [x] transaction table和dirty page table主要存储什么信息

transaction table主要存储当前所有transaction运行状态如COMMITTING ABORYT END，以及最近执行的log record 对应的LSN
DPT记录当前所有log record中有修改data page但还未刷入磁盘的pageNUM和最早修改该page对应的log record 的LSN

- [x] 什么是WAL

write ahead logging，为了最好的性能我们采用Steal+Not Forced的方式，这样就需要额外的机制实现redo和undo的操作，这就是log，用log record记录每个操作的内容，wal有两项要求

1. 为了能实现undo已经写入磁盘的操作，必须在每次data page刷入磁盘前先将log record 刷入磁盘，防止data刷进去了但是log还未刷入crash了，就无从undo了
1. 为了能实现redo，必须在transaction commit前先将该transaction涉及的所有的log record包括commit record先刷入磁盘，这样的话万一commit后data page还未刷入磁盘时crash，还能从log中redo一遍
- [x] LSN的作用

log sequence number，logrecord的唯一标识，只会增长，LSN的功能主要用于实现WAL的requirements：

1. undo过程中通过log record中的prev LSN跟踪同一个transaction的上一条log record
1. redo时判断某条log record中的操作是否真的写入过磁盘：
    1. 根据WAL中data刷入磁盘前log record必须先刷入磁盘，所以redo过程中log record关联的disk page上的pageLSN大于log record的LSN时该log record必定已经写入过磁盘了
    1. redo过程中发现dirty table中不存在该page或者page的recLSN大于log record的LSN，则必定已经写入磁盘了
- [x] ARIES Recovery Algorithm的具体流程是怎么样的

这是wal 以及steal/Not Forced recovery的一种实现算法，主要分为三个阶段

1. Analysis Phase：reconstructs the Xact Table and the DPT

从最近的一个checkpoint开始直到崩溃前重建transaction table以及dirty page table的信息

2. Redo Phase: repeats operations to ensure durability

从dirty table中找到最小的rec LSN开始往后redo所有的update操作，除了已经写入磁盘的record（通过LSN，recLSN以及pageLSN之间的相互关系判断）

3. Undo Phase: undoes operations from transactions that were running during the crash to

ensure atomicity
将未commit的transaction中已经写入磁盘的操作全部undo，同时产生一种特殊的CLR的log record，回滚至transaction创建前的状态，由于前一步redo已经把所有的写入磁盘的操作都执行了一遍，所以undo阶段无需再判断是否真的写入了磁盘，通过prevLSN和undoNextLSN不断往前回滚即可，最后更新transaction table的状态


### Task 1: Transaction Status

- [x] commit的特殊性

由于之前提及的WAL的第二项requirement，commit前必须将所有的log record连同commit log record一起刷入磁盘，所以`logManager.flushToLSN(newLSN);`是必要的

- [x] end和abort的特殊性和一些细节点

这里的abort不是崩溃时的回滚，比如可能是运行中可能发生了不符合ACID中的C从而abort，实际的回滚代码是在end中进行的，注意rollback第二个参数是无法达到的，而根据proj的设计对一个transaction的log record不断进行prevLSN的递归终点是0即master record的地方，而不是transaction涉及的第一个log record，所以是自适应的
递归的过程中由于可能存在undolog，所以需要先判断是否存在undoNextLSN，没有再取prevLSN，这样可以防止同样的操作undo多次，以及需要注意undolog本身时isundoable是false
，undo只针对所有的update操作
### Task 2: Logging

- [x] 与lecture中不同点和一点点细节

除了commit end 以及abort以外还有一些update相关的操作，这里和lecture有所不同，`logAllocPart`, `logFreePart`, `logAllocPage`, `logFreePage`:这几个和proj自身的disk manager设计相关，据他文档说为了保证
> a consistent state after a crash.

大概就是为了确定data所在的page以及part吧，我们只需要实现lecture中提及的update operation相关的log write即可
注意为了减少io，不像之前的commit，这里无需flush
### Task 3: Savepoints
这个task实现用户主动abort回滚，很简单，直接调用之前的rollBackToLSN即可，没啥讲头
### Task 4: Checkpoints

- [x] checkpoints的作用是什么，什么是fuzzy checkpoint，怎么起作用的

若没有checkpoint，每次系统崩溃，重建transaction table和DPT都需要从第一条log record开始，很耽误事于是需要一些机制保存memory中transaction table和DPT的中间状态，这就是checkpoint，checkpoint分为begin checkpoint和end check point

- begin checkpoint用于记录开始写transaction table和DPT到log的时机
- end checkpoint用于记录写入完毕时的时机，同时在该log record中真正记录了transaction table和DPT的状态
- fuzzy checkpoint：由于开始写入到写入完毕是有时间差的，在这个过程中其他transaction 的进程可能同时在对transaction table 和DPT进行写入，所以end checkpoint记录的状态很可能是从begin到end某个中间状态，因此重建时需要从begin checkpoint开始重新走一遍
- [x] proj5中checkpoint和lecture中设计不同的地方
- lecture中可以直接取出end checkpoint中的状态然后再从begin走一遍，而proj5中是从begin开始从空白的table记录，然后最后再从end checkpoint中执行合并替换操作
- 为了一个endpoint不会占多个page，对含有较大内容的endpoint进行切分为多个
- [x] 一点点细节

checkpoint除了运行时调用以外，初始化和undo结束后都会调用，所以debug时候第一个运行时LSN会很小
endpoint产生以后一定要强制刷入磁盘不然check point就白做了
### Task 5: Analysis

- [x] 整体流程
1. 通过master record获取last checkpoint的LSN，从该begin checpoint开始往后遍历开始重建transaction table 和DPT，需要注意的是restart recovery刚开始拿到的trans table和DPT都是空白的
    1. 若遇到transnum相关的，更新transtable LSN，若没有该transacion需要通过recovery manager自带的匿名方法newTransaction来生成一个新的transaction对象
    1. page相关的需要分类讨论
        1. 对于该project 的设计_FREE_PAGE_和U_NDO_ALLOC_PAGE执行后立_即写入磁盘需要移除DPT
        1. UNDO_UPDATE和UPDATE_PAGE需要视情况写入DPT
        1. 其他情况无需修改，这部分其实我就没搞明白
    3. 关于transaction状态的变更
        1. 需要注意在recovery 过程中所有的abort都要变成abort_recovery来处理
        1. 遇到end需要移除transaction，基本和之前正常end操作一致，但是需要由于后面end points的fuzzy不确定性，为了排除插入已经end的transaction，需要提前记录end的transaction
    4. 关于endcheckpoint
        1. 通过强制类型转换得到ECLR从中取出两个table
        1. 由于checkpoint的特殊性，ECLR的DPT中的所有记录可以直接覆盖现有的dirtytable，因为recLSN只记录第一个，checkpoint必定是最早的
        1. transaction的状态的更新一方面lastLSN取大的，一方面status需要判断两者转换的可能性，我理解是是这样
```java
public static boolean possibleStatusChange(Transaction.Status beginStaus, Transaction.Status endStatus){
        switch (beginStaus){
            case RUNNING:
                return endStatus.equals(COMMITTING)||endStatus.equals(ABORTING)||endStatus.equals(RECOVERY_ABORTING);
            case ABORTING:
                return endStatus.equals(COMPLETE)||endStatus.equals(RECOVERY_ABORTING);
            case COMMITTING:
                return endStatus.equals(COMPLETE);
            case RECOVERY_ABORTING:
                return endStatus.equals(COMPLETE);
            default:
                return false;
            }
        }
```

2. 最后需要transaction中committing换为complete并进行end操作，因为根据持久性，commit后的transaction就要全部刷入磁盘了（通过后面的redo操作，其余running或是abort（这里因为可能从endcheck point直接插入的没有更改为recovery abort）全部改为recovery_abort
### Task 6: Redo

- [x] 哪些操作需要重写刷入磁盘

从DPT中找到最小的recLSN往后找update或是CLR record进行redo，复习一下几种情况下不用redo，

- DPT没有record相关page，必定已经刷入磁盘
- record的lSN<record相关page在DPT中的recLSN，那么也必定刷入磁盘
- 抓取该page持久化在磁盘中记录的pageLSN（反映最近更新该页的log record）>=logrecord的LSN，那么必然已经刷入过磁盘了

这个project自身的设计特点还需要redo 所有part相关操作以及alloc undo free(可能这种操作有幂等性？）
### Task 7: Undo

- [x] 怎么处理遇到的CLR record

为了防止对同一个操作进行undo两边，遇到CLR是迭代不是prevLSN而是nextundoLSN，需要注意的是undo本身的undorecord已经在redo阶段完成了，直接跳过到nextundo即可

- [x] 怎样实现从最大的lastLSN开始依次往前undo update的操作

首先用一个优先队列维护所有处于abort_recovery的transaction的lastLSN每次取出队列中最大的lastLSN，若该record是update相关，则undo，若是CLR，跳过直接插入nextundo，其他情况跳过插入prevLSN，依次类推直到队列为空为止

- [x] 怎么判断当前record是transaction的第一个record，undo结束呢

proj5的设计prevLSN为0时则表示此时是第一个，无需再插入队列中，并对该transaction执行end操作
