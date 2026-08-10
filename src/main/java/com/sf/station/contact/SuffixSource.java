package com.sf.station.contact;

/** 真实尾号的来源，用于判断可信度与是否需要补录。 */
public enum SuffixSource {
    /** 由真实号直接截取 */
    DERIVED,
    /** 由掩码号尾段提取 */
    MASK,
    /** 由平台接口回传（本原型未接入，保留） */
    PLATFORM,
    /** 站员人工补录 */
    MANUAL
}
