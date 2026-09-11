# Resetting Local Data

Starts everything over from nothing: every database, the Redis cache and queues, and the Kafka topics.

> **This is destructive and can't be undone.** It deletes every local merchant, API key, order, payment, vaulted card, webhook delivery and settlement.

1. **Stop every service.** Nothing else may be connected to PostgreSQL.
2. **Recreate the infrastructure from empty volumes** (from the repository root):

   ```bash
   docker compose -f services.docker-compose.yaml down -v
   docker compose -f services.docker-compose.yaml up -d
   ```

3. **Recreate the four databases** — they aren't created by the compose file; see [setup](setup.md#2-create-the-four-databases).
4. **Start the stack again** in the [usual order](setup.md#4-start-the-services-in-order). Hibernate recreates every table on first start.

## Notes

- Clearing only Redis (`FLUSHALL`) is safe while services are stopped, but it also drops the webhook retry queue — rows whose delivery was queued are picked up again by the 10-second reconciliation pass, since `next_retry_at` is also kept in PostgreSQL.
- An API key cached in Redis keeps authenticating for up to 5 minutes after its row is deleted, so reset Redis together with the databases.
- Because the schema is `ddl-auto: update`, a column removed from an entity is **not** dropped from an existing database. Resetting is the only way to get a clean schema after such a change.
