:::info
💡  第二个proj就正式开始实现数据库管理系统啦！
这个proj分为4 个task，分别实现

1. 完成leafNode和innerNode构造器中的关键函数fromBytes
1. 实现b+tree的增删查操作
1. 实现b+tree的全遍历和range遍历
1. 实现b+tree的bulk load，即批量导入构造b+tree
   :::

## Hints
> 做之前一定要仔细看代码的注释，尤其是BPlusNode里面的抽象类前的注释，以及项目文档里的图片帮助理解代码
> Debug可以用tree.toDotPDFFile()需要预先配置[GraphViz](https://graphviz.gitlab.io/download/)环境，打印出b+tree的整体结构


![](https://cdn.nlark.com/yuque/0/2022/png/25488814/1654440038516-cd2cf57c-f01a-498b-9630-143bc31cc169.png?x-oss-process=image%2Fresize%2Cw_1038%2Climit_0#crop=0&crop=0&crop=1&crop=1&from=url&id=bfTiU&margin=%5Bobject%20Object%5D&originHeight=745&originWidth=1038&originalType=binary&ratio=1&rotation=0&showTitle=false&status=done&style=none&title=)
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1654704947398-b13a652c-d139-4193-a360-f8872a6cb8c3.png#clientId=u4e563b72-0e1f-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=977&id=ub2bdb52f&margin=%5Bobject%20Object%5D&name=image.png&originHeight=977&originWidth=1918&originalType=binary&ratio=1&rotation=0&showTitle=false&size=80768&status=done&style=none&taskId=u14d73552-d52b-44f1-a72a-085d45eaeb7&title=&width=1918)

## Task 1 LeafNode::fromBytes

- [x] fromBytes函数具体在做什么

根据pageNum将每个buffer中储存的每个page（每个page对应一个leafNode或者一个innerNode）中的字节数据反序列化提取成具体的数据结构，最终返回一个leafNode或者inner节点

- [x] rightSibling和pageNum不要搞混
> leafNode的一个page中的依次包括以下字节数据
> // When we serialize a leaf node, we write:
//
//   a. the literal value 1 (1 byte) which indicates that this node is a
//      leaf node,
//   b. the page id (8 bytes) of our right sibling (or -1 if we don't have
//      a right sibling),
//   c. the number (4 bytes) of (key, rid) pairs this leaf node contains,
//      and
//   d. the (key, rid) pairs themselves.

page的字节数据存入buffer中后反序列化b得到的是pageNum，pageNum=-1表示右兄弟节点不存在，但是若右兄弟不存在，leafNode.rightSibling = Optional.empty()不是-1

- [x] inner和leafNode的fromByte具体实现不同

leafNode中key和recordId是交替出现的，inner是先keys然后chilren
## Task 2 get, getLeftmostLeaf, put, remove

- [x] 插入、查找、删除的实现思路

这三个实现的思路都差不多，利用了java的动态代理，b+tree的root节点为编译类型是BPlusNode，它的实现类innerNode和leafNode通过重写相同的方法，innerNode递归调用childrenNode的相同方法，而leafNode作为递归调用的出口

- [x] 插入时带来的分裂情况需要小心使用sublist方法

在使用ArrayList.sublist取出leafNode或者innerNode节点分裂后新page上的key和rid/children需要注意，sublist的元素是原有的list内对象的引用，所以若对之前的list执行remove操作会同时导致sublist中的引用变为空引用，报空指针异常，解决方法是取sublist的同时new：`List<DataBox> newKeys = new ArrayList<>(keys.subList(d,2*d+1))`

- [x] 注意在leaf节点和inner节点分裂时，split-key 操作不同
1. leafNode中split-key保留， copy upwards
1. innerNode中split-key不保留，move upwards
- [x] remove和put的特殊点

这两个属于mutating operation，执行完毕后需要将改动的node同步写入磁盘
remove操作结束后不需要对b+tree进行rebalance操作

- [x] get和put方法的中查找位置的优化

get方法在inner节点需要找到key对应的chilren指针，代码框架提供了numLessThan和numLessThanEqual，但是用二分法查找可以提高查找效率
## Task 3 Scans

- [x] scanAll和scanGreaterEqual两个方法返回的遍历迭代器必须以lazy的方式对磁盘中的page进行io读入

根据注释，test中会检查io次数，若在构造迭代器时全遍历了leafNode会带来大量的io消耗，应该在重写迭代器的hasnext中进行判断读入新的leafNode所在的page

- [x] scanGreaterEqual需要通过get先找到key所在的leafNode

若直接get leftMostLeaf然后依次迭代也会带来较大的io消耗
## Task 4: Bulk Load

- [x] bulk load的流程和与repeatly insert相比的优势

流程可以参考以下的ppt链接
[https://docs.google.com/presentation/d/1_ghdp60NV6XRHnutFAL20k2no6tr2PosXGokYtR8WwU/edit#slide=id.g93b02f7d9b_1_415](https://docs.google.com/presentation/d/1_ghdp60NV6XRHnutFAL20k2no6tr2PosXGokYtR8WwU/edit#slide=id.g93b02f7d9b_1_415)
简单概括一下就是从左往右，从下到上的顺序依次插入，并且对leafNode给定fullfactor，这样做的好处在于：

1. 由于数据插入是有序的，所以插入时不需要像task2中put函数需要查找插入的位置，必定是rightMostNode
1. 一旦分裂之后，分裂之前的结点就不会再次访问，减少了内存反复换页带来的io消耗
1. 对每个leafNode预留了空位，为之后的插入预留了空间，防止太多的分裂操作影响插入效率
- [x] fullfactor的一些注意点

fullfactor与2d的乘积向上取整，并且只对leafNode有效，innerNode依然以2d+1作为分裂的判定点



## 