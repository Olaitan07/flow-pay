package com.flowpay.kyc.entity;

public enum IdType {
    NIN(true),
    BVN(true),
    PASSPORT(false),
    DRIVERS_LICENSE(false),
    NATIONAL_ID(false);

    private final boolean elevenDigits;

    IdType(boolean elevenDigits) {
        this.elevenDigits = elevenDigits;
    }

    /** NIN and BVN are exactly 11 digits. */
    public boolean requiresElevenDigits() {
        return elevenDigits;
    }
}
