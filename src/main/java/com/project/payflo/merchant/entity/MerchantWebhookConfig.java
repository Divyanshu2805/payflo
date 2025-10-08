package com.project.payflo.merchant.entity;

import com.project.payflo.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "merchant_webhook_config",
    indexes = {
        @Index(name = "idx_webhook_merchant_id", columnList = "merchant_id, enabled")
    }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MerchantWebhookConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(nullable = false, length = 500)
    private String targetUrl; //www.zara.com/webhook/success

    @Column(length = 255)
    private String webhookSecret;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(length = 255)
    private String eventTypes;
    // Comma-separated list of event types to subscribe to
    
}
