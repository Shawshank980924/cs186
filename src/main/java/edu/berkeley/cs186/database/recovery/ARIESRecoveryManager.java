package edu.berkeley.cs186.database.recovery;

import com.sun.deploy.panel.DeleteFilesDialog;
import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static edu.berkeley.cs186.database.Transaction.Status.*;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        //在commit之前需要先写log，改变transaction table的状态
        TransactionTableEntry transactionTableEntry = this.transactionTable.get(transNum);
        transactionTableEntry.transaction.setStatus(COMMITTING);
        //追加log,先得到当前transaction的last LSN作为log record中的prevLSN
        long lastLSN = transactionTableEntry.lastLSN;
        long newLSN = logManager.appendToLog(new CommitTransactionLogRecord(transNum, lastLSN));
        transactionTableEntry.lastLSN = newLSN;
        //flush log records
        logManager.flushToLSN(newLSN);

        return newLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry transactionTableEntry = this.transactionTable.get(transNum);
        transactionTableEntry.transaction.setStatus(Transaction.Status.ABORTING);
        long lastLSN = transactionTableEntry.lastLSN;
        long newLSN = logManager.appendToLog(new AbortTransactionLogRecord(transNum, lastLSN));
        transactionTableEntry.lastLSN = newLSN;
        return newLSN;
    }

    public long abortRecovery(long transNum) {
        TransactionTableEntry transactionTableEntry = this.transactionTable.get(transNum);
        transactionTableEntry.transaction.setStatus(RECOVERY_ABORTING);
        long lastLSN = transactionTableEntry.lastLSN;
        long newLSN = logManager.appendToLog(new AbortTransactionLogRecord(transNum, lastLSN));
        transactionTableEntry.lastLSN = newLSN;
        return newLSN;
    }
    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry transactionTableEntry = this.transactionTable.get(transNum);
        Transaction.Status oldStatus = transactionTableEntry.transaction.getStatus();
        if(oldStatus.equals(Transaction.Status.ABORTING)){
            //若原来的transaction status是abort则需要回滚
            //首先需要拿到该transaction的第一个LSN
            long firstLSN = getFirstLSN(transNum);
            //调用回滚函数
            rollbackToLSN(transNum,firstLSN);
        }
        //更新transaction table以及append log record
        transactionTable.remove(transNum);
        transactionTableEntry.transaction.setStatus(Transaction.Status.COMPLETE);
        long lastLSN = transactionTableEntry.lastLSN;
        long newLSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, lastLSN));
        transactionTableEntry.lastLSN = newLSN;
        return newLSN;
    }
    public long getFirstLSN(long transNum){
        long lastLSN = transactionTable.get(transNum).lastLSN;
        while(true){
            LogRecord logRecord = logManager.fetchLogRecord(lastLSN);
//            System.out.println(logRecord.toString());
            Optional<Long> prevLSN = logRecord.getPrevLSN();
            if(!prevLSN.isPresent()){
                break;
            }
            lastLSN = prevLSN.get();
        }
        return lastLSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Emit the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        // TODO(proj5) implement the rollback logic described above
        while(Long.compare(currentLSN,LSN)>0){
            //当前的LSN可以undo
            LogRecord logRecord = logManager.fetchLogRecord(currentLSN);
            if(logRecord.isUndoable()){
                //插入CLR log record,方法的调用者
                LogRecord undoLog = logRecord.undo(transactionEntry.lastLSN);
//                System.out.println(undoLog.toString());

                long newLSN = logManager.appendToLog(undoLog);
                transactionEntry.lastLSN = newLSN;
                undoLog.redo(this,this.diskSpaceManager,this.bufferManager);
            }
            currentLSN = logRecord.getUndoNextLSN().orElse(logRecord.getPrevLSN().orElse(LSN));
//            lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        }

    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        long prevLSN = transactionEntry.lastLSN;
        long newLSN = logManager.appendToLog(new UpdatePageLogRecord(transNum,pageNum,prevLSN,pageOffset,before,after));
        //更新lastLSN
        transactionEntry.lastLSN = newLSN;
        //更新DPT
        if(!dirtyPageTable.containsKey(pageNum)){
            dirtyPageTable.put(pageNum,newLSN);
        }
//        logManager.flushToLSN(newLSN);

        return newLSN;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        rollbackToLSN(transNum,savepointLSN);

        return;
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {

        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
//        System.out.println(beginRecord.LSN);
        long beginLSN = logManager.appendToLog(beginRecord);




        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table

//
        int dptNum = 0,tranTableNum =0;
        Iterator<Long> iterator = dirtyPageTable.keySet().iterator();
        while(iterator.hasNext()){
            if(!EndCheckpointLogRecord.fitsInOneRecord(dptNum+1,0)){
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                long l = logManager.appendToLog(endRecord);
//                System.out.println(l);
//                flushToLSN(endRecord.getLSN());
                chkptDPT = new HashMap<>();
                chkptTxnTable = new HashMap<>();
                dptNum = 0;
            }
            Long nextPageNum = iterator.next();
            chkptDPT.put(nextPageNum,dirtyPageTable.get(nextPageNum));
            dptNum++;
        }
//        System.out.println(chkptDPT.toString());
        iterator = transactionTable.keySet().iterator();
        while(iterator.hasNext()){
            if(!EndCheckpointLogRecord.fitsInOneRecord(dptNum,tranTableNum+1)){
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(endRecord);
                chkptDPT = new HashMap<>();
                chkptTxnTable = new HashMap<>();
                tranTableNum=0;
                dptNum=0;
            }
            Long nextTranNum = iterator.next();
            TransactionTableEntry transactionEntry = transactionTable.get(nextTranNum);
            Pair<Transaction.Status, Long> pair = new Pair<>(transactionEntry.transaction.getStatus(), transactionEntry.lastLSN);
            chkptTxnTable.put(nextTranNum,pair);
            tranTableNum++;
        }
        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related, update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records are processed, cleanup and end transactions that are in
     * the COMMITING state, and move all transactions in the RUNNING state to
     * RECOVERY_ABORTING/emit an abort record.
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        //LSN是masterRecord中的beginRecord
        Iterator<LogRecord> logIt = logManager.scanFrom(LSN);
        //遍历所有beginCheckPoint后面的logRecords，重建DPT和TXN
        //首先
        while (logIt.hasNext()){
            LogRecord logRecord = logIt.next();
            LogType type = logRecord.type;
            //* If the log record is for a transaction operation (getTransNum is present)
            //     * - update the transaction table
            if(logRecord.getTransNum().isPresent()){
                Long transNum = logRecord.getTransNum().get();
                //注意在transactionTable不存在需要利用newTransaction产生一个新的transaction
                if(!transactionTable.containsKey(transNum)){
                    transactionTable.put(transNum,new TransactionTableEntry(newTransaction.apply(transNum)));
                }
                TransactionTableEntry transactionTableEntry = transactionTable.get(transNum);
                transactionTableEntry.lastLSN = logRecord.LSN;
            }
        //     * If the log record is page-related, update the dpt
        //     *   - update/undoupdate page will dirty pages
        //     *   - free/undoalloc page always flush changes to disk
        //     *   - no action needed for alloc/undofree page
            if(logRecord.getPageNum().isPresent()){
                Long pageNum = logRecord.getPageNum().get();
                if(type.equals(LogType.UNDO_UPDATE_PAGE)||type.equals(LogType.UPDATE_PAGE)){
                    dirtyPage(pageNum,logRecord.LSN);

                }
                else if(type.equals(LogType.FREE_PAGE)||type.equals(LogType.UNDO_ALLOC_PAGE)){
                    dirtyPageTable.remove(pageNum);
                }
                //其余情况无需修改dirty table
            }
            //* If the log record is for a change in transaction status:
            //     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
            //     *   from txn table, and add to endedTransactions
            //     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
            //     * - update the transaction table
            if(type.equals(LogType.END_TRANSACTION)){
                //因为之前已经判断过了，所以transactionTable必定存在这个transNum
                Long transNum = logRecord.getTransNum().get();
                TransactionTableEntry transactionTableEntry = transactionTable.get(transNum);
                transactionTableEntry.transaction.cleanup();
                transactionTableEntry.transaction.setStatus(Transaction.Status.COMPLETE);
                transactionTable.remove(transNum);
                endedTransactions.add(transNum);
            }
            else if(type.equals(LogType.ABORT_TRANSACTION)||type.equals(LogType.COMMIT_TRANSACTION)){
                //这部分只要改动transactionTable的状态就行了
                Long transNum = logRecord.getTransNum().get();
                Transaction transaction = transactionTable.get(transNum).transaction;
                transaction.setStatus(type.equals(LogType.ABORT_TRANSACTION)? RECOVERY_ABORTING: COMMITTING);
            }
            //* If the log record is an end_checkpoint record:
            //     * - Copy all entries of checkpoint DPT (replace existing entries if any)
            //     * - Skip txn table entries for transactions that have already ended
            //     * - Add to transaction table if not already present
            //     * - Update lastLSN to be the larger of the existing entry's (if any) and
            //     *   the checkpoint's
            //     * - The status's in the transaction table should be updated if it is possible
            //     *   to transition from the status in the table to the status in the
            //     *   checkpoint. For example, running -> aborting is a possible transition,
            //     *   but aborting -> running is not.
            if(type.equals(LogType.END_CHECKPOINT)){
                //先强制类型转换
                EndCheckpointLogRecord ECLR = (EndCheckpointLogRecord)logRecord;
                Map<Long, Pair<Transaction.Status, Long>> ECLR_transTable = ECLR.getTransactionTable();
                Map<Long, Long> ECLR_dpt = ECLR.getDirtyPageTable();
                Iterator<Long> transIt = ECLR_transTable.keySet().iterator();
                Iterator<Long> dptIt = ECLR_dpt.keySet().iterator();
                while(dptIt.hasNext()){
                    Long pageNum = dptIt.next();
                    dirtyPageTable.put(pageNum,ECLR_dpt.get(pageNum));
                }
                while(transIt.hasNext()){
                    Long transNum = transIt.next();
                    if(endedTransactions.contains(transNum)){
                        continue;
                    }
                    if(!transactionTable.containsKey(transNum)){
                        transactionTable.put(transNum,new TransactionTableEntry(newTransaction.apply(transNum)));
                    }
                    Pair<Transaction.Status, Long> statusLongPair = ECLR_transTable.get(transNum);
                    Transaction.Status status = statusLongPair.getFirst();
                    Long lastLSN = statusLongPair.getSecond();
                    TransactionTableEntry transactionTableEntry = transactionTable.get(transNum);
                    //取较大的lastLSN
                    transactionTableEntry.lastLSN = Math.max(transactionTableEntry.lastLSN,lastLSN);
                    //根据status的正确转换关系set status
                    if(possibleStatusChange(transactionTableEntry.transaction.getStatus(),status)){
                        transactionTableEntry.transaction.setStatus(status);
                    }
                }


            }

        }
        //* After all records are processed, cleanup and end transactions that are in
        //     * the COMMITING state, and move all transactions in the RUNNING state to
        //     * RECOVERY_ABORTING/emit an abort record.
        Iterator<Long> iterator = transactionTable.keySet().iterator();
        while (iterator.hasNext()){
            Long transNum = iterator.next();
            TransactionTableEntry transactionTableEntry = transactionTable.get(transNum);
            Transaction transaction = transactionTableEntry.transaction;
            Transaction.Status status = transaction.getStatus();
            if(status.equals(COMMITTING)){
                transaction.cleanup();
                end(transNum);
            }
            else if(status.equals(RUNNING)){
                abort(transNum);
                transaction.setStatus(RECOVERY_ABORTING);
            }
            else if(status.equals(ABORTING)){
                transaction.setStatus(RECOVERY_ABORTING);
            }
        }

        // TODO(proj5): implement
        return;
    }
    public static boolean possibleStatusChange(Transaction.Status beginStaus, Transaction.Status endStatus){
        switch (beginStaus){
            case RUNNING:
                return endStatus.equals(COMMITTING)||endStatus.equals(ABORTING)||endStatus.equals(RECOVERY_ABORTING);
            case ABORTING:
                return endStatus.equals(COMPLETE)||endStatus.equals(RECOVERY_ABORTING);
            case COMMITTING:
                return endStatus.equals(COMPLETE)||endStatus.equals(ABORTING)||endStatus.equals(RECOVERY_ABORTING);
            case RECOVERY_ABORTING:
                return endStatus.equals(COMPLETE);
            default:
                return false;
            }
        }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - about a partition (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        //得到dpt中最小的recLSN
        Long minLSN = Long.MAX_VALUE;
        Iterator<Long> it = dirtyPageTable.values().iterator();
        while (it.hasNext()){
            minLSN = Math.min(minLSN,it.next());
        }
        Iterator<LogRecord> logIt = logManager.scanFrom(minLSN);
        while (logIt.hasNext()){
            LogRecord logRecord = logIt.next();
            LogType type = logRecord.type;
            if(!logRecord.isRedoable())continue;
            //若是对part的操作永远redo，因为对part的操作不写入磁盘？
            if(isPartOperation(type)){
                logRecord.redo(this,diskSpaceManager,bufferManager);
            }
            //allocates a page (AllocPage/UndoFreePage), always redo it
            else if(type.equals(LogType.ALLOC_PAGE)||type.equals(LogType.UNDO_FREE_PAGE)){
                logRecord.redo(this,diskSpaceManager,bufferManager);
            }
            else if(isModifyPageOperation(type)){
                Long pageNum = logRecord.getPageNum().get();
                //若pageNum在DPT中存在且LSN>=recLSN说明还没刷入磁盘，需要redo
                if(dirtyPageTable.containsKey(pageNum)){
                    Page page = bufferManager.fetchPage(new DummyLockContext(), pageNum);
                    Long pageLSN = page.getPageLSN();
                    try {
                        // Do anything that requires the page here
                        //若disk上的pageLSN小于当前的LSN说明还未写入磁盘
                        if(pageLSN<logRecord.LSN&&dirtyPageTable.get(pageNum)<=logRecord.LSN){
                            logRecord.redo(this,diskSpaceManager,bufferManager);
                        }
                        if(pageLSN>=dirtyPageTable.get(pageNum)){
                            dirtyPageTable.remove(pageNum);
                        }
                    } finally {
                        page.unpin();
                    }

                }
            }




        }
        return;
    }
    public static boolean isPartOperation(LogType type){
        return type.equals(LogType.ALLOC_PART)||type.equals(LogType.FREE_PART)||type.equals(LogType.UNDO_FREE_PART)||type.equals(LogType.UNDO_ALLOC_PART);
    }
    public static boolean isModifyPageOperation(LogType type){
        return type.equals(LogType.UPDATE_PAGE)||type.equals(LogType.UNDO_UPDATE_PAGE)||type.equals(LogType.UNDO_ALLOC_PAGE)||type.equals(LogType.FREE_PAGE);
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and emit the appropriate CLR
     * - replace the entry in the set should be replaced with a new one, using the undoNextLSN
     *   (or prevLSN if not available) of the record; and
     * - if the new LSN is 0, end the transaction and remove it from the queue and transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Long> lastLSNs = new PriorityQueue<>(new Comparator<Long>() {
            @Override
            public int compare(Long o1, Long o2) {
                return o2.compareTo(o1);
            }
        });
        Iterator<TransactionTableEntry> it = transactionTable.values().iterator();
        while(it.hasNext()){
            TransactionTableEntry tte = it.next();
            if(tte.transaction.getStatus().equals(RECOVERY_ABORTING)){
                lastLSNs.add(tte.lastLSN);

            }

        }
        while (!lastLSNs.isEmpty()){
            Long nowLSN = lastLSNs.poll();
            LogRecord logRecord = logManager.fetchLogRecord(nowLSN);
            Long transNum = logRecord.getTransNum().get();
            TransactionTableEntry transactionEntry = transactionTable.get(transNum);
            if(logRecord.isUndoable()){
                LogRecord undoLog = logRecord.undo(transactionEntry.lastLSN);
//                System.out.println(undoLog.toString());

                long newLSN = logManager.appendToLog(undoLog);
                transactionEntry.lastLSN = newLSN;
                undoLog.redo(this,this.diskSpaceManager,this.bufferManager);
                nowLSN = newLSN;
            }
            logRecord = logManager.fetchLogRecord(nowLSN);
            //若本身就是CLR直接取出下一个UndoLSN,否则取prevLSN
            Long nextLSN = logRecord.getUndoNextLSN().orElse(logRecord.getPrevLSN().get());
            //prevLSN为0的情况下，end transaction
            if(nextLSN ==0L){
                TransactionTableEntry transactionTableEntry = transactionTable.get(transNum);
                transactionTable.remove(transNum);
                transactionTableEntry.transaction.cleanup();
                transactionTableEntry.transaction.setStatus(Transaction.Status.COMPLETE);
                long lastLSN = transactionTableEntry.lastLSN;
                long newLSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, lastLSN));
                transactionTableEntry.lastLSN = newLSN;
            }
            else{
                lastLSNs.add(nextLSN);
            }
        }
        return;
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
