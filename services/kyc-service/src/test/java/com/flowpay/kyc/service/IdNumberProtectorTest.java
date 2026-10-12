package com.flowpay.kyc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowpay.kyc.configuration.KycProperties;
import com.flowpay.kyc.entity.IdType;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class IdNumberProtectorTest {

    static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    final IdNumberProtector protector = new IdNumberProtector(new KycProperties(newKey(), "NGN", null));

    @Test
    void encryptedValueIsNotReadable_andDecryptsBackToTheOriginal() {
        String stored = protector.encrypt("12345678901");

        assertThat(stored).doesNotContain("12345678901");
        assertThat(protector.decrypt(stored)).isEqualTo("12345678901");
    }

    @Test
    void everyEncryptionUsesAFreshNonce_soEqualNumbersLookDifferent() {
        assertThat(protector.encrypt("12345678901")).isNotEqualTo(protector.encrypt("12345678901"));
    }

    @Test
    void tamperedOrWrongKeyCiphertextIsRejected() {
        String stored = protector.encrypt("12345678901");
        byte[] bytes = Base64.getDecoder().decode(stored);
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> protector.decrypt(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
        var otherKey = new IdNumberProtector(new KycProperties(newKey(), "NGN", null));
        assertThatThrownBy(() -> otherKey.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fingerprintIsStableForTheSameIdAndDiffersByTypeNumberAndKey() {
        String a = protector.fingerprint(IdType.NIN, "12345678901");

        assertThat(protector.fingerprint(IdType.NIN, "12345678901")).isEqualTo(a);
        assertThat(protector.fingerprint(IdType.BVN, "12345678901")).isNotEqualTo(a);
        assertThat(protector.fingerprint(IdType.NIN, "12345678902")).isNotEqualTo(a);
        assertThat(new IdNumberProtector(new KycProperties(newKey(), "NGN", null))
                .fingerprint(IdType.NIN, "12345678901")).as("keyed: another key gives another hash").isNotEqualTo(a);
        assertThat(a).hasSize(64).doesNotContain("12345678901");
    }

    @Test
    void normalisationIgnoresCaseSpacesAndDashes_andMaskingShowsOnlyTheLastFour() {
        assertThat(IdNumberProtector.normalise("a-123 456")).isEqualTo("A123456");
        assertThat(IdNumberProtector.last4("12345678901")).isEqualTo("8901");
        assertThat(IdNumberProtector.mask("8901")).isEqualTo("*******8901");
    }

    @Test
    void aMissingOrShortKeyStopsStartup() {
        assertThatThrownBy(() -> new IdNumberProtector(new KycProperties(null, "NGN", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdNumberProtector(new KycProperties(
                Base64.getEncoder().encodeToString(new byte[8]), "NGN", null)))
                .isInstanceOf(IllegalStateException.class);
    }
}
