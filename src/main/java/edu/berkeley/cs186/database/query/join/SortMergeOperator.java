package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.HashFunc;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.MaterializeOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.query.SortOperator;
import edu.berkeley.cs186.database.table.Record;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class SortMergeOperator extends JoinOperator {
    public SortMergeOperator(QueryOperator leftSource,
                             QueryOperator rightSource,
                             String leftColumnName,
                             String rightColumnName,
                             TransactionContext transaction) {
        super(prepareLeft(transaction, leftSource, leftColumnName),
              prepareRight(transaction, rightSource, rightColumnName),
              leftColumnName, rightColumnName, transaction, JoinType.SORTMERGE);
        this.stats = this.estimateStats();
    }

    /**
     * If the left source is already sorted on the target column then this
     * returns the leftSource, otherwise it wraps the left source in a sort
     * operator.
     */
    private static QueryOperator prepareLeft(TransactionContext transaction,
                                             QueryOperator leftSource,
                                             String leftColumn) {
        leftColumn = leftSource.getSchema().matchFieldName(leftColumn);
        if (leftSource.sortedBy().contains(leftColumn)) return leftSource;
        return new SortOperator(transaction, leftSource, leftColumn);
    }

    /**
     * If the right source isn't sorted, wraps the right source in a sort
     * operator. Otherwise, if it isn't materialized, wraps the right source in
     * a materialize operator. Otherwise, simply returns the right source. Note
     * that the right source must be materialized since we may need to backtrack
     * over it, unlike the left source.
     */
    private static QueryOperator prepareRight(TransactionContext transaction,
                                              QueryOperator rightSource,
                                              String rightColumn) {
        rightColumn = rightSource.getSchema().matchFieldName(rightColumn);
        if (!rightSource.sortedBy().contains(rightColumn)) {
            return new SortOperator(transaction, rightSource, rightColumn);
        } else if (!rightSource.materialized()) {
            return new MaterializeOperator(rightSource, transaction);
        }
        return rightSource;
    }

    @Override
    public Iterator<Record> iterator() {
        return new SortMergeIterator();
    }

    @Override
    public List<String> sortedBy() {
        return Arrays.asList(getLeftColumnName(), getRightColumnName());
    }

    @Override
    public int estimateIOCost() {
        //does nothing
        return 0;
    }

    /**
     * An implementation of Iterator that provides an iterator interface for this operator.
     *    See lecture slides.
     *
     * Before proceeding, you should read and understand SNLJOperator.java
     *    You can find it in the same directory as this file.
     *
     * Word of advice: try to decompose the problem into distinguishable sub-problems.
     *    This means you'll probably want to add more methods than those given (Once again,
     *    SNLJOperator.java might be a useful reference).
     *
     */
    private class SortMergeIterator implements Iterator<Record> {
        /**
        * Some member variables are provided for guidance, but there are many possible solutions.
        * You should implement the solution that's best for you, using any member variables you need.
        * You're free to use these member variables, but you're not obligated to.
        */
        private Iterator<Record> leftIterator;
        private BacktrackingIterator<Record> rightIterator;
        private Record leftRecord;
        private Record nextRecord;
        private Record rightRecord;
        private boolean marked;

        private SortMergeIterator() {
            super();
            leftIterator = getLeftSource().iterator();
            rightIterator = getRightSource().backtrackingIterator();
            rightIterator.markNext();

            if (leftIterator.hasNext() && rightIterator.hasNext()) {
                leftRecord = leftIterator.next();
                rightRecord = rightIterator.next();
            }

            this.marked = false;
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

        /**
         * Returns the next record that should be yielded from this join,
         * or null if there are no more records to join.
         */
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

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
