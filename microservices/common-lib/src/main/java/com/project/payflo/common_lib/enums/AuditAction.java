package com.project.payflo.common_lib.enums;

/**
 * The sensitive actions the audit log records. Stored by name in {@code audit_log.action}, whose check constraint
 * lists them: a new constant needs a migration that widens it.
 */
public enum AuditAction {
    // money and identity
    SETTLEMENT_BANK_CHANGED,
    KYC_VERIFIED,
    PASSWORD_CHANGED,

    // credentials
    API_KEY_CREATED,
    API_KEY_REVOKED,
    API_KEY_ROTATED,
    WEBHOOK_CONFIG_CREATED,
    WEBHOOK_CONFIG_UPDATED,
    WEBHOOK_CONFIG_DELETED,
    WEBHOOK_SECRET_ROTATED,

    // who can do what
    USER_ADDED,
    USER_ROLE_CHANGED,
    USER_REMOVED,

    // the platform operator's actions
    MERCHANT_SUSPENDED,
    MERCHANT_REACTIVATED,
    SETTLEMENT_RUN_TRIGGERED
}
