# Project 3: Joins and Query Optimization
:::info
💡  第三个proj就主要实现各种join、sort算法和查询的优化，part1对应join、sort算法，part2对应查询优化

1. part1 分为四个task
   1. 理解Nested Loop Joins并实现BNLJ的算法
   1. 在SHJ的基础上完成GHJ算法
   1. 完成External Sort算法
   1. 实现一个没有优化版本的Sort Merge Join
2. part2 分为三个task
   1. table scan算法的选择
   1. left-deep plan中join算法的选择
   1. 将a和b组合成完整的Quert Optimize流程
      :::

## Part-1: Task 1 Nested Loop Joins

- [x] BNLJ的left block以及right page在放进内存后访问的顺序是怎样的

可以看这个proj的动画演示，应该是对于左block中每个record，去遍历page，page遍历结束换下一个page
全部的page遍历结束换下一个block
![](https://cdn.nlark.com/yuque/0/2022/gif/25488814/1655552796463-2e5fc7e7-f506-4a89-ad4c-830c6a95f579.gif#crop=0&crop=0&crop=1&crop=1&from=url&id=JhCQP&margin=%5Bobject%20Object%5D&originHeight=520&originWidth=408&originalType=binary&ratio=1&rotation=0&showTitle=false&status=done&style=none&title=)

- [x] IndexBacktrackingIterator这个回溯迭代器的作用是什么

根据题意：
> - Case 1: The right page iterator has a value to yield
> - Case 2: The right page iterator doesn't have a value to yield but the left block iterator does
> - Case 3: Neither the right page nor left block iterators have values to yield, but there's more right pages
> - Case 4: Neither right page nor left block iterators have values nor are there more right pages, but there are still left blocks

对于case2，left block iterator在next以后，right page iterator应该重置到该页的页首
对于case3，right page在fetch新的以后，left block iterator应当重置到block的第一个record
对于case4，left block iterator在fetch新的以后，right page iterator和rightsourceiterator都应该重置到第一个页页首和第一个record
这里的重置操作就是 这个迭代器的reset函数功能，应当在每次fetch新的时候通过marknext标记第一个迭代器的位置

- [x] 一些容易出现的bug
- leftrecord在case3手动重置left block iterator以后应该同时更新
- 对于case4忘记重置rightsourceiterator
## Part-1: Task 2: Hash Joins

- [x] 根据testSimpleSHJ对于SHJOperator的调用逻辑如下

![](https://cdn.nlark.com/yuque/0/2022/jpeg/25488814/1655951545125-91ffb1e5-a173-4ec6-ab4f-38b061473cea.jpeg)

- [x] 思维导图备注
1. 为所有的leftRecords创建空白分区，分区的数量=B-1
1. 根据hash函数将每个leftRecords分配到特定的分区中
1. bulid阶段，将partition[i]中的leftRecords根据key放进hashTable中，probe阶段，依次遍历rightRecords，根据key与相应key在hashTable存储的list中的各个leftRecords进行join加入到joinRecords中

对于GHJ而言的不同点在于，leftRecords可能需要多次分区直到各分区的Records的数量达到要求不大于B-2，与此同时rightRecords也需要同时分区，同时看buildAndProbe可以发现：
```java
if (leftPartition.getNumPages() <= this.numBuffers - 2) {
            buildRecords = leftPartition;
            buildColumnIndex = getLeftColumnIndex();
            probeRecords = rightPartition;
            probeColumnIndex = getRightColumnIndex();
            probeFirst = false;
        } else if (rightPartition.getNumPages() <= this.numBuffers - 2) {
            buildRecords = rightPartition;
            buildColumnIndex = getRightColumnIndex();
            probeRecords = leftPartition;
            probeColumnIndex = getLeftColumnIndex();
            probeFirst = true;
        } else {
            throw new IllegalArgumentException(
                "Neither the left nor the right records in this partition " +
                "fit in B-2 pages of memory."
            );
        }
```
对于GHJ来说对于每个partition来说是左边还是右边的作为bulid table是不确定的，需要若leftPages不大于B-2则build left，否则build rihgt

- [x] 不同的分区之间的在buildAndProbe时pass值可能不同

因为每个分区中record的数量不同，若有个分区left和right的page数量超过B-2就需要对于这两个分区的内的records递归调用run函数继续分区直到符合位置，但是其他分区可能已经符合要求了可以先行进入buildAndProbe阶段

- [x] 两个record相互调用concat的顺序有特定关系

A join B，那么必须是A.record.concat(B.record)，由于build hashTable不一定是left records，需要借助probeFirst来判断是谁调用concat函数

- [x] 关于breakSHJ和breakGHJ的test数据选取

breakSHJ的数据取的思路是考虑最坏的情况，根据题意left每个分区的records的个数不能超过32个，那么给left不同的32*（b-1）=160+个records SHJ必定爆掉，此时控制right records很少GHJ是可以保证GHJ不爆掉
而对于breakGHJ来说，若考虑最坏情况就不合适了需要3125*（B-1)+个records，这样test会跑的很慢，简单的思路就是在left和right中相同的值插入超过32个不管hashfunc怎么变，永远都有一个分区中的数据超过32个，即BreakGHJ
## Part-1:  Task 3: External Sort

- [x] 执行流程
1. 入口函数是sort()，借助`getBlockIterator`函数将所有records的迭代器分成N/B个迭代器
1. 对于1中产生的每个迭代器，使用`sortRun`进行内部排序得到都各自依次排序好的run list
1. 循环调用mergePass函数直到2中的list中只有一个run
- [x] 关于mergePass函数的执行逻辑

mergePass函数将B-1个runs合并成一个，有一点技巧性，取出runs中每个每个run的迭代器，将record和run在runs中的index记录成一个Pair，将每个run对于的第一个Pair放入优先队列，取出最小的Pair，然后把该Pair的run对应迭代器的下一个Pair加入优先队列，重复以上操作实现merge

## Part-1: Task 4: Sort Merge Join
直接贴代码和注释，主要就是notes中的这三段话转成代码实现就可以了，但是我实现的很罗里吧嗦，却可以跑通
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1656085800266-875d00f6-76a0-4091-9f91-729ceadad0c3.png#clientId=u3bc9fc29-919b-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=456&id=uc00a4856&margin=%5Bobject%20Object%5D&name=image.png&originHeight=570&originWidth=1433&originalType=binary&ratio=1&rotation=0&showTitle=false&size=134570&status=done&style=none&taskId=udaff7116-d9bb-4ac3-8f7f-0918713b58c&title=&width=1146.4)
```java
while(true){
                if(leftRecord==null)return null;
                //对于leftRecord还未出现相等的rightRecord
                if(!marked == true){
                    if(compare(leftRecord, rightRecord)>0){
                        if(!rightIterator.hasNext())return null;
                        rightRecord = rightIterator.next();
                    }
                    else if(compare(leftRecord, rightRecord)<0){
                        if(!leftIterator.hasNext())return null;
                        leftRecord = leftIterator.next();
                    }
                    else {
                        //对于leftRecord第一次出现相等的rightRecord
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        //若right后续还有record需要markPrev
                        if(rightIterator.hasNext()){
                            marked = true;
                            rightIterator.markPrev();
                            rightRecord = rightIterator.next();
                        }
                        //若right是最后一个，直接reset rightIterator然后推进leftRecord
                        else{
                            if(!leftIterator.hasNext())leftRecord = null;
                            else leftRecord = leftIterator.next();

                            rightIterator.reset();
                            rightRecord = rightIterator.next();
                        }
                        return joinedRecord;
                    }
                }
                //对于leftRecord已经遇到过相等的rightRecord时
                else{
                    if(compare(leftRecord, rightRecord)==0){
                        //对于leftRecord第一次出现相等的rightRecord
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        //right不是最后一个
                        if(rightIterator.hasNext())rightRecord = rightIterator.next();
                        //right是最后一个，right reset ，left next
                        else if(leftIterator.hasNext()){
                            marked = false;
                            leftRecord = leftIterator.next();
                            rightIterator.reset();
                            rightRecord = rightIterator.next();
                        }
                        else{
                            leftRecord = null;
                        }
                        return joinedRecord;
                    }
                    //不相等的时候
                    else{
                        if(leftIterator.hasNext()){
                            marked = false;
                            leftRecord = leftIterator.next();
                            rightIterator.reset();
                            rightRecord = rightIterator.next();
                        }
                        else leftRecord = null;
                    }

                }
            }
```
## Part-2：Task 5: Single Table Access Selection (Pass 1)

- [x] single Table Access具体在做什么

这部分是所有query plan的第一个步骤，根据特定的table得到遍历io cost最少的scan方式，scan的方式分为两种，一种是默认的sequential scan，另一种是该table若存在索引且该建立的索引在select predicate中存在那么可以采用在索引中查找，可用的索引是通过`getEligibleIndexColumns`得到的。通过调用`estimateIOCost()`比较返回最小io cost的queryOperator

- [x] 索引table的index 对应的select predicate判断条件需要删去

因为索引搜索的时候已经完成了该判断条件，所以不需要重复进行筛选该判断条件，其余条件在single table access的最后全部push down，所谓的pushdown就是在scan生成的QueryOperator的基础上重新套上一层层的select Operator即可
## Part-2：Task 6: Join Selection (Pass i > 1)

- [x] `minCostJoins`具体在做什么

理解以下这段话是关键：
> Recall that for i > 1, pass i of the dynamic programming algorithm takes in optimal plans for joining together all possible sets of i - 1 tables (except those involving cartesian products), and returns optimal plans for joining together all possible sets of i tables (again excluding those with cartesian products).

![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1656743652767-3d8c9690-60b8-4a40-aa6f-b17d94e33fb9.png#clientId=ud73ee789-1509-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=199&id=uae35dd4f&margin=%5Bobject%20Object%5D&name=image.png&originHeight=249&originWidth=302&originalType=binary&ratio=1&rotation=0&showTitle=false&size=2993&status=done&style=none&taskId=u5edeb8d6-442f-4707-879b-206a1300d27&title=&width=241.6)
pass1做的就是ABCD四个table的scan+pushdown select predicate，返回相应的QueryOperator，对应传入参数中的Map<Set<String>, QueryOperator> pass1Map，这里的set里面只有一个table name，而对于pass i，i>1来说，需要做的就是将前一个pass产生的i-1个table join或者scan的结果作为输入prevMap，找到一个额外的base table，对于这个额外的table找到和他join的最小io cost，以pass 2 为例对于上图中的从下往上第一层join，prevMap就是pass1Map，找到ABCD各自两两join io cost最小的情况，注意不考虑没有join condition的叉积以及对于join方法观察`minCostJoinType`其实只有BNLJ和SNLJ两种可能性

- [x] Join predicate不能像之前的select predicate一样用过就直接删除

因为一个join predicate在不同的query plan中可能会同时用，在产生不同的query plan时join predicate需要保留，但是为了防止同一个join predicate被同一个上下游的query plan 同时使用，需要加上额外的判断，preMap的set中不能同时出现join的leftTable和rightTable也就是代码注释中but后面的原因
> Case 1: The set contains left table** but not right,** use pass1Map
//              to fetch an operator to access the rightTable
//      Case 2: The set contains right table **but not left,** use pass1Map
//              to fetch an operator to access the leftTable.

- [x] for each遍历set的时候不要改动本身的set

当使用HashSet的keySet方法时返回的是HashMap内部类`final class KeySet extends AbstractSet<K>`，可以看到本身继承的是AbstractSet，它又是同时继承AbstractCollection需要注意的是继承的这个类中的add remove方法都是直接抛出错误`throw new UnsupportedOperationException();`的，而该内部类未重写add方法所以取出来的keySet是不能增加的，若要修改必须重新复刻一个。
养成良好的习惯，遍历同时删除或者修改会产生奇怪的迭代器问题，建议重新需要修改set建议重新new一个新的复刻原来的set，题外话若是边遍历边删除，使用iterator.remove()才是安全的操作

- [x] 只考虑left-deep query plan！

由于left-deep情况下（不考虑sort-merge）可以实现完全的pipeline，因为如果右侧的table存在join的话必须要写temp file用于储存全部的join result page（可以回想一下下BNLJ算法里面右侧的迭代器是要rightSource Iterator.reset重新初始化的，而左侧的迭代器只是重置单个block内的pages iterator 即可，这样一来left-deep保证右侧永远是baseTable，免去了写Temp file所带来的IO消耗
在case2中join predicate.rightTable在prevMap中时，需要将joinOp的左右掉个个，从A join B改为 B join A这样才能做到这仍然是个left-deep query

## Part-2：Task 7: Optimal Plan Selection

- [x] excute的具体在做什么

主要干两件事，首先调用single table access生成pass1Map，然后复刻成passMap，当passMap中query plan有多个时说明还有base Table可以join，重复上一个Task的join

- [x] 别忘最后赋给finalOperator 并且套上GroupBy以及Project等Operator就完事了