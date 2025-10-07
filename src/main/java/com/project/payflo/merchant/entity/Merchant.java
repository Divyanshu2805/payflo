package com.project.payflo.merchant.entity;

import com.project.payflo.common.entity.BaseEntity;
import com.project.payflo.common.enums.BusinessType;
import com.project.payflo.common.enums.MerchantStatus;
import jakarta.persistence.*;
import lombok.*;
import tools.jackson.core.ObjectReadContext;

import java.util.UUID;

@Entity
@Table(name = "merchant", indexes = {
        @Index(name = "idx_merchant_status", columnList = "status")
})
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Merchant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(unique = true, nullable = false, length = 200)
    private String email;

    @Column(length = 20)
    private String contactNumber;

    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private BusinessType businessType;

    @Column(length = 200)
    private String businessName;

    @Column(length = 200)
    private String websiteUrl;

    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MerchantStatus status = MerchantStatus.PENDING_KYC;

    @Column(length = 20)
    private String gstId;

    @Column(length = 20)
    private String panId;

    @Column(length = 200)
    private String settlementBankAccount;

    @Column(length = 20)
    private String settlementBankIfsc;

    @Column(length = 200)
    private String settlementBankAccountHolderName;
}
