// Demonstrates performing an Enrollment.

// The FaceTec Device SDKs will cancel from the Progress Screen if onProgress() is not called for
// 60 seconds. This provides a failsafe for users getting stuck in the process because of a networking
// issue. If you would like to force users to stay on the Progress Screen for longer than 60 seconds,
// you can write code in the FaceScan or ID Scan Processor to call onProgress() via your own custom logic.
package com.reactnativefacetec.ZoomProcessors;

import android.content.Context;
import android.util.Log;

import com.facetec.sdk.ZoomCustomization;
import com.facetec.sdk.FaceTecFaceScanProcessor;
import com.facetec.sdk.FaceTecFaceScanResultCallback;
import com.facetec.sdk.FaceTecSessionActivity;
import com.facetec.sdk.FaceTecSessionResult;
import com.facetec.sdk.FaceTecSessionStatus;

import org.json.JSONException;
import org.json.JSONObject;

import static java.util.UUID.randomUUID;

public class EnrollmentProcessor extends Processor implements FaceTecFaceScanProcessor {
    FaceTecFaceScanResultCallback FaceTecFaceScanResultCallback;
    FaceTecSessionResult latestFaceTecSessionResult;
    private boolean _isSuccess = false;
    SessionTokenSuccessCallback sessionTokenSuccessCallback;

    public EnrollmentProcessor(String id, final Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
        // For demonstration purposes, generate a new uuid for each user and flag as successful in onZoomSessionComplete.  Reset enrollment status each enrollment attempt.
       this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
        ZoomGlobalState.randomUsername = id;
        NetworkingHelpers.getSessionToken(new NetworkingHelpers.SessionTokenCallback() {
            @Override
            public void onResponse(String sessionToken) {
                // Launch the ZoOm Session.
                FaceTecSessionActivity.createAndLaunchSession(context, EnrollmentProcessor.this, sessionToken);
            }

            @Override
            public void onError() {
                sessionTokenErrorCallback.onError("EnrollmentProcessor");
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
        NetworkingHelpers.getEnrollmentResponseFromZoomServer(zoomSessionResult, this.FaceTecFaceScanResultCallback, new FaceTecManagedAPICallback() {
            @Override
            public void onResponse(JSONObject responseJSON) {
                UXNextStep nextStep = ServerResultHelpers.getEnrollmentNextStep(responseJSON);

                if (nextStep == UXNextStep.Succeed) {
                    _isSuccess = true;
                    // Dynamically set the success message.
                  try {
                    sessionTokenSuccessCallback.onSuccess(responseJSON.getJSONObject("data").toString());
                  } catch (JSONException e) {
                    sessionTokenSuccessCallback.onSuccess(responseJSON.toString());
                    e.printStackTrace();
                  }
                  Log.i("responseJSON", "responseJSON == "+ responseJSON.toString());
                    ZoomCustomization.overrideResultScreenSuccessMessage = "Enrollment\nSuccessful";
//                    ZoomGlobalState.isRandomUsernameEnrolled = true;
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
