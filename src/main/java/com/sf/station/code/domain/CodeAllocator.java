package com.sf.station.code.domain;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

/**
 * 码分配器：位图 + 游标 + next-fit（文档 §8.2）。
 *
 * <p><b>纯函数，不注入任何 Repository 和 Clock</b>，因此回绕、满位、跳号全部可用纯单测覆盖。
 *
 * <p>不写 gap-finding SQL（H2 与 MySQL 方言不通用且难调试）。一次索引覆盖扫描把该排
 * 占用号全捞出来，在内存求解。capacity 取 9999 时 BitSet 仅 1.25KB，
 * 几百个 int 的加载亚毫秒级，开销可忽略。
 *
 * <p><b>为什么是 next-fit 而不是 first-fit</b>：取最小空闲号会让站员回头找位置，
 * 打断流水作业；且刚释放的号立刻被复用，复用间隔过短，取件事故风险高。
 * next-fit 的复用距离约等于"空间上限 − 当前占用数"，天然提供了安全间隔。
 */
public final class CodeAllocator {

    private CodeAllocator() {
    }

    /**
     * next-fit：从游标下一位向后找第一个空位，到顶回绕至 1，转满一圈返回 empty。
     *
     * @param capacity 排内序号上限
     * @param cursor   当前游标位置（0 表示尚未分配过）
     * @param occupied 该排真正不可用的序号（在库中，或已出库但仍在冷却期内）
     */
    public static OptionalInt nextFit(int capacity, int cursor, Collection<Integer> occupied) {
        if (capacity <= 0) {
            return OptionalInt.empty();
        }
        BitSet used = toBitSet(capacity, occupied);
        return nextFit(capacity, cursor, used);
    }

    /**
     * 批量入库：一次取 N 个空位，避免 N 次往返。
     * 返回的列表长度可能小于 n（空间不足时尽力而为）。
     */
    public static List<Integer> nextFitBatch(int capacity, int cursor,
                                             Collection<Integer> occupied, int n) {
        List<Integer> result = new ArrayList<>(Math.max(0, n));
        if (capacity <= 0 || n <= 0) {
            return result;
        }
        BitSet used = toBitSet(capacity, occupied);
        int c = cursor;
        for (int i = 0; i < n; i++) {
            OptionalInt next = nextFit(capacity, c, used);
            if (next.isEmpty()) {
                break;
            }
            int seq = next.getAsInt();
            result.add(seq);
            used.set(seq);   // 占住，避免同批次内重复
            c = seq;
        }
        return result;
    }

    /** 在给定位图上执行一次 next-fit。调用方保证 capacity > 0。 */
    private static OptionalInt nextFit(int capacity, int cursor, BitSet used) {
        // 位图的 0 号位是哨兵（非真实槽位），统计占用数时必须扣除，
        // 否则 capacity-1 个占用会被误判为空间耗尽
        if (used.cardinality() - 1 >= capacity) {
            return OptionalInt.empty();
        }
        // 游标归一到 [0, capacity-1]，起点取其下一位；cursor=capacity 时起点回到 1
        int normalized = ((cursor % capacity) + capacity) % capacity;
        int start = normalized + 1;

        int seq = used.nextClearBit(start);
        if (seq > capacity) {
            seq = used.nextClearBit(1);      // 回绕
            if (seq > capacity) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(seq);
    }

    private static BitSet toBitSet(int capacity, Collection<Integer> occupied) {
        BitSet used = new BitSet(capacity + 2);
        // 0 号位不使用，先置位以免 nextClearBit 返回 0
        used.set(0);
        if (occupied != null) {
            for (Integer s : occupied) {
                if (s != null && s >= 1 && s <= capacity) {
                    used.set(s);
                }
            }
        }
        return used;
    }

    /**
     * 统计已占用数量（去重并裁剪到有效区间），供可用率计算复用同一份口径。
     */
    public static int countOccupied(int capacity, Collection<Integer> occupied) {
        BitSet used = toBitSet(capacity, occupied);
        used.clear(0);
        return used.cardinality();
    }
}
