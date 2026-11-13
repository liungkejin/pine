package top.canyie.pine.regression;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

public class RegressionInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        String scenario = arguments.getString("scenario", "normal");
        Bundle result = new Bundle();
        try {
            InitializationTest.main(new String[] {
                    getTargetContext().getApplicationInfo().nativeLibraryDir, scenario});
            result.putString("stream", "PASS initialization " + scenario + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL initialization " + scenario + ": " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
