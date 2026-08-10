package com.sf.station.contact;

import java.text.Normalizer;

/** 号码字面归一化：全角转半角、去空白与常见分隔符。纯函数。 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {
    }

    /** 轻度归一化：NFKC 折叠全角、去空白、统一全角逗号。保留 * - 等掩码字符 */
    public static String stripBlank(String raw) {
        if (raw == null) {
            return null;
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u3000]", "")
                .replace('，', ',')
                .trim();
    }

    /** 完全归一化：在 stripBlank 基础上再去掉连字符，供真实号与虚拟号判定 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return stripBlank(raw).replace("-", "");
    }

    /**
     * 脱敏展示：真实号与掩码号统一显示为 138****5678；
     * 虚拟号保留原样（本就不是客户真实号，且分机号是取件必需信息）。
     */
    public static String mask(String contactNo, ContactType type) {
        if (contactNo == null || contactNo.isBlank()) {
            return "";
        }
        if (type == ContactType.VIRTUAL) {
            return contactNo;
        }
        String digits = contactNo.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return contactNo;
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }
}
