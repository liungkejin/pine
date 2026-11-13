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
            String directory = getTargetContext().getApplicationInfo().nativeLibraryDir;
            String detail = "";
            if (scenario.startsWith("gc_")) {
                detail = GcReferenceTest.run(directory, scenario);
            } else if (scenario.startsWith("typed_")) {
                detail = TypedBridgeTest.run(directory, scenario.equals("typed_inline"), scenario.equals("typed_jit"));
            } else {
                InitializationTest.main(new String[] {directory, scenario});
            }
            result.putString("stream", "PASS " + scenario + " " + detail + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + scenario + ": " + android.util.Log.getStackTraceString(failure) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
