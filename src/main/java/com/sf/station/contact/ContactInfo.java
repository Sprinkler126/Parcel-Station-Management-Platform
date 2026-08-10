package com.sf.station.contact;

/**
 * 联系方式解析结果。
 *
 * @param contactNo    原始面单号码，用于联系
 * @param contactType  形态
 * @param realSuffix   真实后四位，用于身份识别与检索。AXB 单未补录时为 null
 * @param suffixSource 尾号来源
 */
public record ContactInfo(String contactNo, ContactType contactType,
                          String realSuffix, SuffixSource suffixSource) {

    /** 是否待补录尾号：虚拟号入库时客户尾号未知，检索通道缺失 */
    public boolean needsSuffixPatch() {
        return realSuffix == null || realSuffix.isBlank();
    }
}
