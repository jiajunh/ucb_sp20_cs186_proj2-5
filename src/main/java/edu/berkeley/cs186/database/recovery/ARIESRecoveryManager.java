package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.LockContext;
import edu.berkeley.cs186.database.concurrency.LockType;
import edu.berkeley.cs186.database.concurrency.LockUtil;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Lock context of the entire database.
    private LockContext dbContext;
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given transaction number.
    private Function<Long, Transaction> newTransaction;
    // Function to update the transaction counter.
    protected Consumer<Long> updateTransactionCounter;
    // Function to get the transaction counter.
    protected Supplier<Long> getTransactionCounter;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();

    // List of lock requests made during recovery. This is only populated when locking is disabled.
    List<String> lockRequests;

    public ARIESRecoveryManager(LockContext dbContext, Function<Long, Transaction> newTransaction,
                                Consumer<Long> updateTransactionCounter, Supplier<Long> getTransactionCounter) {
        this(dbContext, newTransaction, updateTransactionCounter, getTransactionCounter, false);
    }

    ARIESRecoveryManager(LockContext dbContext, Function<Long, Transaction> newTransaction,
                         Consumer<Long> updateTransactionCounter, Supplier<Long> getTransactionCounter,
                         boolean disableLocking) {
        this.dbContext = dbContext;
        this.newTransaction = newTransaction;
        this.updateTransactionCounter = updateTransactionCounter;
        this.getTransactionCounter = getTransactionCounter;
        this.lockRequests = disableLocking ? new ArrayList<>() : null;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     *
     * The master record should be added to the log, and a checkpoint should be taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor because of the cyclic dependency
     * between the buffer manager and recovery manager (the buffer manager must interface with the
     * recovery manager to block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManagerImpl(bufferManager);
    }

    // Forward Processing ////////////////////////////////////////////////////////////////////

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
     * A commit record should be emitted, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement

        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        long newLSN = logManager.appendToLog(new CommitTransactionLogRecord(transNum, LSN));
        transaction.setStatus(Transaction.Status.COMMITTING);
        entry.lastLSN = newLSN;
        this.pageFlushHook(newLSN);

        return newLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be emitted, and the transaction table and transaction
     * status should be updated. No CLRs should be emitted.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        long newLSN = logManager.appendToLog(new AbortTransactionLogRecord(transNum, LSN));
        transaction.setStatus(Transaction.Status.ABORTING);
        entry.lastLSN = newLSN;
        return newLSN;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting.
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be emitted,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        long nextLSN = LSN;
        if(transaction.getStatus() == Transaction.Status.ABORTING) {
            LogRecord r = logManager.fetchLogRecord(LSN);
            while (!r.getPrevLSN().equals(Optional.empty())) {
                if (r.isUndoable()) {
                    LogRecord clr = r.undo(nextLSN).getFirst();
                    boolean needflush = r.undo(nextLSN).getSecond();
                    nextLSN = logManager.appendToLog(clr);
                    entry.lastLSN = nextLSN;

                    if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                        if(!dirtyPageTable.containsKey(clr.getPageNum().get())){
                            dirtyPageTable.put(clr.getPageNum().get(), nextLSN);
                        }
                    }
                    if(needflush){
                        this.pageFlushHook(nextLSN);
                        if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                            this.diskIOHook(clr.getPageNum().get());
                        }

                    }
                    clr.redo(diskSpaceManager, bufferManager);
                }
                r = logManager.fetchLogRecord(r.getPrevLSN().get());

            }
        }

        long newLSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, nextLSN));
        transaction.setStatus(Transaction.Status.COMPLETE);
        entry.lastLSN = newLSN;
        transactionTable.remove(transNum);
        return newLSN;
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
        dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be emitted; if the number of bytes written is
     * too large (larger than BufferManager.EFFECTIVE_PAGE_SIZE / 2), then two records
     * should be written instead: an undo-only record followed by a redo-only record.
     *
     * Both the transaction table and dirty page table should be updated accordingly.
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

        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        int pageSize = before.length;
        if(pageSize <= BufferManager.EFFECTIVE_PAGE_SIZE / 2){
            Long LSN1 = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSN, pageOffset, before, after));
            entry.touchedPages.add(pageNum);
            if(!dirtyPageTable.containsKey(pageNum))
                dirtyPageTable.put(pageNum, LSN1);
            entry.lastLSN = LSN1;
            return LSN1;
        }else{
            Long LSN1 = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSN, pageOffset, before, new byte[0]));
            Long LSN2 = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSN1, pageOffset, new byte[0], after));
            entry.touchedPages.add(pageNum);
            if(!dirtyPageTable.containsKey(pageNum))
                dirtyPageTable.put(pageNum, LSN1);
            entry.lastLSN = LSN2;
            return LSN2;
        }


    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) {
            return -1L;
        }

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
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) {
            return -1L;
        }

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
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN, touchedPages
        transactionEntry.lastLSN = LSN;
        transactionEntry.touchedPages.add(pageNum);
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
     * The appropriate log record should be emitted, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) {
            return -1L;
        }

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN, touchedPages
        transactionEntry.lastLSN = LSN;
        transactionEntry.touchedPages.add(pageNum);
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
        long targetLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        long nextLSN = LSN;

        if(LSN == targetLSN) return;
        while (true) {
            LogRecord r = logManager.fetchLogRecord(LSN);
            if (r.isUndoable()) {
                LogRecord clr = r.undo(nextLSN).getFirst();
                boolean needflush = r.undo(nextLSN).getSecond();
                nextLSN = logManager.appendToLog(clr);
                entry.lastLSN = nextLSN;
                if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                    if(!dirtyPageTable.containsKey(clr.getPageNum().get())){
                        dirtyPageTable.put(clr.getPageNum().get(), nextLSN);
                    }
                }
                if(needflush){
                    this.pageFlushHook(nextLSN);
                    if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                        this.diskIOHook(clr.getPageNum().get());
                    }
                }
                clr.redo(diskSpaceManager, bufferManager);
            }
            if(r.getUndoNextLSN().isPresent() && r.getUndoNextLSN().get() == targetLSN) break;
            else if(r.getPrevLSN().get() == targetLSN) break;
            LSN = r.getPrevLSN().get();
//            r = logManager.fetchLogRecord(r.getPrevLSN().get());

        }

    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible,
     * using recLSNs from the DPT, then status/lastLSNs from the transactions table,
     * and then finally, touchedPages from the transactions table, and written
     * when full (or when done).
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord(getTransactionCounter.get());
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> dpt = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> txnTable = new HashMap<>();
        Map<Long, List<Long>> touchedPages = new HashMap<>();
        int numTouchedPages = 0;

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        dpt = dirtyPageTable;
        for(Long transNum: transactionTable.keySet()){
            txnTable.put(transNum, new Pair<Transaction.Status, Long>(
                    transactionTable.get(transNum).transaction.getStatus(),transactionTable.get(transNum).lastLSN));
            List<Long> list = new ArrayList();
            for(Long tp: transactionTable.get(transNum).touchedPages){
                list.add(tp);
            }
            touchedPages.put(transNum, list);
        }

        for (Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            long transNum = entry.getKey();
            for (long pageNum : entry.getValue().touchedPages) {
                boolean fitsAfterAdd;
                if (!touchedPages.containsKey(transNum)) {
                    fitsAfterAdd = EndCheckpointLogRecord.fitsInOneRecord(
                                       dpt.size(), txnTable.size(), touchedPages.size() + 1, numTouchedPages + 1);
                } else {
                    fitsAfterAdd = EndCheckpointLogRecord.fitsInOneRecord(
                                       dpt.size(), txnTable.size(), touchedPages.size(), numTouchedPages + 1);
                }

                if (!fitsAfterAdd) {
                    LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
                    logManager.appendToLog(endRecord);

                    dpt.clear();
                    txnTable.clear();
                    touchedPages.clear();
                    numTouchedPages = 0;
                }

                touchedPages.computeIfAbsent(transNum, t -> new ArrayList<>());
                touchedPages.get(transNum).add(pageNum);
                ++numTouchedPages;
            }
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(dpt, txnTable, touchedPages);
        logManager.appendToLog(endRecord);

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    // TODO(proj5): add any helper methods needed

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery //////////////////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery. Recovery is
     * complete when the Runnable returned is run to termination. New transactions may be
     * started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the dirty page
     * table of non-dirty pages (pages that aren't dirty in the buffer manager) between
     * redo and undo, and perform a checkpoint after undo.
     *
     * This method should return right before undo is performed.
     *
     * @return Runnable to run to finish restart recovery
     */
    @Override
    public Runnable restart() {
        // TODO(proj5): implement
        this.restartAnalysis();
        this.restartRedo();
        List<Long> dirtyPages = new ArrayList<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) {
                dirtyPages.add(pageNum);
            }
        });
        for(long pageNum: dirtyPageTable.keySet()){
            if(!dirtyPages.contains(pageNum)) dirtyPageTable.remove(pageNum);
        }
        return () -> {
            this.restartUndo();
            this.checkpoint();
        };
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the begin checkpoint record.
     *
     * If the log record is for a transaction operation:
     * - update the transaction table
     * - if it's page-related (as opposed to partition-related),
     *   - add to touchedPages
     *   - acquire X lock
     *   - update DPT (alloc/free/undoalloc/undofree always flushes changes to disk)
     *
     * If the log record is for a change in transaction status:
     * - clean up transaction (Transaction#cleanup) if END_TRANSACTION
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     *
     * If the log record is a begin_checkpoint record:
     * - Update the transaction counter
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Update lastLSN to be the larger of the existing entry's (if any) and the checkpoint's;
     *   add to transaction table if not already present.
     * - Add page numbers from checkpoint's touchedPages to the touchedPages sets in the
     *   transaction table if the transaction has not finished yet, and acquire X locks.
     *
     * Then, cleanup and end transactions that are in the COMMITING state, and
     * move all transactions in the RUNNING state to RECOVERY_ABORTING.
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        assert (record != null);
        // Type casting
        assert (record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;

        // TODO(proj5): implement
        Iterator<LogRecord> iter = logManager.scanFrom(LSN);
        while(iter.hasNext()){
            LogRecord logRecord = iter.next();

            if(logRecord instanceof UpdatePageLogRecord ||
            logRecord instanceof AllocPartLogRecord ||
            logRecord instanceof AllocPageLogRecord ||
            logRecord instanceof FreePartLogRecord ||
            logRecord instanceof FreePageLogRecord ||
            logRecord instanceof UndoUpdatePageLogRecord ||
            logRecord instanceof UndoAllocPartLogRecord ||
            logRecord instanceof UndoAllocPageLogRecord ||
            logRecord instanceof UndoFreePartLogRecord ||
            logRecord instanceof UndoFreePageLogRecord){
                long transNum = logRecord.getTransNum().get();
                if(transactionTable.containsKey(transNum)){
                    transactionTable.get(transNum).lastLSN = logRecord.getLSN();
                }else{
                    Transaction newTxn = newTransaction.apply(transNum);
                    TransactionTableEntry newTxntableEntry = new TransactionTableEntry(newTxn);
                    newTxntableEntry.lastLSN = logRecord.getLSN();
                    transactionTable.put(transNum, newTxntableEntry);
                }

                if(logRecord instanceof UpdatePageLogRecord ||
                        logRecord instanceof AllocPageLogRecord ||
                        logRecord instanceof FreePageLogRecord ||
                        logRecord instanceof UndoUpdatePageLogRecord ||
                        logRecord instanceof UndoAllocPageLogRecord ||
                        logRecord instanceof UndoFreePageLogRecord){

                    TransactionTableEntry txnEntry = transactionTable.get(transNum);
                    long pageNum = logRecord.getPageNum().get();
                    txnEntry.touchedPages.add(pageNum);
                    this.acquireTransactionLock(txnEntry.transaction, this.getPageLockContext(pageNum), LockType.X);
                    if(!dirtyPageTable.containsKey(pageNum)){
                        dirtyPageTable.put(pageNum, logRecord.getLSN());
                    }

                    if(logRecord instanceof AllocPageLogRecord ||
                            logRecord instanceof FreePageLogRecord ||
                            logRecord instanceof UndoAllocPageLogRecord ||
                            logRecord instanceof UndoFreePageLogRecord){
                        this.diskIOHook(pageNum);
                    }

                }

            }else if(logRecord instanceof EndTransactionLogRecord ||
            logRecord instanceof CommitTransactionLogRecord ||
            logRecord instanceof AbortTransactionLogRecord){
                long transNum = logRecord.getTransNum().get();
                if(!transactionTable.containsKey(transNum)){
                    Transaction newTxn = newTransaction.apply(transNum);
                    TransactionTableEntry newTxntableEntry = new TransactionTableEntry(newTxn);
                    transactionTable.put(transNum, newTxntableEntry);
                }
                if(logRecord instanceof EndTransactionLogRecord){
                    transactionTable.get(transNum).transaction.cleanup();
                    transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.get(transNum).lastLSN = logRecord.getLSN();
                    transactionTable.remove(transNum);
                }else if(logRecord instanceof CommitTransactionLogRecord){

                    transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMMITTING);
                    transactionTable.get(transNum).lastLSN = logRecord.getLSN();

                }else{
                    transactionTable.get(transNum).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                    transactionTable.get(transNum).lastLSN = logRecord.getLSN();
                }

            }else if(logRecord instanceof BeginCheckpointLogRecord){
                this.updateTransactionCounter.accept(logRecord.getMaxTransactionNum().get());
            }else{

                Map<Long, Long> endDPT = logRecord.getDirtyPageTable();
                Map<Long, Pair<Transaction.Status, Long>> endTxnTable = logRecord.getTransactionTable();
                Map<Long, List<Long>> endTouchedPages = logRecord.getTransactionTouchedPages();

                for(Long pageNum: endDPT.keySet()){
                    dirtyPageTable.put(pageNum, endDPT.get(pageNum));
                }

                for(Long transNum: endTxnTable.keySet()){
                    if(!transactionTable.containsKey(transNum)){
                        Transaction newTxn = newTransaction.apply(transNum);
                        newTxn.setStatus(endTxnTable.get(transNum).getFirst());
                        TransactionTableEntry newTxntableEntry = new TransactionTableEntry(newTxn);
                        newTxntableEntry.lastLSN = endTxnTable.get(transNum).getSecond();
                        transactionTable.put(transNum, newTxntableEntry);
                    }
                    long endLSN = endTxnTable.get(transNum).getSecond();
                    long currLSN = transactionTable.get(transNum).lastLSN;
                    transactionTable.get(transNum).lastLSN = Math.max(endLSN, currLSN);
                }

                for(Long transNum: endTouchedPages.keySet()){
                    if(transactionTable.get(transNum).transaction.getStatus() != Transaction.Status.COMPLETE){
                        List<Long> tps = endTouchedPages.get(transNum);
                        for(Long tp: tps){
                            transactionTable.get(transNum).touchedPages.add(tp);
                            this.acquireTransactionLock(transactionTable.get(transNum).transaction, getPageLockContext(tp), LockType.X);
                        }
                    }
                }

            }
        }

        for(Long t: transactionTable.keySet()){
            TransactionTableEntry entry = transactionTable.get(t);
            if(entry.transaction.getStatus() == Transaction.Status.COMMITTING){
                entry.transaction.cleanup();
                entry.transaction.setStatus(Transaction.Status.COMPLETE);
                entry.lastLSN = logManager.appendToLog(new EndTransactionLogRecord(t, entry.lastLSN));
                transactionTable.remove(t);
            }else if(entry.transaction.getStatus() == Transaction.Status.RUNNING){
                entry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                entry.lastLSN = logManager.appendToLog(new AbortTransactionLogRecord(t, entry.lastLSN));
            }
        }

    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the DPT.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - about a page (Update/Alloc/Free/Undo..Page) in the DPT with LSN >= recLSN,
     *   the page is fetched from disk and the pageLSN is checked, and the record is redone.
     * - about a partition (Alloc/Free/Undo..Part), redo it.
     */
    void restartRedo() {
        // TODO(proj5): implement
        long lowestRecLSN = Integer.MAX_VALUE;
        for(Long pageNum: dirtyPageTable.keySet()){
            lowestRecLSN = Math.min(lowestRecLSN, dirtyPageTable.get(pageNum));
        }
        Iterator<LogRecord> iter = logManager.scanFrom(lowestRecLSN);
        while(iter.hasNext()){
            LogRecord logRecord = iter.next();
            boolean isRedoable = false;
            boolean isPageRelated = false;
            boolean isInDPT = false;
            boolean isLSNNotLessThanRec = false;
            boolean isPageLSNLessThanLSN = false;

            if(logRecord.isRedoable()) isRedoable = true;

            if(logRecord instanceof UpdatePageLogRecord ||
                    logRecord instanceof AllocPageLogRecord ||
                    logRecord instanceof FreePageLogRecord ||
                    logRecord instanceof UndoUpdatePageLogRecord ||
                    logRecord instanceof UndoAllocPageLogRecord ||
                    logRecord instanceof UndoFreePageLogRecord){
                isPageRelated = true;
            }

            if(isPageRelated){
                long pageNum = logRecord.getPageNum().get();

                if(dirtyPageTable.containsKey(pageNum)) isInDPT = true;

                long LSN = logRecord.getLSN();
                long recLSN = dirtyPageTable.get(pageNum);
                if(LSN >= recLSN) isLSNNotLessThanRec = true;

                Page page = bufferManager.fetchPage(getPageLockContext(pageNum), pageNum, false);
                long pageLSN = page.getPageLSN();
                if(pageLSN < LSN) isPageLSNLessThanLSN = true;
            }

            if(isRedoable && (!isPageRelated || (isInDPT&&isLSNNotLessThanRec&&isPageLSNLessThanLSN))){
                logRecord.redo(diskSpaceManager, bufferManager);
            }
        }
    }

    /**
     * This method performs the redo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, emit the appropriate CLR, and update tables accordingly;
     * - replace the entry in the set should be replaced with a new one, using the undoNextLSN
     *   (or prevLSN if none) of the record; and
     * - if the new LSN is 0, end the transaction and remove it from the queue and transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Long> queue = new PriorityQueue<>(new Comparator<Long>() {
            @Override
            public int compare(Long o1, Long o2) {
                return o2.intValue() - o1.intValue();
            }
        });
        for(Long trans: transactionTable.keySet()){
            TransactionTableEntry entry = transactionTable.get(trans);
            queue.add(entry.lastLSN);
        }

        while(!queue.isEmpty()){
            long LSN = queue.poll();
            LogRecord logRecord = logManager.fetchLogRecord(LSN);

            if(logRecord.isUndoable()){
                long lastLSN = transactionTable.get(logRecord.getTransNum().get()).lastLSN;
                LogRecord clr = logRecord.undo(lastLSN).getFirst();
                boolean needflush = logRecord.undo(lastLSN).getSecond();

                long newLSN = logManager.appendToLog(clr);
                transactionTable.get(logRecord.getTransNum().get()).lastLSN = newLSN;

                if(!dirtyPageTable.containsKey(clr.getPageNum().get()))
                    dirtyPageTable.put(clr.getPageNum().get(), newLSN);

                if(needflush){
                    this.pageFlushHook(newLSN);
                    this.diskIOHook(clr.getPageNum().get());
//                    if(logRecord instanceof AllocPageLogRecord ||
//                            logRecord instanceof FreePageLogRecord ||
//                            logRecord instanceof UndoAllocPageLogRecord ||
//                            logRecord instanceof UndoFreePageLogRecord){
//
//                    }
                }
                clr.redo(diskSpaceManager, bufferManager);

//                if(logRecord instanceof UpdatePageLogRecord ||
//                        logRecord instanceof AllocPageLogRecord ||
//                        logRecord instanceof FreePageLogRecord ||
//                        logRecord instanceof UndoUpdatePageLogRecord ||
//                        logRecord instanceof UndoAllocPageLogRecord ||
//                        logRecord instanceof UndoFreePageLogRecord){
//
//                }

            }

            long newLSN;
            if(logRecord.getUndoNextLSN().isPresent()){
                queue.add(logRecord.getUndoNextLSN().get());
                newLSN = logRecord.getUndoNextLSN().get();
            }else{
                queue.add(logRecord.getPrevLSN().get());
                newLSN = logRecord.getPrevLSN().get();
            }

            if(newLSN == 0){
                this.end(logRecord.getTransNum().get());
                queue.remove(newLSN);
                transactionTable.remove(logRecord.getTransNum().get());
            }
        }

        return;
    }

    // TODO(proj5): add any helper methods needed

    // Helpers ///////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the lock context for a given page number.
     * @param pageNum page number to get lock context for
     * @return lock context of the page
     */
    private LockContext getPageLockContext(long pageNum) {
        int partNum = DiskSpaceManager.getPartNum(pageNum);
        return this.dbContext.childContext(partNum).childContext(pageNum);
    }

    /**
     * Locks the given lock context with the specified lock type under the specified transaction,
     * acquiring locks on ancestors as needed.
     * @param transaction transaction to request lock for
     * @param lockContext lock context to lock
     * @param lockType type of lock to request
     */
    private void acquireTransactionLock(Transaction transaction, LockContext lockContext,
                                        LockType lockType) {
        acquireTransactionLock(transaction.getTransactionContext(), lockContext, lockType);
    }

    /**
     * Locks the given lock context with the specified lock type under the specified transaction,
     * acquiring locks on ancestors as needed.
     * @param transactionContext transaction context to request lock for
     * @param lockContext lock context to lock
     * @param lockType type of lock to request
     */
    private void acquireTransactionLock(TransactionContext transactionContext,
                                        LockContext lockContext, LockType lockType) {
        TransactionContext.setTransaction(transactionContext);
        try {
            if (lockRequests == null) {
                LockUtil.ensureSufficientLockHeld(lockContext, lockType);
            } else {
                lockRequests.add("request " + transactionContext.getTransNum() + " " + lockType + "(" +
                                 lockContext.getResourceName() + ")");
            }
        } finally {
            TransactionContext.unsetTransaction();
        }
    }

    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A), in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
        Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
