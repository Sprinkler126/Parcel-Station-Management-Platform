package com.sf.station.code.domain;

/**
 * 取件码的规范表示，是入库流程内部唯一允许流通的码表示。
 * DTO 里的字符串一律先经 {@link PickupCodeNormalizer} 转换。
 *
 * @param fullCode 归一化后的完整码，如 15-1-731
 * @param prefix   排前缀，如 15-1
 * @param seq      排内序号，如 731
 */
public record PickupCodeVO(String fullCode, String prefix, int seq) {

    public static PickupCodeVO of(String prefix, int seq) {
        return new PickupCodeVO(prefix + "-" + seq, prefix, seq);
    }
}
