// Demonstrates performing an Authentication against a previously enrolled user.

// The FaceTec Device SDKs will cancel from the Progress Screen if onProgress() is not called for
// 60 seconds. This provides a failsafe for users getting stuck in the process because of a networking
// issue. If you would like to force users to stay on the Progress Screen for longer than 60 seconds,
// you can write code in the FaceMap or ID Scan Processor to call onProgress() via your own custom logic.
package com.reactnativefacetec.ZoomProcessors;

import android.content.Context;

import com.facetec.sdk.ZoomCustomization;
import com.facetec.sdk.ZoomFaceScanProcessor;
import com.facetec.sdk.ZoomFaceScanResultCallback;
import com.facetec.sdk.ZoomSessionActivity;
import com.facetec.sdk.ZoomSessionResult;
import com.facetec.sdk.ZoomSessionStatus;

import org.json.JSONObject;

public class AuthenticateProcessor extends Processor implements ZoomFaceScanProcessor {
    ZoomFaceScanResultCallback ZoomFaceScanResultCallback;
    ZoomSessionResult latestZoomSessionResult;
    private boolean _isSuccess = false;
    SessionTokenSuccessCallback sessionTokenSuccessCallback;
    String id;

    public AuthenticateProcessor(String id, final Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
        this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
        this.id = id;
        NetworkingHelpers.getSessionToken(new NetworkingHelpers.SessionTokenCallback() {
            @Override
            public void onResponse(String sessionToken) {
                // Launch the ZoOm Session.
                ZoomSessionActivity.createAndLaunchZoomSession(context, AuthenticateProcessor.this, sessionToken);
            }

            @Override
            public void onError() {
                sessionTokenErrorCallback.onError("AuthenticateProcessor");
            }
        });
    }

    public boolean isSuccess() {
        return _isSuccess;
    }

    // Required function that handles calling ZoOm Server to get result and decides how to continue.
    public void processSessionResultWhileFaceTecSDKWaits(final ZoomSessionResult zoomSessionResult, final ZoomFaceScanResultCallback ZoomFaceScanResultCallback) {
        this.latestZoomSessionResult = zoomSessionResult;
        this.ZoomFaceScanResultCallback = ZoomFaceScanResultCallback;

        // Cancel last request in flight.  This handles case where processing is is taking place but cancellation or Context Switch occurs.
        // Our handling here ends the latest in flight request and simply re-does the normal logic, which will cancel out.
        NetworkingHelpers.cancelPendingRequests();

        // cancellation, timeout, etc.
        if (zoomSessionResult.getStatus() != ZoomSessionStatus.SESSION_COMPLETED_SUCCESSFULLY) {
            ZoomFaceScanResultCallback.cancel();
            this.ZoomFaceScanResultCallback = null;
            return;
        }

        // Create and parse request to ZoOm Server.
        NetworkingHelpers.getAuthenticateResponseFromZoomServer(id, zoomSessionResult, this.ZoomFaceScanResultCallback, new FaceTecManagedAPICallback() {
            @Override
            public void onResponse(JSONObject responseJSON) {
                UXNextStep nextStep = ServerResultHelpers.getAuthenticateNextStep(responseJSON);

                if (nextStep == UXNextStep.Succeed) {
                    _isSuccess = true;
                    // Dynamically set the success message.
                    sessionTokenSuccessCallback.onSuccess(responseJSON.toString());
                    ZoomCustomization.overrideResultScreenSuccessMessage = "Authenticated";
                    ZoomFaceScanResultCallback.succeed();
                }
                else if (nextStep == UXNextStep.Retry) {
                    ZoomFaceScanResultCallback.retry();
                }
                else {
                    ZoomFaceScanResultCallback.cancel();
                }
            }
        });
    }
}
