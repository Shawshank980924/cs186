package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have what locks
 * on what resources and handles queuing logic. The lock manager should generally
 * NOT be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with multiple
 * levels of granularity. Multigranularity is handled by LockContext instead.
 *
 * Each resource the lock manager manages has its own queue of LockRequest
 * objects representing a request to acquire (or promote/acquire-and-release) a
 * lock that could not be satisfied at the time. This queue should be processed
 * every time a lock on that resource gets released, starting from the first
 * request, and going in order until a request cannot be satisfied. Requests
 * taken off the queue should be treated as if that transaction had made the
 * request right after the resource was released in absence of a queue (i.e.
 * removing a request by T1 to acquire X(db) should be treated as if T1 had just
 * requested X(db) and there were no queue on db: T1 should be given the X lock
 * on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is
 * processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();

    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently granted locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // Below are a list of helper methods we suggest you implement.
        // You're free to modify their type signatures, delete, or ignore them.

        /**
         * Check if `lockType` is compatible with preexisting locks. Allows
         * conflicts for locks held by transaction with id `except`, which is
         * useful when a transaction tries to replace a lock it already has on
         * the resource.
         */
        public boolean checkCompatible(LockType lockType, long except) {
            //兼容的话分为两种情况：
            // 一种是持有该资源的锁和希望新获取的锁是同一个transaction，那么直接替换为高等级的锁即可

            for (Lock lock : this.locks) {
                //若该lockType和已经存在的非同一个transaction的锁互斥，返回false
                if (except != lock.transactionNum && !LockType.compatible(lockType, lock.lockType)) return false;
                    //若两个锁持有的transaction是同一个，判断是否可以替换其中的一个，若不能返回false
                else if (except == lock.transactionNum && !(LockType.substitutable(lockType, lock.lockType) || LockType.substitutable(lock.lockType, lockType))) {
                    return false;
                }
            }
            // TODO(proj4_part1): implement
            return true;
        }

        /**
         * Gives the transaction the lock `lock`. Assumes that the lock is
         * compatible. Updates lock on resource if the transaction already has a
         * lock.
         */
        public void grantOrUpdateLock(Lock lock) {
            //获取该transaction在这个resource上已经持有的锁
            LockType transactionLockType = getTransactionLockType(lock.transactionNum);
            //若未持有锁，直接添加锁
            if (transactionLockType == LockType.NL) {
                List<Lock> locks = LockManager.this.transactionLocks.getOrDefault(lock.transactionNum, new ArrayList<>());
                locks.add(lock);
                LockManager.this.transactionLocks.put(lock.transactionNum, locks);
//                LockManager.this.transactionLocks.get().add(lock);
                this.locks.add(lock);
            }
            //若该transaction已经持有该resource的锁，替换为高等级的锁
            else {
                Lock oldLock = findLockByTransaction(lock.transactionNum);
                //若新加的lock的等级高需要进行替换,替换不改变获取锁的顺序
                if (LockType.substitutable(lock.lockType, oldLock.lockType)) {
                    List<Lock> locks = LockManager.this.transactionLocks.get(lock.transactionNum);
//                    int index = locks.indexOf(oldLock);
                    locks.remove(oldLock);
//                    locks.add(index, lock);
                    locks.add(lock);
//                    index = this.locks.indexOf(oldLock);
                    this.locks.remove(oldLock);
//                    this.locks.add(index, lock);
                    this.locks.add(lock);
                }
                //否则直接跳过即可


            }
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Releases the lock `lock` and processes the queue. Assumes that the
         * lock has been granted before.
         */
        public void releaseLock(Lock lock) {
            Long transactionNum = lock.transactionNum;
            //这个函数被上层的LockManager调用release时调用，上层释放transactionLocks，这里释放该resource对应的resourceEntry中的锁
            this.locks.remove(lock);
//            LockManager.this.transactionLocks.get(transactionNum).remove(lock);
            //该resource的资源释放了一个锁，队列可能可以推进，调用processQueue()
            processQueue();


            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Adds `request` to the front of the queue if addFront is true, or to
         * the end otherwise.
         */
        public void addToQueue(LockRequest request, boolean addFront) {
            if (addFront) {
                this.waitingQueue.addFirst(request);
            } else {
                this.waitingQueue.addLast(request);
            }
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Grant locks to requests from front to back of the queue, stopping
         * when the next lock cannot be granted. Once a request is completely
         * granted, the transaction that made the request can be unblocked.
         */
        private void processQueue() {
            Iterator<LockRequest> requests = waitingQueue.iterator();
            //只要队列头的第一个request的锁和现持有该resource的锁不冲突，就能一直poll这个队列
            while (requests.hasNext()) {
                LockRequest next = requests.next();
                TransactionContext transaction = next.transaction;
                Lock lock = next.lock;
                List<Lock> releasedLocks = next.releasedLocks;
                if (!this.checkCompatible(lock.lockType, lock.transactionNum)) {
                    break;
                }
                //对于acquireAndRelease来说还需要释放锁
                for (Lock releaseLock : releasedLocks) {
                    releaseLock(releaseLock);
                }
                //只要不冲突，就在locks中加入这把锁
                grantOrUpdateLock(lock);


                requests.remove();
                //unblock这个transaction
                transaction.unblock();

            }

            // TODO(proj4_part1): implement
            return;
        }

        public Lock findLockByTransaction(long transaction) {
            Lock result = null;
            for (Lock lock : this.locks) {
                if (lock.transactionNum == transaction) {
                    result = lock;
                    break;
                }
            }
            return result;
        }

        /**
         * Gets the type of lock `transaction` has on this resource.
         */
        public LockType getTransactionLockType(long transaction) {

            // TODO(proj4_part1): implement
            LockType result = LockType.NL;
            for (Lock lock : this.locks) {
                if (lock.transactionNum == transaction) {
                    result = lock.lockType;
                    break;
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                    ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<String, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to `name`.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`, and
     * releases all locks on `releaseNames` held by the transaction after
     * acquiring the lock in one atomic action.
     * <p>
     * Error checking must be done before any locks are acquired or released. If
     * the new lock is not compatible with another transaction's lock on the
     * resource, the transaction is blocked and the request is placed at the
     * FRONT of the resource's queue.
     * <p>
     * Locks on `releaseNames` should be released only after the requested lock
     * has been acquired. The corresponding queues should be processed.
     * <p>
     * An acquire-and-release that releases an old lock on `name` should NOT
     * change the acquisition time of the lock on `name`, i.e. if a transaction
     * acquired locks in the order: S(A), X(B), acquire X(A) and release S(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is already held
     *                                       by `transaction` and isn't being released
     * @throws NoLockHeldException           if `transaction` doesn't hold a lock on one
     *                                       or more of the names in `releaseNames`
     */
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseNames)
            throws DuplicateLockRequestException, NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method. You are not required to keep
        // all your code within the given synchronized block and are allowed to
        // move the synchronized block elsewhere if you wish.
        boolean shouldBlock = false;
        synchronized (this) {
            LockType oldLockType = getLockType(transaction, name);
            //若原来transaction持有该资源的锁，且释放的锁中没有持有的资源
            if (!oldLockType.equals(LockType.NL) && !releaseNames.contains(name)) {
                throw new DuplicateLockRequestException(oldLockType.toString());
            }
            Lock lock = new Lock(name, lockType, transaction.getTransNum());
            List<Lock> releaseLocks = new ArrayList<>();
            ArrayList<ResourceName> resourceNames = new ArrayList<>(releaseNames);
            if (this.transactionLocks.containsKey(transaction.getTransNum())) {
                List<Lock> locks = this.transactionLocks.get(transaction.getTransNum());
                for (Lock heldLock : locks) {
                    if (releaseNames.contains(heldLock.name)) {
                        releaseLocks.add(heldLock);
                        resourceNames.remove(heldLock.name);
                    }

                }
            }

            //若有releaseName没有被该transaction持有锁，抛出错误
            if (!resourceNames.isEmpty()) {
                throw new NoLockHeldException(transaction.toString() + " on " + releaseNames.toString());
            }
            LockRequest lockRequest = new LockRequest(transaction, lock, releaseLocks);
            //获取该resource的resourceEntry
            ResourceEntry resourceEntry = this.getResourceEntry(name);
            //若和当前持有resource的锁兼容直接获取这个锁,并释放相关锁
            if (resourceEntry.checkCompatible(lock.lockType, lock.transactionNum)) {
                for (ResourceName releaseName : releaseNames) {
                    release(transaction, releaseName);
                }
                resourceEntry.grantOrUpdateLock(lock);
            }
            //当前情况下无法直接获取该锁，LockRequest进入waiting queue，该transaction挂起阻塞
            else {
                resourceEntry.addToQueue(lockRequest, true);
                shouldBlock = true;
                transaction.prepareBlock();
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`.
     * <p>
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is held by
     *                                       `transaction`
     */
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method. You are not required to keep all your
        // code within the given synchronized block and are allowed to move the
        // synchronized block elsewhere if you wish.
        boolean shouldBlock = false;
        synchronized (this) {
            Lock lock = new Lock(name, lockType, transaction.getTransNum());
            LockRequest lockRequest = new LockRequest(transaction, lock);
            //获取该resource的resourceEntry
            ResourceEntry resourceEntry = this.getResourceEntry(name);
            //若该transaction已经持有该resource锁的时候抛出错误
            LockType oldLockType = getLockType(transaction, name);
            if (!oldLockType.equals(LockType.NL)) {
                throw new DuplicateLockRequestException(oldLockType.toString());
            }
            //waiting queue为空的话且和当前持有的锁兼容的话直接获取该锁
            if (resourceEntry.waitingQueue.isEmpty() && resourceEntry.checkCompatible(lock.lockType, lock.transactionNum)) {
                resourceEntry.grantOrUpdateLock(lock);
            }
            //当前情况下无法直接获取该锁，LockRequest进入waiting queue，该transaction挂起阻塞
            else {
                resourceEntry.addToQueue(lockRequest, false);
                shouldBlock = true;
                transaction.prepareBlock();

            }


        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Release `transaction`'s lock on `name`. Error checking must be done
     * before the lock is released.
     * <p>
     * The resource name's queue should be processed after this call. If any
     * requests in the queue have locks to be released, those should be
     * released, and the corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     */
    public void release(TransactionContext transaction, ResourceName name)
            throws NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
            List<Lock> locks = getLocks(transaction);
            Iterator<Lock> iterator = this.transactionLocks.getOrDefault(transaction.getTransNum(), new ArrayList<>()).iterator();
            if (!iterator.hasNext()) {
                throw new NoLockHeldException(transaction.toString() + " on " + name);
            }
            ResourceEntry resourceEntry = getResourceEntry(name);
            while (iterator.hasNext()) {
                Lock next = iterator.next();
                if (next.name.equals(name)) {
                    iterator.remove();
                    resourceEntry.releaseLock(next);
                    break;
                    //release 锁可能可以前进

                }
            }


        }
    }

    /**
     * Promote a transaction's lock on `name` to `newLockType` (i.e. change
     * the transaction's lock on `name` from the current lock type to
     * `newLockType`, if its a valid substitution).
     * <p>
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the
     * transaction is blocked and the request is placed at the FRONT of the
     * resource's queue.
     * <p>
     * A lock promotion should NOT change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     *                                       `newLockType` lock on `name`
     * @throws NoLockHeldException           if `transaction` has no lock on `name`
     * @throws InvalidLockException          if the requested lock type is not a
     *                                       promotion. A promotion from lock type A to lock type B is valid if and
     *                                       only if B is substitutable for A, and B is not equal to A.
     */
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry resourceEntry = getResourceEntry(name);
            LockType lockType = getLockType(transaction, name);
            Lock newLock = new Lock(name, newLockType, transaction.getTransNum());
            //这个transaction之前没有持有该资源的锁，抛出错误
            if (lockType.equals(LockType.NL)) {
                throw new NoLockHeldException(lockType.toString());
            }
            //原持有锁的类型和现在的相同抛出错误
            if (lockType.equals(newLockType)) {
                throw new DuplicateLockRequestException(lockType.toString());

            }
            //升级后的锁和原来持有resource的锁是兼容的，判断是否可替换
            if (resourceEntry.checkCompatible(newLockType, transaction.getTransNum())) {
                //不可替换抛出错误
                if (!LockType.substitutable(newLockType, lockType)) {
                    throw new InvalidLockException(newLockType.toString() + "->" + lockType.toString());
                } else {
                    resourceEntry.grantOrUpdateLock(newLock);
                }
            } else {
                //无法promote，插入队列的第一个位置
                resourceEntry.addToQueue(new LockRequest(transaction, newLock), true);
                transaction.prepareBlock();
                shouldBlock = true;
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Return the type of lock `transaction` has on `name` or NL if no lock is
     * held.
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
        // TODO(proj4_part1): implement
        ResourceEntry resourceEntry = getResourceEntry(name);
        LockType result = LockType.NL;
        for (Lock lock : resourceEntry.locks) {
            if (lock.transactionNum == transaction.getTransNum()) {
                result = lock.lockType;
                break;
            }
        }
        return result;
    }

    /**
     * Returns the list of locks held on `name`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks held by `transaction`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at the top of this file and the top
     * of LockContext.java for more information.
     */
    public synchronized LockContext context(String name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, name));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at the top of this
     * file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database");
    }
}



