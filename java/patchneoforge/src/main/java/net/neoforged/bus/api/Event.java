package net.neoforged.bus.api;

public abstract class Event {

    public enum Result {
        DENY,
        DEFAULT,
        ALLOW
    }

    private boolean isCanceled = false;
    private Result result = Result.DEFAULT;

    public boolean isCancelable() {
        return this instanceof ICancellableEvent;
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    public void setCanceled(boolean cancel) {
        if (!isCancelable()) {
            throw new UnsupportedOperationException("Attempted to cancel an event that is not cancelable: " + getClass().getName());
        }
        this.isCanceled = cancel;
    }

    public boolean hasResult() {
        return result != Result.DEFAULT;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }
}
