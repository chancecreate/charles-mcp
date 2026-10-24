package io.github.charlesmcp.agent.instrumentation;

/**
 * Maps {@code com.xk72.proxy.ProxyEventStatus#getRawValue()} to stable labels.
 *
 * <p>The enum constants are obfuscated (a..o) but their raw values and Charles's own display strings
 * (from {@code Transaction.a(ProxyEventStatus)}) are stable evidence; see
 * docs/reverse-engineering-findings.md.
 */
public final class StatusLabels {
    private StatusLabels() {
    }

    public static String label(int raw) {
        switch (raw) {
            case 0:
                return "initialising";
            case 5:
            case 10:
            case 20:
                return "sending_request_header";
            case 23:
                return "resolving_remote_host";
            case 24:
                return "connecting_to_remote_host";
            case 25:
                return "connected_to_remote_host";
            case 30:
                return "sending_request_body";
            case 40:
                return "waiting_for_response";
            case 50:
                return "waiting_for_response_body";
            case 60:
                return "receiving_response_body";
            case 70:
                return "received_response_body";
            case 80:
                return "complete";
            case 90:
                return "failed";
            case 100:
                return "blocked";
            default:
                return raw < 0 ? null : "unknown_" + raw;
        }
    }
}
