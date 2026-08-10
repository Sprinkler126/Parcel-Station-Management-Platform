package com.sf.station.contact;

/** 联系号形态。隐私面单下手机号承担"联系"与"身份识别"双职责，必须拆分。 */
public enum ContactType {
    /** 真实号 13812345678 */
    REAL,
    /** 掩码号 138****5678 */
    MASKED,
    /** AXB 虚拟中间号 17012345678,8462，一单一号、签收后回收复用 */
    VIRTUAL
}
