package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement
        //首先获取当前层的resources name
        ResourceName resourceName = getResourceName();
        //获取当前层的effectiveLock
        LockType effectiveLockType = getEffectiveLockType(transaction);
        //若带锁直接抛错
        if(!effectiveLockType.equals(LockType.NL)) {
            throw new DuplicateLockRequestException(transaction.toString()+"already had lock on"+ resourceName.toString() );
        }
        //只读的资源不能获取新的锁
        if(this.readonly) {
            throw new UnsupportedOperationException("readonly");
        }
        //若不是第一层，判断获取后其父节点是否还合法
        LockContext parentContext = this.parentContext();
        if(parentContext!=null){
            LockType pcLockType = parentContext.getEffectiveLockType(transaction);
            if(!LockType.canBeParentLock(pcLockType,lockType)){
                throw new InvalidLockException("error in canBeParentLock");
            }
            Map<Long, Integer> numChildLocks = this.parentContext().numChildLocks;
            long transNum = transaction.getTransNum();
            numChildLocks.put(transNum,numChildLocks.getOrDefault(transNum,0)+1);
        }

        this.lockman.acquire(transaction,resourceName,lockType);

        return;
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        //readonly就不能释放
        if(this.readonly||(this.parentContext()!=null&&this.parentContext().childLocksDisabled)){
            throw new UnsupportedOperationException("readonly or parent hold childLock");
        }
        //children有持有锁
        if(numChildLocks.getOrDefault(transaction.getTransNum(),0)!=0){
            throw new InvalidLockException("children still hold lock");
        }
        //释放自身的锁,更新父节点的numChildLock
        ResourceName resourceName = getResourceName();
        this.lockman.release(transaction,resourceName);
        if(parentContext()!=null){
            Map<Long, Integer> numChildLocks = parentContext().numChildLocks;
            numChildLocks.put(transaction.getTransNum(),numChildLocks.get(transaction.getTransNum())-1);
        }

        return;
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        //若readonly直接抛错
        if(this.readonly||(this.parentContext()!=null&&this.parentContext().childLocksDisabled)){
            throw new UnsupportedOperationException("readonly");
        }
        ResourceName resourceName = this.getResourceName();
        //判断若promote以后父节点是否合法
        if(this.parentContext()!=null){
            if(!LockType.canBeParentLock(this.parentContext().getEffectiveLockType(transaction),newLockType)){
                throw new InvalidLockException("can't be parent");
            }
        }
        LockType oldLockType = this.lockman.getLockType(transaction, resourceName);
        long transNum = transaction.getTransNum();
        //若提升为SIX，需要释放其下的S IS锁
        if(newLockType.equals(LockType.SIX)){
            //获取所有子节点资源
            List<ResourceName> sisDs = sisDescendants(transaction);
            //对所有的子节点资源的父节点的numchildrenlocks进行更新
            for (ResourceName sisD : sisDs) {
                LockContext childLockContext = LockContext.fromResourceName(lockman, sisD);
                LockContext parentContext = childLockContext.parentContext();
                //注意持有自己resource的锁释放以后因为要通过requireAndRelease重新持有所以不减少其parentContext.numChildLocks
                Map<Long, Integer> numChildLocks = parentContext.numChildLocks;
                numChildLocks.put(transNum,numChildLocks.get(transNum)-1);
            }
            sisDs.add(this.getResourceName());
            this.lockman.acquireAndRelease(transaction,getResourceName(),newLockType,sisDs);
            return ;
        }
        else if(newLockType.equals(LockType.SIX)&&hasSIXAncestor(transaction)){
            throw new InvalidLockException("hasSIXAncestor");
        }

        this.lockman.promote(transaction,getResourceName(),newLockType);





        return;
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        if(this.readonly){
            throw new UnsupportedOperationException("readonly");
        }

        LockType currentLockType = this.getEffectiveLockType(transaction);
        if(currentLockType.equals(LockType.NL)){
            throw new NoLockHeldException(this.toString());
        }
        //若当前的LockType是S或者X，下层必定已经带了S或者X直接返回
        if(!currentLockType.isIntent()){
            return;
        }
        ResourceName resourceName = this.getResourceName();

        //获取该transaction持有的所有锁
        List<Lock> locks = this.lockman.getLocks(transaction);
        //需要拿到该层以下所有的resource
        List<Lock> releaseLocks = new ArrayList<>();
        List<ResourceName> resourceNames = new ArrayList<>();
        //注意要释放自己
        resourceNames.add(resourceName);
        //默认更改为S类型
        LockType toType = LockType.S;
        if(currentLockType.equals(LockType.SIX)||currentLockType.equals(LockType.IX)){
            toType = LockType.X;
        }
        //遍历该transaction带的所有锁
        for (Lock lock : locks) {
            //若该锁位于该层以下则需要释放
            if(lock.name.isDescendantOf(resourceName)){
//                //底层存在非IS或者S的替换为X,这里的注释掉因为不符合测试文件的要求
//                if(!lock.lockType.equals(LockType.S)&&!lock.lockType.equals(LockType.IS)){
//                    toType = LockType.X;
//                }
//                releaseLocks.add(lock);
                //获取该锁的lockContext
                ResourceName name = lock.name;
                LockContext childContext = LockContext.fromResourceName(lockman, name);
                //加入释放锁List
                resourceNames.add(lock.name);
                //父节点不为null时需要更新父节点的numchildlocks
                if(childContext.parentContext()!=null){
                    Map<Long, Integer> numChildLocks = childContext.parentContext().numChildLocks;
                    numChildLocks.put(transaction.getTransNum(),numChildLocks.get(transaction.getTransNum())-1);
                }
            }
        }
//        System.out.println(toType);
        this.lockman.acquireAndRelease(transaction,resourceName,toType,resourceNames);
//        System.out.println(this.getEffectiveLockType(transaction));



        return;
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        //返回当前层transaction持有resource的lockType
        LockType lockType = this.lockman.getLockType(transaction, getResourceName());
        return lockType;
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        LockType explicitLockType = getExplicitLockType(transaction);
        //若该层的transaction持有explicitLock，直接返回
        if(!explicitLockType.equals(LockType.NL)){
            return explicitLockType;
        }
        //explictLock是NL，递归调用上层
        LockType effectiveLockType = LockType.NL;
        //该层不是第一层
        if(this.parentContext()!=null){
            effectiveLockType = this.parentContext().getEffectiveLockType(transaction);
        }
        //若上层是S或者X返回相同的
        if(effectiveLockType.equals(LockType.S)||effectiveLockType.equals(LockType.X)){
            return effectiveLockType;
        }else if(effectiveLockType.equals(LockType.IX)){
            //若上层是IX，需要判断上层是否存在SIX,若有SIX默认加了S
            if(hasSIXAncestor(transaction)){
                return LockType.S;
            }
            //若没有SIX,那就是没有锁
            else{
                return LockType.NL;
            }
        }else{
            //其他情况直接返回NL
            return LockType.NL;
        }
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        LockContext parent = this.parentContext();
        boolean hasSIX = false;
        while(parent!=null){
            //获取父节点对该资源的锁
            LockType explicitLockType = parent.getExplicitLockType(transaction);
            if(explicitLockType.equals(LockType.SIX)) {
                hasSIX = true;
                break;
            }
            parent = parent.parentContext();
        }
        return hasSIX;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        long transNum = transaction.getTransNum();
        List<Lock> locks = this.lockman.getLocks(transaction);
        List<ResourceName> result = new ArrayList<>();
        for (Lock lock : locks) {
            if((lock.lockType.equals(LockType.S)||lock.lockType.equals(LockType.IS))&&lock.name.isDescendantOf(this.name)){
                result.add(lock.name);
            }
        }
        return result;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

