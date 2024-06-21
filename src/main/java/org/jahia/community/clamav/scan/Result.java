package org.jahia.community.clamav.scan;

public class Result {

    private Status status;
    private String output;
    private String signature;

    public Result() {
        super();
    }

    public Result(Status status, String output) {
        super();
        this.status = status;
        this.output = output;
    }

    public Result(Status status, String output, String signature) {
        super();
        this.status = status;
        this.output = output;
        this.signature = signature;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
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
