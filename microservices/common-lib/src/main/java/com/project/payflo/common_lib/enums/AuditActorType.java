package com.project.payflo.common_lib.enums;

/** Who did a recorded action. Stored by name in {@code audit_log.actor_type}. */
public enum AuditActorType {
    /** A dashboard login; the actor is their email. */
    USER,
    /** An API key; the actor is its public key id. */
    API_KEY,
    /** The platform operator, through the admin API. */
    PLATFORM_ADMIN,
    /** The platform itself, with no request behind it. */
    SYSTEM
}
