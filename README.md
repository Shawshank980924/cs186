# Project 4: Concurrency
:::info
💡  第四个proj就主要实现并行查询和修改过程中多粒度锁的使用和释放，从lockManger底层开始往上LockContext再到LockUtil完成一层又一层的封装，该project主要分为两个部分

1. part1 Queuing 分为两个个task
   1. 主要理解IS IX SIX S X这几个锁之间的相互兼容和替代关系
   1. 实现最底层几个锁的获取释放方法，并实现queuing logic并学会运用synchronized实现原子性和有序性代码
2. part2 分为三个task
   1. lockContext封装LockManger的方法并执行多粒度锁限制条件的检查
   1. 封装LockContext的方法并自动判断和调整ancestors节点和当前层节点锁状态
   1. 实现strict 2PL
      :::
## Part-1：Task 1 LockType

- [x] project中涉及的几种锁的特性

感觉这几个锁定义的挺模糊的，做project的时候有时候概念不太清楚，我也是凭着自己的理解写一下：

- S(A) 某个transaction对A加S锁就是可以读A以及A以下的资源，比如A是某个table，那么就能读该table下所有的page，不同的tranction可以对同一个A同时持有S锁，
- X(A) 某个transaction对A加S锁就是可以读以及写A以及A以下的资源，因为加了写的权限所以A不能再被其他transaction持有X或者S锁
- IS(A) 这个表示A以下有资源被该transaction上了S锁（但不一定是全部资源），注意IS下只能存在S或者IS锁，不可能存在X锁
- IX(A) 这个表示A以下有资源被该transaction上了X锁（但不一定是全部资源），注意IX下也可以存在IS或者S锁，所以说IX下可以获取任意种类的锁
- SIX(A) 这个锁可以看作一个transaction对一个资源同时获取了S和IX锁，这是啥意思呢，就是A下的资源除了需要写入的资源获取X锁以外其余均获取S锁（当然还会有IX锁），假设A是database画张图理解以下就是：

![](https://cdn.nlark.com/yuque/0/2022/jpeg/25488814/1658029090874-42ef66de-3586-4e2c-9746-fd11cadbfaed.jpeg)

- 需要注意的是为了简洁性，SIX下不允许出现IS或者S或者SIX，以上说的显式的带锁，图中加粗的部分的S是隐式的带锁，所谓隐式的带锁是指显式不带锁，锁来源于自己的ancestors，这里也就是SIX，也和之后实现的effectiveLockType和explicitLockType函数有点相关，此处先按下不表
- 若一个资源带了S或者X锁，其下的所有资源不能显式的带有其他锁
> 显式和隐式的区别和联系可以参考ppt上这句话explicitly就是显式带锁，implicitly就是隐式带锁
> •When a transaction locks a node in the tree **explicitly**, it **implicitly** locks all the node’s descendants in the same mode.

- [x] Intent Lock即上面带I的锁到底有什么用

首先需要知道ILock加的原则是什么，可以参考LockType.parentLock中的代码，当一个子节点获取X时它的ancestors节点至少要获取IX锁，子节点获取S时它的所有ancestors节点至少要获取IS锁
带I的锁主要对其他transaction提前判断是否有权限去读或者写如一个table，而无需遍历该table下所有的page，若一个table下有page有S锁，table上至少有个IS锁，这样若其他transaction要对table加X锁通过判断table上有IS就知道无法获取table的X
父节点上的Intent Lock对应的是粗粒度锁（Coarse granularity ），子节点加的S或者X Lock对应的细粒度锁(Fine granularity)，粗细粒度锁各有优缺点，参考ppt
> •Fine granularity (lower in tree):  High concurrency, lots of locks (overhead)
> •Coarse granularity (higher in tree): Few locks (low overhead), lost concurrency

- [x] 单个tranction对单个resource只能同时获取一个锁

例如对某个table，transaction只能最多对其持有上述的一个锁，若显式带锁那么持有的就是显式带的锁，若显式不带锁再看隐式带的锁，虽然不能同时持有但是锁与锁之间有等级替换关系比如X可以替换S

- [x] 锁的兼容性compatibility以及canbeparent和substitute怎么看
- 锁的兼容性指的是不同的transaction对同一个资源是否可以同时各自持有某锁（这个锁可以不同）对于Ilock判断标准就是只要存在某种情况下是符合的就行比如IS和SIX只要IS加的不是X的资源即可，详见下图
```java
/**
     * Compatibility Matrix
     * (Boolean value in cell answers is `left` compatible with `top`?)
     *
     *     | NL  | IS  | IX  |  S  | SIX |  X
     * ----+-----+-----+-----+-----+-----+-----
     * NL  |  T  |  T  |  T  |  T  |  T  |  T
     * ----+-----+-----+-----+-----+-----+-----
     * IS  |  T  |  T  |  T  |  T  |  t  |  f
     * ----+-----+-----+-----+-----+-----+-----
     * IX  |  T  |  T  |  T  |  F  |  f  |  f
     * ----+-----+-----+-----+-----+-----+-----
     * S   |  T  |  T  |  F  |  T  |  F  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * SIX |  T  |  t  |  f  |  F  |  f  |  f
     * ----+-----+-----+-----+-----+-----+-----
     * X   |  T  |  f  |  f  |  F  |  f  |  F
     * ----+-----+-----+-----+-----+-----+-----
     *
*/
```

- 锁的canBeparent判断用于同一个transaction中对于上下两层的资源加的锁是否适配，这个方法主要用于之后升级锁时判断对于父节点是否需要改动，注意这里指的都是显式带锁

具体见下表，判断方法可以看代码文档对于各种锁的说明
```java
/**
     * Parent Matrix
     * (Boolean value in cell answers can `left` be the parent of `top`?)
     *
     *     | NL  | IS  | IX  |  S  | SIX |  X
     * ----+-----+-----+-----+-----+-----+-----
     * NL  |  T  |  F  |  F  |  F  |  F  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * IS  |  T  |  T  |  F  |  T  |  F  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * IX  |  T  |  T  |  T  |  T  |  T  |  T
     * ----+-----+-----+-----+-----+-----+-----
     * S   |  T  |  f  |  f  |  f  |  f  |  f
     * ----+-----+-----+-----+-----+-----+-----
     * SIX |  T  |  f  |  t  |  f  |  f  |  t
     * ----+-----+-----+-----+-----+-----+-----
     * X   |  T  |  f  |  f  |  f  |  f  |  f
     * ----+-----+-----+-----+-----+-----+-----
     *
     */
```

- 锁的可替换性就是指用高等级的锁去替换低等级的锁，在原来的基础上锁更强了就能替换，或者说原来的操作在新的锁以下都能做就表示可以替换，具体见下表左边的替换上边
```java
  /**
     * Substitutability Matrix
     * (Values along left are `substitute`, values along top are `required`)
     *
     *     | NL  | IS  | IX  |  S  | SIX |  X
     * ----+-----+-----+-----+-----+-----+-----
     * NL  |  T  |  F  |  F  |  F  |  F  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * IS  |  T  |  T  |  F  |  F  |  f  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * IX  |  T  |  T  |  T  |  F  |  F  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * S   |  T  |  t  |  f  |  T  |  f  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * SIX |  T  |  t  |  t  |  T  |  T  |  F
     * ----+-----+-----+-----+-----+-----+-----
     * X   |  T  |  t  |  t  |  T  |  t  |  T
     * ----+-----+-----+-----+-----+-----+-----
     *
```

- [x] checkCompatible中的except

except是一种特殊情况，一般来说兼容性检查应该对于不同的transaciton，由于task2中的acquireAndRelease可能release和acquire中存在对相同transaction对于相同资源的锁以及promote函数调用，为了辅助判断这个，传入参数加入transaction作为except，相同transaction只要可替换就也能算兼容的一种特殊情况
## Part-1：Task 2 LockManager

- [x] Lockmanager的具体作用

全局所有的transaction共用一个LockManager实例，LockManager管理着每个transaction持有的锁，以及维护着对于每个resource，持有该resource的所有的锁以及队列中等待持有的锁，对于等待持有的锁所在的transaction处于被挂起阻塞block的状态，可以借用一张Discussion中的表来表示LockManager中的包含的信息
![](https://cdn.nlark.com/yuque/0/2022/png/25488814/1657246760405-e5b2c9ff-ff22-4586-908a-0308b78032dd.png#crop=0&crop=0&crop=1&crop=1&from=url&id=mgwhs&margin=%5Bobject%20Object%5D&originHeight=280&originWidth=639&originalType=binary&ratio=1&rotation=0&showTitle=false&status=done&style=none&title=)

- [x] 关于各个方法的调用逻辑关系以及实现的一些细节问题

这里的锁相关的调用方法非常多，文档描述的也比较模糊，调用逻辑以及各个函数的作用容易混淆，可以这样来协助分析：acquireAndRelease acquire  release以及promote三个方法中带了同步关键字synchronized，说明这是调用的入口方法，其他不带该关键字的应该被以上三个方法来调用
三个

1. acquireAndRelease：
   1. 若原来该transaction对该资源就持有锁，需要判断是否在releaseNames中是否存在自身，若不存在才需要抛出错误
   1. 为了代码的健壮性，checcompatibility以后具体执行的时候应该先释放锁然后再获取锁，因为若先获取可能出现原资源已有锁且不可替换的情况
   1. releaseNames上的锁必须全部存在
2. acquire
   1. 只有队列为空且与当前该资源持有的锁兼容时才能直接获取锁，否则需要再队列中追加
   1. 与前者不同，不允许锁的升级，因为grantUpdate中有锁替换的逻辑，所以doubleLock的判断需要在acquire就完成
3. release
   1. 这里有个坑点，观察getLock函数`return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),Collections._emptyList_()));`返回的不是一个transactionLocks Map value的一个引用还是复刻了一个ArrayList，所以删除不能用这个方法来获取该transaction持有的所有锁，而是直接调用transactionLocks.get
   1. release有两个地方的锁需要释放，一个是在transactionLocks中维护的该transaction对该resource持有的锁，这个应该在release母函数中完成，另一个是在ResourceEntry中维护该资源的持有锁中删除该transaction对应的锁，对应的是`ResourceEntry.releaseLock`方法
   1. 这里需要边遍历边删除必须使用迭代器才能安全删除即`iterator.remove`
   1. 释放一个锁以后需要调用`processQueue`因为持有锁变化后可能可以推进队列，获取新的锁，注意`processQueue`中应该是个循环判断逻辑获取锁直到无法继续获取为止
4. promote
   1. NL不可promote，同一个transaction promote的锁与之前相同也不可，然后再判断是否兼容，注意不可替换的判断应该在promote中判断，grantUpdate是直接替换的
## Part-2：Task 3: LockContext
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1658037875816-48ed0f2d-65b6-44df-a79d-675af5551743.png#clientId=ua1996f64-888b-4&crop=0&crop=0&crop=1&crop=1&from=paste&id=u3a159407&margin=%5Bobject%20Object%5D&name=image.png&originHeight=456&originWidth=986&originalType=url&ratio=1&rotation=0&showTitle=false&size=81408&status=done&style=none&taskId=ud1e07272-9990-43a8-b770-ff7c4b3c2a7&title=)
根据proj4的架构图，在实现lockManager以后，实现了对于不同资源和不同transaction持有锁以及queuing logic的维护，上一层是LockContext，在这一层里通过locktext对象管理不同级别的resource，这一层级的锁的获取和释放不仅仅是调用lockManager，而且还会检查和更新ancestors和Descendants持有锁的状态确保符合多粒度锁限制条件Multigranularity constrains，也就是canBeparent等的限制条件

- [x] `getExplicitLockType`和`getEffectiveLockType`的区别

getExplicitLockType用于得到某transaction在该资源下显式带的锁即`this.lockman.getLockType`的方法结果
`getEffectiveLockType`首先判断显式带的锁，若显式带锁，直接返回显式锁；若显式不带锁，找隐式带的锁，需要往上层递归调用本函数，返回的结果若是S或者是X直接返回该结果，若是IX，需要判断ancestors中是否存在SIX锁，若存在返回S，若不存在返回NL；其余情况均返回NL

- [x] 几个实现函数中的细节和易错部分

几个函数的实现流程基本内容主要分为检查Multigranularity constrains和调用lockManager的相关函数

1. acquire
   1. 注意是否有锁抛错应该用的是effectiveLock
   1. 除了通过canBeParent判断父节点的合法性以外需要更新父节点的lockcontext中维护的子节点的锁的数量
2. release
   1. 通过numchildren来判断子节点中是否持有显式锁，注意numchildren存储的是显式锁数量，隐式锁不计入，若持有抛错
   1. 同上记得更新numchildren
3. promote
   1. 由于SIX在代码文档中特别说明，SIX下只能存在IX X两种显式锁，所以在提升为SIX之前需要释放descendants中所有的S 和IS锁（**其实我觉得应该还要释放SIX锁**），以及还要保证该点以上不能存在SIX节点
   1. 在SIX情况下由于应该只能调用lockManger一次实现原子性，中间状态不可见，一次性的释放多个锁应该调用acquireAndRelease函数，其余情况直接调用lockManager.promote即可
   1. 注意释放锁中除了自己以外其他锁的父节点的numchildren都应该改变
4. escalate
   1. 这个函数作用就是回收所有下层资源的细粒度锁，以最小代价转为该层的粗粒度锁（限制为S或者X）但我觉得它文档描述和测试文件中略有不符，按文档理解应该遍历所有的子节点判断是否存在带X锁的资源节点，若没有则该层转化为S，若有则该层转化为X，但从`testEscalateIXX`来看只是从该层现在带的锁来进行判断，若该层带S或者X，表明无需处理直接返回；若该层带IX或者SIX，转化为X；其余情况均转化为S锁
   1. 3.b中相同需要使用acquireAndRelease方法实现只调用一次lockManager
   1. `LockContext._fromResourceName_`_静态方法可以帮助获取下层的lockContext_
## Part-2：Task 4: LockUtil
该层位于架构图的最上层，主要向用户封装lockContext锁的释放和使用，实现两个方面的作用

1. 判断当前锁的状态是否已经满足request了例如A原来已经加了X，对于同一个transaction向对A获取S，已经满足条件直接返回即可，用户不知道具体获取的锁但是已经满足了条件
1. 不满足，需要调整锁时，先调整ancestors节点的状态使其满足要求然后再调用lockContext的相关方法获取该层的锁
- [x] 怎么调整ancestors节点获取锁的状态

实现一个自定义的`ancestorEnsuring`函数，通过LockType.parentLock获取父节点至少要获取的锁类型，然后判断当前父节点是否已经覆盖了该等级的锁，若已经覆盖了直接返回；若未覆盖，递归调用本函数后先调整其父节点然后再调用LockContext的函数调整该层节点的锁，这很关键，因为调用lockContext前首先要保证父节点锁是满足多粒度锁约束的

- [x] 怎么根据request中锁以及现有的状态来调整锁呢

参考代码注释中的几种情况来实现代码
> _* - The current lock type can effectively substitute the requested type
* - The current lock type is IX and the requested lock is S
* - The current lock type is an intent lock
* - None of the above: In this case, consider what values the explicit
*   lock type can be, and think about how ancestor locks will need to be
*   acquired or changed._

1. 当前层的锁等级大于等于request，这种情况下可以直接返回
1. 当前带的锁是IX，request是S，这种情况下应该promote为SIX锁
1. 当前的锁是intent lock，这种情况需要对request进行分类讨论详细分析一下：
   1. 若request是X，那么X可以替换所有的I锁
   1. 若request是S，那么S可以替换除了SIX和IX以外所有的I锁，而IX和SIX都包含在了以上两种情况下了（IX是2，SIX是1），在这种情况下无论request是S还是X都可以直接替换掉
   1. 具体流程就是先ancestorEnsuring，然后在该层escalate，最后判断该层是否满足request，若不满足再进行promote
4. 剩下的情况是当前层effectiveLock不能覆盖request，而且当前持有的不是intent lock，只能是S或者NL
   1. effectiveLock是NL，直接acquire
   1. effectiveLock是S，这种情况下，S可能来源于自身带的锁，可能是来源于隐式锁
      1. 若explicitLock是S，ancestorEnsuring后该层直接promote为request即可
      1. 若explicitLock是NL，隐式锁可能来源于上层的S或者SIX，那么等价于改变上层的锁，递归调用本函数`_ensureSufficientLockHeld_(parentContext,requestType);`_即可_
## Part-2：Task 5: Two-Phase Locking

- [x] 关于什么是2PL，以及2PL和strict 2PL之间的区别

这两种都是避免死锁的方式，2PL就是在同一个traction中在锁的获取过程中不能释放之前获取的锁，只有当所有的锁都获取完毕以后才能释放锁，所以2PL整个流程中持有锁的示意图如下
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1658043892670-05d78de1-225b-445a-8942-89bf090abe9b.png#clientId=ua1996f64-888b-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=320&id=u8b5c08fb&margin=%5Bobject%20Object%5D&name=image.png&originHeight=400&originWidth=876&originalType=binary&ratio=1&rotation=0&showTitle=false&size=12450&status=done&style=none&taskId=uda6204f0-5896-44b8-bf1d-f5a96d58d99&title=&width=700.8)
但是2PL有一个问题，一旦释放过程中该transaction奔溃了需要回滚，但是其他transaction已经获取了部分释放的锁，会产生脏读的问题，所以提出了strict 2PL的概念，所有的锁必须在traction结束时统一释放，示意图如下
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1658044300420-24f10537-92b1-4974-b1b9-b9c3916c34e2.png#clientId=ua1996f64-888b-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=310&id=ufe112595&margin=%5Bobject%20Object%5D&name=image.png&originHeight=388&originWidth=847&originalType=binary&ratio=1&rotation=0&showTitle=false&size=17107&status=done&style=none&taskId=ua7be8da2-f791-4e4b-ae96-90e956a657f&title=&width=677.6)
这个project采用的是strict 2PL，所以所有持有的锁应该在transaction结束的时候进行释放，具体流程参照注释完成即可，不难

- [x] 关于释放锁的顺序和实现

由于调用lockContext的release方法会首先检查子节点的锁是否释放完毕，所以锁的释放顺序必须是从下到上，实现的思路类似BFS的层序遍历，每一次取出numchildren为0 的locks，在释放的同时更新父节点的numChildren直到list为空，所有的锁均释放完毕
## Testing
需要写一个关于IX promote为_SIX的测试代码练练手_
```java
 @Test
public void testPromoteSIXSaturation() {
        // your test code here
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1,LockType.IX);
        tableLockContext.acquire(t1,LockType.IS);
        pageLockContext.acquire(t1,LockType.S);
        dbLockContext.promote(t1,LockType.SIX);
        assertEquals(0,dbLockContext.getNumChildren(t1));

        }
```