package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.table.Record;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Performs an equijoin between two relations on leftColumnName and
 * rightColumnName respectively using the Block Nested Loop Join algorithm.
 */
public class BNLJOperator extends JoinOperator {
    protected int numBuffers;

    public BNLJOperator(QueryOperator leftSource,
                        QueryOperator rightSource,
                        String leftColumnName,
                        String rightColumnName,
                        TransactionContext transaction) {
        super(leftSource, materialize(rightSource, transaction),
                leftColumnName, rightColumnName, transaction, JoinType.BNLJ
        );
        this.numBuffers = transaction.getWorkMemSize();
        this.stats = this.estimateStats();
    }

    @Override
    public Iterator<Record> iterator() {
        return new BNLJIterator();
    }

    @Override
    public int estimateIOCost() {
        //This method implements the IO cost estimation of the Block Nested Loop Join
        int usableBuffers = numBuffers - 2;
        int numLeftPages = getLeftSource().estimateStats().getNumPages();
        int numRightPages = getRightSource().estimateIOCost();
        return ((int) Math.ceil((double) numLeftPages / (double) usableBuffers)) * numRightPages +
               getLeftSource().estimateIOCost();
    }

    /**
     * A record iterator that executes the logic for a simple nested loop join.
     * Look over the implementation in SNLJOperator if you want to get a feel
     * for the fetchNextRecord() logic.
     */
    private class BNLJIterator implements Iterator<Record>{
        // Iterator over all the records of the left source
        private Iterator<Record> leftSourceIterator;
        // Iterator over all the records of the right source
        private BacktrackingIterator<Record> rightSourceIterator;
        // Iterator over records in the current block of left pages
        private BacktrackingIterator<Record> leftBlockIterator;
        // Iterator over records in the current right page
        private BacktrackingIterator<Record> rightPageIterator;
        // The current record from the left relation
        private Record leftRecord;
        // The next record to return
        private Record nextRecord;

        private BNLJIterator() {
            super();
            this.leftSourceIterator = getLeftSource().iterator();
            this.fetchNextLeftBlock();

            this.rightSourceIterator = getRightSource().backtrackingIterator();
            this.rightSourceIterator.markNext();
            this.fetchNextRightPage();

            this.nextRecord = null;
        }

        /**
         * Fetch the next block of records from the left source.
         * leftBlockIterator should be set to a backtracking iterator over up to
         * B-2 pages of records from the left source, and leftRecord should be
         * set to the first record in this block.
         *
         * If there are no more records in the left source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextLeftBlock() {
            // TODO(proj3_part1): implement
            //从磁盘中取出B-2个page作为一个block放在内存页中，并通过QueryOperator#getBlockIterator返回该block的一个迭代器

            //调用QueryOperator的静态方法 赋给leftBlockIterator,注意最大的page页数b-2
            this.leftBlockIterator =  QueryOperator.getBlockIterator(this.leftSourceIterator, BNLJOperator.this.getLeftSource().getSchema(),numBuffers-2 );
            this.leftBlockIterator.markNext();
            //leftRecord 置为第一个record
            if(this.leftBlockIterator.hasNext())this.leftRecord = this.leftBlockIterator.next();
            else this.leftRecord=null;
            // this.leftRecord = this.leftBlockIterator.next();
            
        }

        /**
         * Fetch the next page of records from the right source.
         * rightPageIterator should be set to a backtracking iterator over up to
         * one page of records from the right source.
         *
         * If there are no more records in the right source, this method should
         * do nothing.
         *
         * You may find QueryOperator#getBlockIterator useful here.
         */
        private void fetchNextRightPage() {
            // TODO(proj3_part1): implement
            //前一个的特殊情况把maxpages改为1即可
            this.rightPageIterator = QueryOperator.getBlockIterator(this.rightSourceIterator, BNLJOperator.this.getRightSource().getSchema(),1);
            this.rightPageIterator.markNext();
        }

        /**
         * Returns the next record that should be yielded from this join,
         * or null if there are no more records to join.
         *
         * You may find JoinOperator#compare useful here. (You can call compare
         * function directly from this file, since BNLJOperator is a subclass
         * of JoinOperator).
         */
        private Record fetchNextRecord() {
            // TODO(proj3_part1): implement
            //把proj的注释copy过来，提示有四种情况需要处理
            // Case 1: The right page iterator has a value to yield
            // Case 2: The right page iterator doesn't have a value to yield but the left block iterator does
            // Case 3: Neither the right page nor left block iterators have values to yield, but there's more right pages
            // Case 4: Neither right page nor left block iterators have values nor are there more right pages, but there are still left blocks
            // if (leftRecord == null) {
            //     // The left source was empty, nothing to fetch
            //     return null;
            // }
            while(true){
                //第一种情况，一个page内的record还没遍历完
                if(this.rightPageIterator.hasNext()){
                    Record rightRecord = rightPageIterator.next();
                    if (compare(leftRecord, rightRecord) == 0) {
                        return leftRecord.concat(rightRecord);
                    }

                }
                //第二种情况，一个page遍历完了，但是左边block还没遍历完
                //左边的block next，右边的page reset重头遍历
                else if(this.leftBlockIterator.hasNext()){
                    leftRecord = leftBlockIterator.next();
                    rightPageIterator.reset();
                    continue;

                }
                
                else{
                    //第三种情况，block里面的每个record都访问了右边的page中的所有record，右边还能fetchNextPage
                    //重置左边block的迭代器，直接进入下一个循环
                    fetchNextRightPage();
                    if(this.rightPageIterator.hasNext()){
                        leftBlockIterator.reset();
                        //leftRecord需要手动重置
                        leftRecord = leftBlockIterator.next();
                        continue;
                    }
                    //第四种情况，block中的所有record都访问了右边所有page的record，但是左边还能fetchNextBlock
                    else{
                        fetchNextLeftBlock();
                        if(leftRecord!=null){
                            //重置右边的record迭代器以及page迭代器到第一个page上
                            this.rightSourceIterator.reset();
                            fetchNextRightPage();
                            continue;
                        }
                        //第五种情况，join操作结束
                        else{
                            return null;
                        }
                    }

                }

            }
        }

        /**
         * @return true if this iterator has another record to yield, otherwise
         * false
         */
        @Override
        public boolean hasNext() {
            if (this.nextRecord == null) this.nextRecord = fetchNextRecord();
            return this.nextRecord != null;
        }

        /**
         * @return the next record from this iterator
         * @throws NoSuchElementException if there are no more records to yield
         */
        @Override
        public Record next() {
            if (!this.hasNext()) throw new NoSuchElementException();
            Record nextRecord = this.nextRecord;
            this.nextRecord = null;
            return nextRecord;
        }
    }
}
