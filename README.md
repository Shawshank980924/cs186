# Project 3: Joins and Query Optimization
:::info
💡  第三个proj就主要实现各种join、sort算法和查询的优化，part1对应join、sort算法，part2对应查询优化

1. part1 分为三个task
   1. 理解Nested Loop Joins并实现BNLJ的算法
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
1. 对于1中产生的每个迭代器，使用sortRun进行内部排序得到都各自依次排序好的run list
1. 循环调用mergePass函数直到list中只有一个run
- [x] 关于mergePass函数的执行逻辑

mergePass函数将B-1个runs合并成一个，有一点技巧性，取出runs中每个每个run的迭代器，将record和run在runs中的index记录成一个Pair，将每个run对于的第一个Pair放入优先队列，取出最小的Pair，然后把该Pair的run对应迭代器的下一个Pair加入优先队列，重复以上操作实现merge

## Part-1: Task 4: Sort Merge Join
直接贴代码和注释，主要就是notes中的这三段话转成代码实现就可以了，但是我实现的很罗里吧嗦，却可以跑通
![image.png](https://cdn.nlark.com/yuque/0/2022/png/25488814/1656085800266-875d00f6-76a0-4091-9f91-729ceadad0c3.png#clientId=u3bc9fc29-919b-4&crop=0&crop=0&crop=1&crop=1&from=paste&height=456&id=uc00a4856&margin=%5Bobject%20Object%5D&name=image.png&originHeight=570&originWidth=1433&originalType=binary&ratio=1&rotation=0&showTitle=false&size=134570&status=done&style=none&taskId=udaff7116-d9bb-4ac3-8f7f-0918713b58c&title=&width=1146.4)
```java
private Record fetchNextRecord() {
            // TODO(proj3_part1): implement
            //left和right有一个为null直接返回null
            if(leftRecord == null||rightRecord == null){
                return null;
            }
            while(true){
                DataBox leftKey = leftRecord.getValue(getLeftColumnIndex());
                DataBox rightKey = rightRecord.getValue(getRightColumnIndex());
                //左右均有下一个的情况
                if(leftIterator.hasNext()&& rightIterator.hasNext()){
                    if(leftKey.compareTo(rightKey)==0){
                        //若对leftKey第一次找到rightKey达到相等，则需要标记rightIterator的位置
                        if(marked == false){
                            marked =true;
                            rightIterator.markPrev();
                        }
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        //只推进rightIterator
                        rightRecord = rightIterator.next();
                        return joinedRecord;
                    }
                    else{
                        //不相等的情况首先判断marked
                        if(marked == true){
                            //重置mark，right跳回标记处，left next
                            leftRecord = leftIterator.next();
                            rightIterator.reset();
                            rightRecord = rightIterator.next();
                            marked = false;
                            continue;
                        }
                        //左边比右边小，左边next
                        if(leftKey.compareTo(rightKey)<0){
                            leftRecord = leftIterator.next();
                        }
                        //右边比左边小，右边next
                        else if(leftKey.compareTo(rightKey)>0){
                            rightRecord = rightIterator.next();
                        }
                    }

                }
                else if(leftIterator.hasNext()){
                    //右边没有record的了，但是左边还有
                    if(leftKey.compareTo(rightKey)==0){
                        //因为右边没有record了，直接跳回标记处，left next
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        leftRecord = leftIterator.next();
                        rightIterator.reset();
                        rightRecord = rightIterator.next();
                        // rightRecord = rightIterator.next();
                        return joinedRecord;
                    }
                    else{
                        if(marked == true){
                            //这里和上面的情况相同相同
                            leftRecord = leftIterator.next();
                            rightIterator.reset();
                            rightRecord = rightIterator.next();
                            marked = false;
                            continue;
                        }
                        //左边比右边小，左边next
                        if(leftKey.compareTo(rightKey)<0){
                            leftRecord = leftIterator.next();
                        }
                        //右边比左边小，因为右边不能再推进了，直接返回null
                        else if(leftKey.compareTo(rightKey)>0){
                            // rightRecord = rightIterator.next();
                            rightRecord = null;
                            return null;
                        }
                    }
                }
                else if(rightIterator.hasNext()){
                    //右边还有，左边没了
                    if(leftKey.compareTo(rightKey)==0){
                        //同左右都有的情况
                        if(marked == false){
                            marked =true;
                            rightIterator.markPrev();
                        }
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        rightRecord = rightIterator.next();
                        return joinedRecord;
                    }
                    else{
                        //不相等时，left需要推进的情况全部返回null
                        if(marked == true){
                            marked = false;
                            leftRecord = null;
                            return null;
                        }
                        if(leftKey.compareTo(rightKey)<0){
                            leftRecord = null;
                            return null;
                        }
                        else if(leftKey.compareTo(rightKey)>0){
                            rightRecord = rightIterator.next();
                        }
                    }
                }
                else{
                    //left 和right均达到了最后一个元素
                    if(leftKey.compareTo(rightKey)==0){
                        if(marked == false){
                            marked =true;
                            rightIterator.markPrev();
                        }
                        Record joinedRecord = leftRecord.concat(rightRecord);
                        //由于right和left都不可能推进了，直接把right置为null，下次调用直接返null即可
                        rightRecord = null;
                        return joinedRecord;
                    }
                    else{
                        //不相等的情况下left和right至少一个要移动直接返回null
                        return null;
                    }
                }
            }
            
            

            
        }
```