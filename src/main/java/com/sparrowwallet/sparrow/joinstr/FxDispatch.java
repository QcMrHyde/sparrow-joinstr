package com.sparrowwallet.sparrow.joinstr;

import javafx.application.Platform;

/**
 * Runs a task on the JavaFX thread, or inline when there is no toolkit. The coinjoin phases are
 * driven from relay callbacks and report progress to the UI, so they need to marshal, but the
 * same code has to be runnable without a toolkit to be testable.
 */
public final class FxDispatch {

    private FxDispatch() {
    }

    public static void run(Runnable runnable) {
        if (runnable == null) {
            return;
        }

        try {
            if (Platform.isFxApplicationThread()) {
                runnable.run();
            } else {
                Platform.runLater(runnable);
            }
        } catch (IllegalStateException e) {
            // no toolkit, so there is no UI thread to marshal onto
            runnable.run();
        }
    }
}
