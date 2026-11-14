package io.github.magnolett.checkoutbff.integration.persistence;

import java.time.Instant;

/**
 * An event waiting in the cart document to be published, written in the same update as the state change that raised
 * it. The payload is the JSON already in its published shape, so the relay only ships bytes.
 */
record OutboxEntry(String eventId, String eventType, Instant occurredAt, String payload) {
}
