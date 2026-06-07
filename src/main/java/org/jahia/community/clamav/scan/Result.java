package org.jahia.community.clamav.scan;

public class Result {

    private final Status status;
    private final String output;
    private final String signature;

    public Result(Status status, String output) {
        this.status = status;
        this.output = output;
        this.signature = null;
    }

    public Result(Status status, String output, String signature) {
        this.status = status;
        this.output = output;
        this.signature = signature;
    }

    public Status getStatus() {
        return status;
    }

    public String getOutput() {
        return output;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Status: ");
        sb.append(getStatus());
        sb.append(System.lineSeparator());

        if (getOutput() != null && !getOutput().isEmpty()) {
            sb.append("Output: ");
            sb.append(getOutput());
            sb.append(System.lineSeparator());
        }

        if (getSignature() != null && !getSignature().isEmpty()) {
            sb.append("Signature: ");
            sb.append(getSignature());
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }
}
