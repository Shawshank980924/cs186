package edu.berkeley.cs186.database.index;

import edu.berkeley.cs186.database.common.Buffer;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.LockContext;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.databox.Type;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.table.RecordId;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * A leaf of a B+ tree. Every leaf in a B+ tree of order d stores between d and
 * 2d (key, record id) pairs and a pointer to its right sibling (i.e. the page
 * number of its right sibling). Moreover, every leaf node is serialized and
 * persisted on a single page; see toBytes and fromBytes for details on how a
 * leaf is serialized. For example, here is an illustration of two order 2
 * leafs connected together:
 *
 *   leaf 1 (stored on some page)          leaf 2 (stored on some other page)
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 *   | k0:r0 | k1:r1 | k2:r2 |       | --> | k3:r3 | k4:r4 |       |       |
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 */
class LeafNode extends BPlusNode {
    // Metadata about the B+ tree that this node belongs to.
    private BPlusTreeMetadata metadata;

    // Buffer manager
    private BufferManager bufferManager;

    // Lock context of the B+ tree
    private LockContext treeContext;

    // The page on which this leaf is serialized.
    private Page page;

    // The keys and record ids of this leaf. `keys` is always sorted in ascending
    // order. The record id at index i corresponds to the key at index i. For
    // example, the keys [a, b, c] and the rids [1, 2, 3] represent the pairing
    // [a:1, b:2, c:3].
    //
    // Note the following subtlety. keys and rids are in-memory caches of the
    // keys and record ids stored on disk. Thus, consider what happens when you
    // create two LeafNode objects that point to the same page:
    //
    //   BPlusTreeMetadata meta = ...;
    //   int pageNum = ...;
    //   LockContext treeContext = new DummyLockContext();
    //
    //   LeafNode leaf0 = LeafNode.fromBytes(meta, bufferManager, treeContext, pageNum);
    //   LeafNode leaf1 = LeafNode.fromBytes(meta, bufferManager, treeContext, pageNum);
    //
    // This scenario looks like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2]     | | | k0:r0 | k1:r1 | k2:r2 |       |
    //   | rids = [r0, r1, r2]     | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Now imagine we perform on operation on leaf0 like leaf0.put(k3, r3). The
    // in-memory values of leaf0 will be updated and they will be synced to disk.
    // But, the in-memory values of leaf1 will not be updated. That will look
    // like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2, k3] | | | k0:r0 | k1:r1 | k2:r2 | k3:r3 |
    //   | rids = [r0, r1, r2, r3] | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Make sure your code (or your tests) doesn't use stale in-memory cached
    // values of keys and rids.
    private List<DataBox> keys;
    private List<RecordId> rids;

    // If this leaf is the rightmost leaf, then rightSibling is Optional.empty().
    // Otherwise, rightSibling is Optional.of(n) where n is the page number of
    // this leaf's right sibling.
    private Optional<Long> rightSibling;

    // Constructors ////////////////////////////////////////////////////////////
    /**
     * Construct a brand new leaf node. This constructor will fetch a new pinned
     * page from the provided BufferManager `bufferManager` and persist the node
     * to that page.
     */
    LeafNode(BPlusTreeMetadata metadata, BufferManager bufferManager, List<DataBox> keys,
             List<RecordId> rids, Optional<Long> rightSibling, LockContext treeContext) {
        this(metadata, bufferManager, bufferManager.fetchNewPage(treeContext, metadata.getPartNum()),
             keys, rids,
             rightSibling, treeContext);
    }

    /**
     * Construct a leaf node that is persisted to page `page`.
     */
    private LeafNode(BPlusTreeMetadata metadata, BufferManager bufferManager, Page page,
                     List<DataBox> keys,
                     List<RecordId> rids, Optional<Long> rightSibling, LockContext treeContext) {
        try {
            assert (keys.size() == rids.size());
            assert (keys.size() <= 2 * metadata.getOrder());

            this.metadata = metadata;
            this.bufferManager = bufferManager;
            this.treeContext = treeContext;
            this.page = page;
            this.keys = new ArrayList<>(keys);
            this.rids = new ArrayList<>(rids);
            this.rightSibling = rightSibling;

            sync();
        } finally {
            page.unpin();
        }
    }

    // Core API ////////////////////////////////////////////////////////////////
    // See BPlusNode.get.
    @Override
    public LeafNode get(DataBox key) {
        // TODO(proj2): implement
        /**
         * n.get(k) returns the leaf node on which k may reside when queried from n.
         * For example, consider the following B+ tree (for brevity, only keys are
         * shown; record ids are omitted).
         *
         *                               inner
         *                               +----+----+----+----+
         *                               | 10 | 20 |    |    |
         *                               +----+----+----+----+
         *                              /     |     \
         *                         ____/      |      \____
         *                        /           |           \
         *   +----+----+----+----+  +----+----+----+----+  +----+----+----+----+
         *   |  1 |  2 |  3 |    |->| 11 | 12 | 13 |    |->| 21 | 22 | 23 |    |
         *   +----+----+----+----+  +----+----+----+----+  +----+----+----+----+
         *   leaf0                  leaf1                  leaf2
         *
         * inner.get(x) should return
         *
         *   - leaf0 when x < 10,
         *   - leaf1 when 10 <= x < 20, and
         *   - leaf2 when x >= 20.
         *
         * Note that inner.get(4) would return leaf0 even though leaf0 doesn't
         * actually contain 4.
         */
        //先引用相关注释
        //需要注意的是这是put函数调用的base情况，这里不需要判断这个key是否真的存在于这个leaf page上直接返回即可

        return this;
    }

    // See BPlusNode.getLeftmostLeaf.
    @Override
    public LeafNode getLeftmostLeaf() {
        // TODO(proj2): implement
        return this;
    }

    // See BPlusNode.put.
    @Override
    public Optional<Pair<DataBox, Long>> put(DataBox key, RecordId rid) {
        // TODO(proj2): implement
        //在leafNode上插入一对key rid键值对
        //我们需要做以下几件事
        //1.找到插入的位置，判断是否有相同的key，若有雷同，抛出错误
        //2.判断key的数量是否达到了2d+1,达到了需要进行分裂操作
        //3.若不分裂直接返回option.empty，若分裂后需要向inner节点返回(split key, right-leafNode-pageNum)
        //4. 调用sync函数同步写入磁盘中
        if(this.getKey(key).isPresent())throw new BPlusTreeException(String.format("key: %s already existed", key.toString()));
        //二分法找到第一个比它的大的值
        int pos = this.BinarySearch(key);
//        System.out.println(key+" "+pos);
        keys.add(pos,key);
        rids.add(pos,rid);
        //判断是否需要分裂leafNode， 即key的数量是否超过了2d
        int d = this.metadata.getOrder();
        if(keys.size()==d*2+1){
            //先分出右边的d+1个元素
            List<DataBox> newKeys = new ArrayList<>(keys.subList(d,2*d+1));
            List<RecordId> newRids = new ArrayList<>(rids.subList(d,2*d+1));
            //然后将原先的keys和rids删除分离出去的元素
            keys.removeAll(newKeys);
            rids.removeAll(newRids);
            //根据这些newKeys和newRids生成新的leafNode
            LeafNode newLeafNode = new LeafNode(metadata, bufferManager, newKeys, newRids, this.rightSibling, treeContext);

            //重置leafpage的rightsibling
            this.rightSibling = Optional.of(newLeafNode.page.getPageNum());
            //写入磁盘
            this.sync();
            //返回split key以及newLeafNode 的pageNum
            return Optional.of(new Pair<>(newLeafNode.getKeys().get(0),newLeafNode.page.getPageNum() ));
        }
        //若不用分裂，写入磁盘后直接返回empty
        this.sync();
        return Optional.empty();
    }

    // See BPlusNode.bulkLoad.
    @Override
    public Optional<Pair<DataBox, Long>> bulkLoad(Iterator<Pair<DataBox, RecordId>> data,
            float fillFactor) {
        // TODO(proj2): implement
        //根据fillfactor和d计算leafNode的最大容纳量
        int fullNum = (int)Math.ceil(2*this.metadata.getOrder()*fillFactor);
        Pair<DataBox, RecordId> pair = data.next();
        //判断当前keys是否达到了fullNum
        if(this.getKeys().size()<fullNum){
            //插入新的key和recordId
            this.getKeys().add(pair.getFirst());
            this.getRids().add(pair.getSecond());
            sync();
            return Optional.empty();
        }
        //当前keys达到了fullNum需要增加新的leafNode
        //注意新的leafNode只插入一个(key,rid)
        List<DataBox> newKeys = new ArrayList<>();
        List<RecordId> newRids = new ArrayList<>();
        newKeys.add(pair.getFirst());
        newRids.add(pair.getSecond());
        //创建新的leafNode
        LeafNode newLeafNode = new LeafNode(metadata, bufferManager, newKeys, newRids, this.rightSibling, treeContext);
        //改动当前的leafNode的rightSibling
        this.rightSibling = Optional.of(newLeafNode.getPage().getPageNum());
        //写入磁盘
        sync();
        return Optional.of(new Pair<DataBox,Long>(pair.getFirst(),this.rightSibling.get()));
    }

    // See BPlusNode.remove.
    @Override
    public void remove(DataBox key) {
        // TODO(proj2): implement
        //找到对应的key的下标删除即可
        int index = keys.indexOf(key);
        keys.remove(index);
        rids.remove(index);
        //写回磁盘
        sync();

        return;
    }

    // Iterators ///////////////////////////////////////////////////////////////
    /** Return the record id associated with `key`. */
    Optional<RecordId> getKey(DataBox key) {
        int index = keys.indexOf(key);
        return index == -1 ? Optional.empty() : Optional.of(rids.get(index));
    }

    /**
     * Returns an iterator over the record ids of this leaf in ascending order of
     * their corresponding keys.
     */
    Iterator<RecordId> scanAll() {
        return rids.iterator();
    }

    /**
     * Returns an iterator over the record ids of this leaf that have a
     * corresponding key greater than or equal to `key`. The record ids are
     * returned in ascending order of their corresponding keys.
     */
    Iterator<RecordId> scanGreaterEqual(DataBox key) {
        int index = InnerNode.numLessThan(key, keys);
        return rids.subList(index, rids.size()).iterator();
    }

    // Helpers /////////////////////////////////////////////////////////////////
    @Override
    public Page getPage() {
        return page;
    }

    /** Returns the right sibling of this leaf, if it has one. */
    Optional<LeafNode> getRightSibling() {
        if (!rightSibling.isPresent()) {
            return Optional.empty();
        }

        long pageNum = rightSibling.get();
        return Optional.of(LeafNode.fromBytes(metadata, bufferManager, treeContext, pageNum));
    }

    /** Serializes this leaf to its page. */
    private void sync() {
        page.pin();
        try {
            Buffer b = page.getBuffer();
            byte[] newBytes = toBytes();
            byte[] bytes = new byte[newBytes.length];
            b.get(bytes);
            if (!Arrays.equals(bytes, newBytes)) {
                page.getBuffer().put(toBytes());
            }
        } finally {
            page.unpin();
        }
    }

    // Just for testing.
    List<DataBox> getKeys() {
        return keys;
    }

    // Just for testing.
    List<RecordId> getRids() {
        return rids;
    }

    /**
     * Returns the largest number d such that the serialization of a LeafNode
     * with 2d entries will fit on a single page.
     */
    static int maxOrder(short pageSize, Type keySchema) {
        // A leaf node with n entries takes up the following number of bytes:
        //
        //   1 + 8 + 4 + n * (keySize + ridSize)
        //
        // where
        //
        //   - 1 is the number of bytes used to store isLeaf,
        //   - 8 is the number of bytes used to store a sibling pointer,
        //   - 4 is the number of bytes used to store n,
        //   - keySize is the number of bytes used to store a DataBox of type
        //     keySchema, and
        //   - ridSize is the number of bytes of a RecordId.
        //
        // Solving the following equation
        //
        //   n * (keySize + ridSize) + 13 <= pageSizeInBytes
        //
        // we get
        //
        //   n = (pageSizeInBytes - 13) / (keySize + ridSize)
        //
        // The order d is half of n.
        int keySize = keySchema.getSizeInBytes();
        int ridSize = RecordId.getSizeInBytes();
        int n = (pageSize - 13) / (keySize + ridSize);
        return n / 2;
    }

    // Pretty Printing /////////////////////////////////////////////////////////
    @Override
    public String toString() {
        String rightSibString = rightSibling.map(Object::toString).orElse("None");
        return String.format("LeafNode(pageNum=%s, keys=%s, rids=%s, rightSibling=%s)",
                page.getPageNum(), keys, rids, rightSibString);
    }

    @Override
    public String toSexp() {
        List<String> ss = new ArrayList<>();
        for (int i = 0; i < keys.size(); ++i) {
            String key = keys.get(i).toString();
            String rid = rids.get(i).toSexp();
            ss.add(String.format("(%s %s)", key, rid));
        }
        return String.format("(%s)", String.join(" ", ss));
    }

    /**
     * Given a leaf with page number 1 and three (key, rid) pairs (0, (0, 0)),
     * (1, (1, 1)), and (2, (2, 2)), the corresponding dot fragment is:
     *
     *   node1[label = "{0: (0 0)|1: (1 1)|2: (2 2)}"];
     */
    @Override
    public String toDot() {
        List<String> ss = new ArrayList<>();
        for (int i = 0; i < keys.size(); ++i) {
            ss.add(String.format("%s: %s", keys.get(i), rids.get(i).toSexp()));
        }
        long pageNum = getPage().getPageNum();
        String s = String.join("|", ss);
        return String.format("  node%d[label = \"{%s}\"];", pageNum, s);
    }

    // Serialization ///////////////////////////////////////////////////////////
    @Override
    public byte[] toBytes() {
        // When we serialize a leaf node, we write:
        //
        //   a. the literal value 1 (1 byte) which indicates that this node is a
        //      leaf node,
        //   b. the page id (8 bytes) of our right sibling (or -1 if we don't have
        //      a right sibling),
        //   c. the number (4 bytes) of (key, rid) pairs this leaf node contains,
        //      and
        //   d. the (key, rid) pairs themselves.
        //
        // For example, the following bytes:
        //
        //   +----+-------------------------+-------------+----+-------------------------------+
        //   | 01 | 00 00 00 00 00 00 00 04 | 00 00 00 01 | 03 | 00 00 00 00 00 00 00 03 00 01 |
        //   +----+-------------------------+-------------+----+-------------------------------+
        //    \__/ \_______________________/ \___________/ \__________________________________/
        //     a               b                   c                         d
        //
        // represent a leaf node with sibling on page 4 and a single (key, rid)
        // pair with key 3 and page id (3, 1).

        assert (keys.size() == rids.size());
        assert (keys.size() <= 2 * metadata.getOrder());

        // All sizes are in bytes.
        int isLeafSize = 1;
        int siblingSize = Long.BYTES;
        int lenSize = Integer.BYTES;
        int keySize = metadata.getKeySchema().getSizeInBytes();
        int ridSize = RecordId.getSizeInBytes();
        int entriesSize = (keySize + ridSize) * keys.size();
        int size = isLeafSize + siblingSize + lenSize + entriesSize;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 1);
        buf.putLong(rightSibling.orElse(-1L));
        buf.putInt(keys.size());
        for (int i = 0; i < keys.size(); ++i) {
            buf.put(keys.get(i).toBytes());
            buf.put(rids.get(i).toBytes());
        }
        return buf.array();
    }

    /**
     * Loads a leaf node from page `pageNum`.
     */
    public static LeafNode fromBytes(BPlusTreeMetadata metadata, BufferManager bufferManager,
                                     LockContext treeContext, long pageNum) {
        // TODO(proj2): implement
        // Note: LeafNode has two constructors. To implement fromBytes be sure to
        // use the constructor that reuses an existing page instead of fetching a
        // brand new one.
        /*
        LeafNode(BPlusTreeMetadata metadata, BufferManager bufferManager, Page page,
                     List<DataBox> keys,
                     List<RecordId> rids, Optional<Long> rightSibling, LockContext treeContext)
         */
        //已知pageNum需要从bufferManager中取出这个page中的内容，根据构造器
        // 其中metadata，bufferManager，treeContext已经有了
        // 还需要得到这个page中存储的keys，rids，rightsibling，page
        //首先，根据bufferManager获取page和buf

        Page page = bufferManager.fetchPage(treeContext, pageNum);
        Buffer buf = page.getBuffer();
        //然后根据序列化的顺序依次取出相应的值的byte值，引用以下前面的注释
        // When we serialize a leaf node, we write:
        //
        //   a. the literal value 1 (1 byte) which indicates that this node is a
        //      leaf node,
        //   b. the page id (8 bytes) of our right sibling (or -1 if we don't have
        //      a right sibling),
        //   c. the number (4 bytes) of (key, rid) pairs this leaf node contains,
        //      and
        //   d. the (key, rid) pairs themselves.
        //
        // For example, the following bytes:
        //
        //   +----+-------------------------+-------------+----+-------------------------------+
        //   | 01 | 00 00 00 00 00 00 00 04 | 00 00 00 01 | 03 | 00 00 00 00 00 00 00 03 00 01 |
        //   +----+-------------------------+-------------+----+-------------------------------+
        //    \__/ \_______________________/ \___________/ \__________________________________/
        //     a               b                   c                         d
        byte nodeType = buf.get();
        //叶子节点的nodetype区域应为1
        assert nodeType ==(byte)1;
        //取出右兄弟节点的page id
        //若id=-1说明没有rightSibling,否则说明有
        Long pid = buf.getLong();
        Optional<Long> rightSibling =pid==-1L? Optional.empty():Optional.of(pid);
        //取出leaf上（key，rid）键值对的个数
        int n = buf.getInt();
        //取出该leaf上keys和rids
        List<DataBox> keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();
        //需要注意的是leafnode上面的key和rid是成对出现的，和inner node不同
        for(int i=0;i<n;i++){
            keys.add(DataBox.fromBytes(buf,metadata.getKeySchema()));
            rids.add(RecordId.fromBytes(buf));
        }
        //使用正确的构造器返回相应的leafNode
        return new LeafNode(metadata, bufferManager, page, keys, rids, rightSibling, treeContext);
    }
    //helper //////////////////////////////////////
    //返回keys中第一个大于等于key的下标
    public Integer BinarySearch(DataBox key){
        int lft = 0,rgt = keys.size();

        while(lft<rgt){
            int mid = (lft+rgt)/2;
            DataBox midKey = keys.get(mid);
            if(midKey.compareTo(key)>0)rgt = mid;
            else lft = mid+1;
        }
        return lft;

    }

    // Builtins ////////////////////////////////////////////////////////////////
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LeafNode)) {
            return false;
        }
        LeafNode n = (LeafNode) o;
        return page.getPageNum() == n.page.getPageNum() &&
               keys.equals(n.keys) &&
               rids.equals(n.rids) &&
               rightSibling.equals(n.rightSibling);
    }

    @Override
    public int hashCode() {
        return Objects.hash(page.getPageNum(), keys, rids, rightSibling);
    }

}
