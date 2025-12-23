# Overview

[← Back to docs index](README.md)

PayFlo is a payment gateway — software that lets a business accept payments online without building
all the plumbing themselves. It handles taking the payment, keeping a record of every order and refund,
storing card details safely, letting the business's own systems know when something happens, and paying
the business out on a regular schedule. The entity/domain layer is fully built (see Entities below), and
a working API now covers merchant onboarding/auth, the order and payment lifecycle, card vaulting, and
signed webhook delivery — see [Project Status](status.md) for exactly what's built vs. still
planned, and the [Phase 1 → Phase 2 handoff](status.md#phase-1--phase-2-handoff) below the status table for what's
being carried into the microservices split.
