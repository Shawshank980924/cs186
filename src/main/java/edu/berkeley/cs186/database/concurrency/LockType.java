package edu.berkeley.cs186.database.concurrency;

/**
 * Utility methods to track the relationships between different lock types.
 */
public enum LockType {
    S,   // shared
    X,   // exclusive
    IS,  // intention shared
    IX,  // intention exclusive
    SIX, // shared intention exclusive
    NL;  // no lock held

    /**
     * This method checks whether lock types A and B are compatible with
     * each other. If a transaction can hold lock type A on a resource
     * at the same time another transaction holds lock type B on the same
     * resource, the lock types are compatible.
     */
    public static boolean compatible(LockType a, LockType b) {
        if (a == null || b == null) {
            throw new NullPointerException("null lock type");
        }
        //其中一个不带锁自然可以兼容
        if(a.equals(NL)||b.equals(NL))return true;
        //其中一个带上IS锁的话表示该node下一个或者多个子节点获取S锁，那么该节点除了X都是可以兼容的
        //注意IS和IX也是可以兼容的只要加的X和S不是同一个子节点即可
        else if(a.equals(IS)||b.equals(IS)){
            return !(a.equals(X)||b.equals(X));
        }
        //带IX，S,X,SIX不能兼容，其他都可以
        else if(a.equals(IX)||b.equals(IX)){
            LockType notIX = a.equals(IX)? b:a;
            return !(notIX.equals(S)||notIX.equals(SIX)||notIX.equals(X));
        }
        //其中一个带S,IX X SIX不能兼容
        else if(a.equals(S)||b.equals(S)){
            LockType notS = a.equals(S)?b:a;
            return !(notS.equals(IX)||notS.equals(X)||notS.equals(SIX));
        }
        //其中一个带SIX，只能兼容NL或者IS
        else if(a.equals(SIX)||b.equals(SIX)){
            LockType notSIX = a.equals(SIX)?b:a;
            return notSIX.equals(NL)||notSIX.equals(IS);
        }
        //其中一个是X，不兼容其他任何的锁
        else if(a.equals(X)||b.equals(X)){
            LockType notX = a.equals(X)? b:a;
            return notX.equals(NL);
        }
        // TODO(proj4_part1): implement

        return false;
    }

    /**
     * This method returns the lock on the parent resource
     * that should be requested for a lock of type A to be granted.
     */
    public static LockType parentLock(LockType a) {
        if (a == null) {
            throw new NullPointerException("null lock type");
        }
        switch (a) {
        case S: return IS;
        case X: return IX;
        case IS: return IS;
        case IX: return IX;
        case SIX: return IX;
        case NL: return NL;
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns if parentLockType has permissions to grant a childLockType
     * on a child.
     */
    public static boolean canBeParentLock(LockType parentLockType, LockType childLockType) {
        if (parentLockType == null || childLockType == null) {
            throw new NullPointerException("null lock type");
        }
        //子节点无锁直接返回
        if(childLockType.equals(NL))return true;
        //父节点是NL,子节点只能是NL
        else if(parentLockType.equals(NL)){
            return childLockType.equals(NL);
        }
        //父节点是IS，子节点能赋S或者IS
        else if(parentLockType.equals(IS)){
            return childLockType.equals(S)||childLockType.equals(IS);
        }
        //父节点是IX，子节点能赋所有的lock
        else if(parentLockType.equals(IX)){
            return true;
        }
        //父节点已经加了S或者X锁，子节点就不能加任何锁了
        else if(parentLockType.equals(S)||parentLockType.equals(X)){
            return false;
        }
        //父节点是SIX，子节点能赋IX或者X
        else if(parentLockType.equals(SIX)){
            return childLockType.equals(IX)||childLockType.equals(X);
        }

        // TODO(proj4_part1): implement

        return false;
    }

    /**
     * This method returns whether a lock can be used for a situation
     * requiring another lock (e.g. an S lock can be substituted with
     * an X lock, because an X lock allows the transaction to do everything
     * the S lock allowed it to do).
     */
    public static boolean substitutable(LockType substitute, LockType required) {
        if (required == null || substitute == null) {
            throw new NullPointerException("null lock type");
        }
        //低等级required锁升级为高等级substitute锁
        if(required.equals(NL))return true;
        else if(substitute.equals(X)){
            return required.equals(X)||required.equals(S)||required.equals(IX)||required.equals(IS)||required.equals(SIX);
        }
        else if(substitute.equals(S)){
            return required.equals(S)||required.equals(IS);
        }
        else if(substitute.equals(IS)){
            return required.equals(IS);
        }
        else if(substitute.equals(IX)){
            return required.equals(IX)||required.equals(IS);
        }
        else if(substitute.equals(SIX)){
            return required.equals(S)||required.equals(IX)||required.equals(IS)||required.equals(SIX);
        }

        // TODO(proj4_part1): implement

        return false;
    }

    /**
     * @return True if this lock is IX, IS, or SIX. False otherwise.
     */
    public boolean isIntent() {
        return this == LockType.IX || this == LockType.IS || this == LockType.SIX;
    }

    @Override
    public String toString() {
        switch (this) {
        case S: return "S";
        case X: return "X";
        case IS: return "IS";
        case IX: return "IX";
        case SIX: return "SIX";
        case NL: return "NL";
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }
}

