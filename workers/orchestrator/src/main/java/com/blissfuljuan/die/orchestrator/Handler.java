package com.blissfuljuan.die.orchestrator;

import java.util.List;

/** Business step of one queue. Pure: returns what to publish, performs no I/O itself. */
public interface Handler {

    /** A message to publish and where. */
    record Outgoing(Topology.Destination destination, Envelope envelope) {}

    /** Thrown when retrying can never help (wrong type, malformed payload): dead-letter immediately. */
    class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }

    /** Messages to publish, in order. All are confirmed by the broker before the delivery is acknowledged. */
    List<Outgoing> handle(Envelope in);

    /** Messages to publish when the delivery is given up on (retries exhausted or permanent), so the API's job fails visibly. */
    List<Outgoing> onGiveUp(Envelope in, String reason);
}
