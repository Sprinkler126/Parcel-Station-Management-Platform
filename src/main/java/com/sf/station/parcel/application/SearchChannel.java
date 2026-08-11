package com.sf.station.parcel.application;

import com.sf.station.code.domain.PickupCodeNormalizer;
import com.sf.station.common.BizException;
import java.util.regex.Pattern;

/**
 * 检索通道（文档 §8.5 分层检索）。
 *
 * <p>站员在同一个输入框里可能敲进四种东西：取件码、手机后四位、运单号、完整手机号。
 * 强迫他先选类型会打断作业动线，因此由输入形态自动判断。
 *
 * <p><b>为什么没有\"模糊搜索\"这个选项</b>：尾号检索若写成 {@code like '%1234'}，
 * 前缀不确定，B+ 树无法定位，必然全索引扫描。所有通道一律等值匹配走索引。
 */
public enum SearchChannel {

    /** 由输入形态自动判断 */
    AUTO,
    /** 取件码，形如 15-1-731 */
    PICKUP_CODE,
    /** 真实手机后四位，等值匹配走 idx_suffix */
    SUFFIX,
    /** 运单号 */
    TRACKING_NO,
    /** 完整联系号 */
    CONTACT_NO;

    private static final Pattern SUFFIX_P = Pattern.compile("^\\d{4}$");
    private static final Pattern CODE_P = Pattern.compile("^\\d{1,3}([-—–－_]\\d{1,4}){1,2}$");
    private static final Pattern PHONE_P = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 形态判定。顺序即优先级：取件码 → 后四位 → 联系号 → 运单号。
     *
     * <p>文档同时写了\"八位以上走运单号\"与\"十一位走联系号\"，两者对
     * 11 位纯数字是冲突的。此处以\"是否匹配大陆手机号\"作为判据消歧：
     * {@code ^1[3-9]\d{9}$} 命中即联系号，否则一律按运单号处理。
     * 运单号通常含字母或为 12~15 位，被误判的概率可忽略。
     */
    public static SearchChannel detect(String raw) {
        if (raw == null || raw.isBlank()) {
            return TRACKING_NO;
        }
        String s = raw.trim();
        if (CODE_P.matcher(s).matches()) {
            return PICKUP_CODE;
        }
        if (SUFFIX_P.matcher(s).matches()) {
            return SUFFIX;
        }
        if (PHONE_P.matcher(s).matches()) {
            return CONTACT_NO;
        }
        return TRACKING_NO;
    }

    /**
     * 按通道把原始输入归一化为可用于等值匹配的字面量。
     *
     * <p>取件码必须归一化后再比对：库里存的是 {@code 15-1-731}，
     * 站员敲的可能是 {@code １５－１－0731}，不归一化就查不到。
     */
    public String normalizeTerm(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (this == PICKUP_CODE) {
            try {
                return PickupCodeNormalizer.normalize(s).fullCode();
            } catch (BizException e) {
                // 归一化失败说明不是合法取件码，退回原样，查不到即返回空列表
                return s;
            }
        }
        return s;
    }
}
