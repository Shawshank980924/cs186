package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.HashFunc;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.query.disk.Partition;
import edu.berkeley.cs186.database.query.disk.Run;
import edu.berkeley.cs186.database.table.Record;
import edu.berkeley.cs186.database.table.Schema;

import java.util.*;

public class GHJOperator extends JoinOperator {
    private int numBuffers;
    private Run joinedRecords;

    public GHJOperator(QueryOperator leftSource,
                       QueryOperator rightSource,
                       String leftColumnName,
                       String rightColumnName,
                       TransactionContext transaction) {
        super(leftSource, rightSource, leftColumnName, rightColumnName, transaction, JoinType.GHJ);
        this.numBuffers = transaction.getWorkMemSize();
        this.stats = this.estimateStats();
        this.joinedRecords = null;
    }

    @Override
    public int estimateIOCost() {
        // Since this has a chance of failing on certain inputs we give it the
        // maximum possible cost to encourage the optimizer to avoid it
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean materialized() { return true; }

    @Override
    public BacktrackingIterator<Record> backtrackingIterator() {
        if (joinedRecords == null) {
            // Executing GHJ on-the-fly is arduous without coroutines, so
            // instead we'll accumulate all of our joined records in this run
            // and return an iterator over it once the algorithm completes
            this.joinedRecords = new Run(getTransaction(), getSchema());
            this.run(getLeftSource(), getRightSource(), 1);
        };
        return joinedRecords.iterator();
    }

    @Override
    public Iterator<Record> iterator() {
        return backtrackingIterator();
    }

    /**
     * For every record in the given iterator, hashes the value
     * at the column we're joining on and adds it to the correct partition in
     * partitions.
     *
     * @param partitions an array of partitions to split the records into
     * @param records iterable of records we want to partition
     * @param left true if records are from the left relation, otherwise false
     * @param pass the current pass (used to pick a hash function)
     */
    private void partition(Partition[] partitions, Iterable<Record> records, boolean left, int pass) {
        // TODO(proj3_part1): implement the partitioning logic
        // You may find the implementation in SHJOperator.java to be a good
        // starting point. You can use the static method HashFunc.hashDataBox
        // to get a hash value.
        //对于特定的pass阶段的的records进行hash分区为B-1个分区
        for (Record record: records) {
            // Partition left records on the chosen column
            DataBox columnValue;
            if(left==true)columnValue = record.getValue(getLeftColumnIndex());
            else columnValue = record.getValue(getRightColumnIndex());
            int hash = HashFunc.hashDataBox(columnValue, pass);
            // modulo to get which partition to use
            int partitionNum = hash % partitions.length;
            if (partitionNum < 0)  // hash might be negative
                partitionNum += partitions.length;
            partitions[partitionNum].add(record);
        }

        return;
    }

    /**
     * Runs the buildAndProbe stage on a given partition. Should add any
     * matching records found during the probing stage to this.joinedRecords.
     */
    private void buildAndProbe(Partition leftPartition, Partition rightPartition) {
        // true if the probe records come from the left partition, false otherwise
        boolean probeFirst;
        // We'll build our in memory hash table with these records
        Iterable<Record> buildRecords;
        // We'll probe the table with these records
        Iterable<Record> probeRecords;
        // The index of the join column for the build records
        int buildColumnIndex;
        // The index of the join column for the probe records
        int probeColumnIndex;

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
        // TODO(proj3_part1): implement the building and probing stage
        // You shouldn't refer to any variable starting with "left" or "right"
        // here, use the "build" and "probe" variables we set up for you.
        // Check out how SHJOperator implements this function if you feel stuck.
        Map<DataBox, List<Record>> hashTable = new HashMap<>();
        //首先是build阶段，将buildRecords依次写入hashTable中
        for(Record record: buildRecords){
            //取出join的字段值
            DataBox joinValue = record.getValue(buildColumnIndex);
            if(!hashTable.containsKey(joinValue)){
                hashTable.put(joinValue, new ArrayList<>());
            }
            hashTable.get(joinValue).add(record);
        }
        //然后是probe阶段，将probe partition中的Records依次取出，与相应的key对于的list中依次join
        for(Record probeRecord:probeRecords){
            DataBox joinValue = probeRecord.getValue(probeColumnIndex);
            //该key 在hashTable中不存在则直接continue
            if(!hashTable.containsKey(joinValue)){
                continue;
            }
            for(Record buildRecord : hashTable.get(joinValue)){
                //需要注意的是concat的顺序必须是left concat right，所以必须使用probefirst来进行判断
                if(probeFirst==false){
                    Record joinedRecord = buildRecord.concat(probeRecord);
                    this.joinedRecords.add(joinedRecord);
                }
                else{
                    Record joinedRecord = probeRecord.concat(buildRecord);
                    this.joinedRecords.add(joinedRecord);
                }
                
            }
        }
    }

    /**
     * Runs the grace hash join algorithm. Each pass starts by partitioning
     * leftRecords and rightRecords. If we can run build and probe on a
     * partition we should immediately do so, otherwise we should apply the
     * grace hash join algorithm recursively to break up the partitions further.
     */
    private void run(Iterable<Record> leftRecords, Iterable<Record> rightRecords, int pass) {
        assert pass >= 1;
        if (pass > 5) throw new IllegalStateException("Reached the max number of passes");

        // Create empty partitions
        Partition[] leftPartitions = createPartitions(true);
        Partition[] rightPartitions = createPartitions(false);

        // Partition records into left and right
        this.partition(leftPartitions, leftRecords, true, pass);
        this.partition(rightPartitions, rightRecords, false, pass);
        for (int i = 0; i < leftPartitions.length; i++) {
            // TODO(proj3_part1): implement the rest of grace hash join
            // If you meet the conditions to run the build and probe you should
            // do so immediately. Otherwise you should make a recursive call.
            if(leftPartitions[i].getNumPages()<=this.numBuffers-2||rightPartitions[i].getNumPages()<=this.numBuffers-2){
                //左分区和右分区只要有一个符合即可
                this.buildAndProbe(leftPartitions[i], rightPartitions[i]);

            }
            else{
                //若全部不符合，recursive call run func
                run(leftPartitions[i], rightPartitions[i], pass+1);
            }
        }
    }

    // Provided Helpers ////////////////////////////////////////////////////////

    /**
     * Create an appropriate number of partitions relative to the number of
     * available buffers we have.
     *
     * @return an array of partitions
     */
    private Partition[] createPartitions(boolean left) {
        int usableBuffers = this.numBuffers - 1;
        Partition partitions[] = new Partition[usableBuffers];
        for (int i = 0; i < usableBuffers; i++) {
            partitions[i] = createPartition(left);
        }
        return partitions;
    }

    /**
     * Creates either a regular partition or a smart partition depending on the
     * value of this.useSmartPartition.
     * @param left true if this partition will store records from the left
     *             relation, false otherwise
     * @return a partition to store records from the specified partition
     */
    private Partition createPartition(boolean left) {
        Schema schema = getRightSource().getSchema();
        if (left) schema = getLeftSource().getSchema();
        return new Partition(getTransaction(), schema);
    }

    // Student Input Methods ///////////////////////////////////////////////////

    /**
     * Creates a record using val as the value for a single column of type int.
     * An extra column containing a 500 byte string is appended so that each
     * page will hold exactly 8 records.
     *
     * @param val value the field will take
     * @return a record
     */
    private static Record createRecord(int val) {
        String s = new String(new char[500]);
        return new Record(val, s);
    }

    /**
     * This method is called in testBreakSHJButPassGHJ.
     *
     * Come up with two lists of records for leftRecords and rightRecords such
     * that SHJ will error when given those relations, but GHJ will successfully
     * run. createRecord(int val) takes in an integer value and returns a record
     * with that value in the column being joined on.
     *
     * Hints: Both joins will have access to B=6 buffers and each page can fit
     * exactly 8 records.
     *
     * @return Pair of leftRecords and rightRecords
     */
    public static Pair<List<Record>, List<Record>> getBreakSHJInputs() {
        ArrayList<Record> leftRecords = new ArrayList<>();
        ArrayList<Record> rightRecords = new ArrayList<>();
        //就是要生成一个test类一个pass内不能满足hash分区，但是在5个pass内可以满足
        //每个page有8个records，有个分区会超过4个page也就是说，分配给该分区的超过32个record即可
        //当总的records数量超过32*5=160时至少有个分区超过32个record，也就是说shj会失灵
        // TODO(proj3_part1): populate leftRecords and rightRecords such that
        // SHJ breaks when trying to join them but not GHJ
        for(int i=0;i<=186;i++){
            leftRecords.add(createRecord(i));
            rightRecords.add(createRecord(i));
            
        }



        return new Pair<>(leftRecords, rightRecords);
    }

    /**
     * This method is called in testGHJBreak.
     *
     * Come up with two lists of records for leftRecords and rightRecords such
     * that GHJ will error (in our case hit the maximum number of passes).
     * createRecord(int val) takes in an integer value and returns a record
     * with that value in the column being joined on.
     *
     * Hints: Both joins will have access to B=6 buffers and each page can fit
     * exactly 8 records.
     *
     * @return Pair of leftRecords and rightRecords
     */
    public static Pair<List<Record>, List<Record>> getBreakGHJInputs() {
        ArrayList<Record> leftRecords = new ArrayList<>();
        ArrayList<Record> rightRecords = new ArrayList<>();
        // TODO(proj3_part1): populate leftRecords and rightRecords such that GHJ breaks
        //相同的key出现超过32次就gg
        for(int i=0;i<33;i++){
            leftRecords.add(createRecord(186));
            rightRecords.add(createRecord(186));
        }

        return new Pair<>(leftRecords, rightRecords);
    }

}

