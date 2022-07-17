package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

/**
 * LockUtil is a declarative layer which simplifies multigranularity lock
 * acquisition for the user (you, in the last task of Part 2). Generally
 * speaking, you should use LockUtil for lock acquisition instead of calling
 * LockContext methods directly.
 */
public class LockUtil {
    /**
     * Ensure that the current transaction can perform actions requiring
     * `requestType` on `lockContext`.
     *
     * `requestType` is guaranteed to be one of: S, X, NL.
     *
     * This method should promote/escalate/acquire as needed, but should only
     * grant the least permissive set of locks needed. We recommend that you
     * think about what to do in each of the following cases:
     * - The current lock type can effectively substitute the requested type
     * - The current lock type is IX and the requested lock is S
     * - The current lock type is an intent lock
     * - None of the above: In this case, consider what values the explicit
     *   lock type can be, and think about how ancestor locks will need to be
     *   acquired or changed.
     *
     * You may find it useful to create a helper method that ensures you have
     * the appropriate locks on all ancestors.
     */
    public static void ensureSufficientLockHeld(LockContext lockContext, LockType requestType) {
        // requestType must be S, X, or NL
        assert (requestType == LockType.S || requestType == LockType.X || requestType == LockType.NL);

        // Do nothing if the transaction or lockContext is null
        TransactionContext transaction = TransactionContext.getTransaction();
        if (transaction == null | lockContext == null) return;

        // You may find these variables useful
        LockContext parentContext = lockContext.parentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        LockType explicitLockType = lockContext.getExplicitLockType(transaction);

        // TODO(proj4_part2): implement
        if(LockType.substitutable(effectiveLockType,requestType)){
            //说明当前获取的锁等级已经大于等于requestType，这样可以直接返回
            //注意这里已经包含了SIX>S的这种情况
            return ;
        }
        else if(effectiveLockType.equals(LockType.IX)&&requestType.equals(LockType.S)){
            //这种情况下应该该锁应该提升为SIX
            //先保证ancestor符合规则
            LockType parentLockType = LockType.parentLock(LockType.SIX);
            ancestorEnsuring(lockContext.parentContext(),transaction,parentLockType);
            lockContext.promote(transaction,LockType.SIX);
        }
        else if(explicitLockType.isIntent()){
            //原有的锁是intent lock
            //由于S可以替换除了SIX和IX以外所有的I锁，而IX和SIX都包含在了以上两种情况下了
            //另外X也可以替换所有的I锁，在这种情况下直接escalate
            LockType parentLockType = LockType.parentLock(requestType);
            ancestorEnsuring(lockContext.parentContext(),transaction,parentLockType);
            lockContext.escalate(transaction);
            //提升以后若不满足要求，再promote
            if(!lockContext.getEffectiveLockType(transaction).equals(requestType)){

                lockContext.promote(transaction,requestType);
            }
        }
        else {
            //剩下的情况是effective不能覆盖request，当前持有的不是intent lock
            //这样的话只能是effective是S 然后request是X或者effective是NL的情况
            //因为S可能来源于自身也可能来源于上层的S或者SIX，
            //若effective是NL，直接acquire
            if(effectiveLockType.equals(LockType.NL)){
                ancestorEnsuring(parentContext,transaction,LockType.parentLock(requestType));
                lockContext.acquire(transaction,requestType);
            }
            //若是自身带S，直接promote
            else if(explicitLockType.equals(LockType.S)){
                ancestorEnsuring(parentContext,transaction,LockType.parentLock(requestType));
                lockContext.promote(transaction,requestType);

            }
            //若这个S来源于上层的S或者SIX递归调用
            else if(explicitLockType.equals(LockType.NL)&&effectiveLockType.equals(LockType.S)){
                ensureSufficientLockHeld(parentContext,requestType);
            }
            else{
                throw new InvalidLockException("not defined");
            }
        }
        return;
    }

    // TODO(proj4_part2) add any helper methods you want
    public static void ancestorEnsuring(LockContext lockContext,TransactionContext transaction,LockType requestType){
        if(lockContext==null){
            return ;
        }
        LockContext parentContext = lockContext.parentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        if(effectiveLockType.equals(LockType.NL)){
            //该父节点没有获取锁。直接获取
            ancestorEnsuring(parentContext,transaction,LockType.parentLock(requestType));
            lockContext.acquire(transaction,requestType);
        }
        else if(!effectiveLockType.equals(requestType)&&LockType.substitutable(requestType,effectiveLockType)){
            //当前持有的锁不与request相同且可以被request替换
            ancestorEnsuring(parentContext,transaction,LockType.parentLock(requestType));
            lockContext.promote(transaction,requestType);
        }
        else{
            //其他情况下说明父节点获取的锁的等级高于requestType，直接返回
            return;
        }
    }
}
