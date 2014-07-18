package com.addthis.hydra.data.tree.prop;

import com.addthis.basis.util.Varint;

import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.core.BundleField;
import com.addthis.bundle.value.ValueObject;
import com.addthis.codec.annotations.FieldConfig;
import com.addthis.hydra.data.tree.DataTreeNode;
import com.addthis.hydra.data.tree.DataTreeNodeUpdater;
import com.addthis.hydra.data.tree.TreeDataParameters;
import com.addthis.hydra.data.tree.TreeNodeData;

import com.google.common.annotations.VisibleForTesting;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

public class DataCountingMin extends TreeNodeData<DataCountingMin.Config> {

    @FieldConfig(codable = true)
    private DataCounting bloomFilter;

    @FieldConfig(codable = true)
    private DataCountMinSketch countMinSketch;

    private BundleField keyAccess;

    public DataCountingMin() {

    }

    public DataCountingMin(DataCounting bloomFilter, DataCountMinSketch countMinSketch) {
        this.bloomFilter = bloomFilter;
        this.countMinSketch = countMinSketch;
    }

    /**
     * @user-reference
     * @hydra-name count.min
     **/
    public static final class Config extends TreeDataParameters<DataCountingMin> {

        /**
         * Field to count or estimate cardinalities for. This field is required.
         */
        @FieldConfig(codable = true, required = true)
        String key;

        /**
         * Minimum cardinality for storing in the bloom filter. This field is required.
         */
        int minimum;

        /**
         * <pre>
         * Which version of a counter to use:
         * ll   : log
         * lc   : linear
         * ce   : countest
         * ac   : adaptive
         * hll  : hyper loglog
         * ceh  : countest hll
         * hllp : hyper loglog plus
         * cehp : countest hllp
         *
         * Default is hllp (hyper loglog plus). Failure to use a recognized type will result in errors.
         * </pre>
         */
        @FieldConfig(codable = true)
        String ver = "hllp";

        /**
         * Maximum error tolerated in count.min.sketch
         * as percentage of cardinality. This field is required.
         */
        @FieldConfig(codable = true, required = true)
        double percentage;

        /**
         * Confidence that the error tolerance is satisfied
         * in count.min.sketch. Expressed as a fraction.
         * Default is 0.99995.
         */
        double confidence = 0.99995;

        /**
         * Passed to ac, lc, and ll as k. Default is 0.
         */
        @FieldConfig(codable = true)
        int size;

        /**
         * Used for ac, ce, and lc. Setting this to >=0 causes the size field to be ignored when applicable.
         * This is the maximum cardinality under which a one percent error rate is likely.
         */
        @FieldConfig(codable = true)
        int max = -1;

        /**
         * Used for ce. The point at which exact counting gives way to estimation. The default is 100.
         */
        @FieldConfig(codable = true)
        int tip = 100;

        /**
         * Used for hll and ceh. The relative standard deviation for the counter -- smaller deviations
         * require more space. The default is 0.05.
         */
        @FieldConfig(codable = true)
        double rsd = 0.05;

        /**
         * Used in hyperloglog plus (hllp).  The precision is the number of bits used when the cardinality
         * is calculated using the normal mode. The default is 14.
         */
        @FieldConfig(codable = true)
        int p = 14;

        /**
         * Used in hyperloglog plus (hllp).  The sparse precision is the number of bits used when the cardinality
         * is calculated using the sparse mode. The default is 25.
         */
        @FieldConfig(codable = true)
        int sp = 25;

        @Override
        public DataCountingMin newInstance() {
            DataCounting.Config countConfig = new DataCounting.Config();
            DataCountMinSketch.Config sketchConfig = new DataCountMinSketch.Config();
            countConfig.setKey(key);
            countConfig.setVer(ver);
            countConfig.setSize(size);
            countConfig.setMax(max);
            countConfig.setTip(tip);
            countConfig.setRsd(rsd);
            countConfig.setP(p);
            countConfig.setSp(sp);
            sketchConfig.setKey(key);
            sketchConfig.setPercentage(percentage);
            sketchConfig.setConfidence(confidence);
            return new DataCountingMin(countConfig.newInstance(), sketchConfig.newInstance());
        }
    }

    @Override
    public byte[] bytesEncode(long version) {
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer();
        try {
            byte[] next;
            next = bloomFilter.bytesEncode(version);
            Varint.writeUnsignedVarInt(next.length, buffer);
            buffer.writeBytes(next);
            next = countMinSketch.bytesEncode(version);
            Varint.writeUnsignedVarInt(next.length, buffer);
            buffer.writeBytes(next);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    @Override
    public void bytesDecode(byte[] b, long version) {
        ByteBuf buffer = Unpooled.wrappedBuffer(b);
        try {
            byte[] next;
            int length;
            length = Varint.readUnsignedVarInt(buffer);
            next = buffer.readBytes(length).array();
            bloomFilter.bytesDecode(next, version);
            length = Varint.readUnsignedVarInt(buffer);
            next = buffer.readBytes(length).array();
            countMinSketch.bytesDecode(next, version);
        } finally {
            buffer.release();
        }
    }

    @Override
    public boolean updateChildData(DataTreeNodeUpdater state, DataTreeNode childNode,
            DataCountingMin.Config conf) {
        Bundle p = state.getBundle();
        if (keyAccess == null) {
            keyAccess = p.getFormat().getField(conf.key);
        }
        String item = p.getValue(keyAccess).toString();
        add(item, conf.minimum);
        return true;
    }

    @VisibleForTesting
    public void add(String item, int minimum) {
        long count = countMinSketch.estimateCount(item);
        if ((count + 1) < minimum) {
            countMinSketch.add(item, 1);
        } else {
            bloomFilter.offer(item);
        }
    }

    @VisibleForTesting
    long count() {
        return bloomFilter.count();
    }


    @Override
    public ValueObject getValue(String key) {
        return bloomFilter.getValue(key);
    }


}
