// Demonstrates performing a Liveness Check.

// The FaceTec Device SDKs will cancel from the Progress Screen if onProgress() is not called for
// 60 seconds. This provides a failsafe for users getting stuck in the process because of a networking
// issue. If you would like to force users to stay on the Progress Screen for longer than 60 seconds,
// you can write code in the FaceScan or ID Scan Processor to call onProgress() via your own custom logic.
package com.reactnativefacetec.ZoomProcessors;

import android.content.Context;

import com.facetec.sdk.ZoomCustomization;
import com.facetec.sdk.FaceTecFaceScanProcessor;
import com.facetec.sdk.FaceTecFaceScanResultCallback;
import com.facetec.sdk.FaceTecSessionActivity;
import com.facetec.sdk.FaceTecSessionResult;
import com.facetec.sdk.FaceTecSessionStatus;

import org.json.JSONObject;

public class LivenessCheckProcessor extends Processor implements FaceTecFaceScanProcessor {
    FaceTecFaceScanResultCallback FaceTecFaceScanResultCallback;
    FaceTecSessionResult latestFaceTecSessionResult;
    SessionTokenSuccessCallback sessionTokenSuccessCallback;
    private boolean _isSuccess = false;

    public LivenessCheckProcessor(final Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
        this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
        NetworkingHelpers.getSessionToken(new NetworkingHelpers.SessionTokenCallback() {
            @Override
            public void onResponse(String sessionToken) {
                // Launch the ZoOm Session.
                FaceTecSessionActivity.createAndLaunchSession(context, LivenessCheckProcessor.this, sessionToken);
            }

            @Override
            public void onError() {
                sessionTokenErrorCallback.onError("LivenessCheckProcessor");
            }
        });
    }

    public boolean isSuccess() {
        return _isSuccess;
    }

    // Required function that handles calling ZoOm Server to get result and decides how to continue.
    public void processSessionResultWhileFaceTecSDKWaits(final FaceTecSessionResult zoomSessionResult, final FaceTecFaceScanResultCallback FaceTecFaceScanResultCallback) {
        this.latestFaceTecSessionResult = zoomSessionResult;
        this.FaceTecFaceScanResultCallback = FaceTecFaceScanResultCallback;

        // Cancel last request in flight.  This handles case where processing is is taking place but cancellation or Context Switch occurs.
        // Our handling here ends the latest in flight request and simply re-does the normal logic, which will cancel out.
        NetworkingHelpers.cancelPendingRequests();

        // cancellation, timeout, etc.
        if (zoomSessionResult.getStatus() != FaceTecSessionStatus.SESSION_COMPLETED_SUCCESSFULLY) {
            FaceTecFaceScanResultCallback.cancel();
            this.FaceTecFaceScanResultCallback = null;
            return;
        }

        // Create and parse request to ZoOm Server.
        NetworkingHelpers.getLivenessCheckResponseFromZoomServer(zoomSessionResult, this.FaceTecFaceScanResultCallback, new FaceTecManagedAPICallback() {
            @Override
            public void onResponse(JSONObject responseJSON) {
                UXNextStep nextStep = ServerResultHelpers.getLivenessNextStep(responseJSON);

                if (nextStep == UXNextStep.Succeed) {
                    _isSuccess = true;
                    sessionTokenSuccessCallback.onSuccess(responseJSON.toString());
                    ZoomCustomization.overrideResultScreenSuccessMessage = "Liveness\nConfirmed";
                    FaceTecFaceScanResultCallback.succeed();
                }
                else if (nextStep == UXNextStep.Retry) {
                    FaceTecFaceScanResultCallback.retry();
                }
                else {
                    FaceTecFaceScanResultCallback.cancel();
                }
            }
        });

    }
}
