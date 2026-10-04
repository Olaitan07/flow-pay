package com.flowpay.kyc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One verification attempt for one tier. The status is changed only through the repository's conditional
 * update, so two reviewers deciding at once cannot both succeed.
 */
@Entity
@Table(name = "kyc_submissions")
public class KycSubmission {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private int tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycStatus status;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;

    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "address_country")
    private String addressCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type")
    private IdType idType;

    @Column(name = "id_number_encrypted")
    private String idNumberEncrypted;

    @Column(name = "id_number_hash")
    private String idNumberHash;

    @Column(name = "id_number_last4")
    private String idNumberLast4;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_reason")
    private String decisionReason;

    protected KycSubmission() {
    }

    private KycSubmission(UUID customerId, int tier, KycStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.tier = tier;
        this.status = status;
        this.submittedAt = now;
    }

    public static KycSubmission tierOne(UUID customerId, LocalDate dateOfBirth, String addressLine1,
                                        String addressLine2, String city, String state, String postalCode,
                                        String country, Instant now) {
        KycSubmission s = new KycSubmission(customerId, 1, KycStatus.PENDING, now);
        s.dateOfBirth = dateOfBirth;
        s.addressLine1 = addressLine1;
        s.addressLine2 = addressLine2;
        s.city = city;
        s.state = state;
        s.postalCode = postalCode;
        s.addressCountry = country;
        return s;
    }

    public static KycSubmission tierTwo(UUID customerId, IdType idType, String encryptedNumber, String numberHash,
                                        String last4, boolean flagged, Instant now) {
        KycSubmission s = new KycSubmission(customerId, 2, flagged ? KycStatus.REQUIRES_REVIEW : KycStatus.PENDING, now);
        s.idType = idType;
        s.idNumberEncrypted = encryptedNumber;
        s.idNumberHash = numberHash;
        s.idNumberLast4 = last4;
        return s;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public int getTier() { return tier; }
    public KycStatus getStatus() { return status; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getAddressLine1() { return addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPostalCode() { return postalCode; }
    public String getAddressCountry() { return addressCountry; }
    public IdType getIdType() { return idType; }
    public String getIdNumberEncrypted() { return idNumberEncrypted; }
    public String getIdNumberHash() { return idNumberHash; }
    public String getIdNumberLast4() { return idNumberLast4; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionReason() { return decisionReason; }
}
