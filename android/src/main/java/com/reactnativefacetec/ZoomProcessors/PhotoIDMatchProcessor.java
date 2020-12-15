// Demonstrates performing a ZoOm Session, proving Liveness, then scanning the ID and performing a Photo ID Match

// The FaceTec Device SDKs will cancel from the Progress Screen if onProgress() is not called for
// 60 seconds. This provides a failsafe for users getting stuck in the process because of a networking
// issue. If you would like to force users to stay on the Progress Screen for longer than 60 seconds,
// you can write code in the FaceScan or ID Scan Processor to call onProgress() via your own custom logic.
package com.reactnativefacetec.ZoomProcessors;

import android.content.Context;
import android.util.Log;

import com.facetec.sdk.*;
import com.facetec.sdk.FaceTecCustomization;
import com.facetec.sdk.FaceTecIDScanRetryMode;
import com.facetec.sdk.ZoomIDScanStatus;
import com.facetec.sdk.FaceTecSessionActivity;

import org.json.JSONException;
import org.json.JSONObject;

import static java.util.UUID.randomUUID;

public class PhotoIDMatchProcessor extends Processor implements FaceTecFaceScanProcessor, FaceTecIDScanProcessor {
    FaceTecFaceScanResultCallback FaceTecFaceScanResultCallback;
    FaceTecSessionResult latestFaceTecSessionResult;

    FaceTecIDScanResultCallback zoomIDScanResultCallback;
    FaceTecIDScanResult latestFaceTecIDScanResult;
    private boolean _isSuccess = false;
    SessionTokenSuccessCallback sessionTokenSuccessCallback;
    SessionTokenErrorCallback sessionTokenErrorCallback;
    String id;


  public PhotoIDMatchProcessor(String id, final Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
        // For demonstration purposes, generate a new uuid for each Photo ID Match.  Enroll this in the DB and compare against the ID after it is scanned.
        ZoomGlobalState.randomUsername = "android_sample_app_" + randomUUID();
        ZoomGlobalState.isRandomUsernameEnrolled = false;
        this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
        this.sessionTokenErrorCallback = sessionTokenErrorCallback;
        this.id = id;

        NetworkingHelpers.getSessionToken(new NetworkingHelpers.SessionTokenCallback() {
            @Override
            public void onResponse(String sessionToken) {
                // Launch the ZoOm Session.
                FaceTecSessionActivity.createAndLaunchSession(context, PhotoIDMatchProcessor.this, PhotoIDMatchProcessor.this, sessionToken);
            }

            @Override
            public void onError() {
                sessionTokenErrorCallback.onError("PhotoIDMatchProcessor");
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

        // Create and parse request to ZoOm Server.  Note here that for Photo ID Match, onFaceScanResultSucceed sends you to the next phase (ID Scan) rather than completing.
        NetworkingHelpers.getEnrollmentResponseFromZoomServer(zoomSessionResult, this.FaceTecFaceScanResultCallback, new FaceTecManagedAPICallback() {
            @Override
            public void onResponse(JSONObject responseJSON) {
                UXNextStep nextStep = ServerResultHelpers.getEnrollmentNextStep(responseJSON);
                if (nextStep == UXNextStep.Succeed) {
                    // Dynamically set the success message.
                    FaceTecCustomization.overrideResultScreenSuccessMessage = "Liveness\nConfirmed";
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

    // Required function that handles calling ZoOm Server to get result and decides how to continue.
    public void processIDScanResultWhileFaceTecSDKWaits(FaceTecIDScanResult zoomIDScanResult, final FaceTecIDScanResultCallback zoomIDScanResultCallback) {
        this.latestFaceTecIDScanResult = zoomIDScanResult;
        this.zoomIDScanResultCallback = zoomIDScanResultCallback;

        Log.i("PhotoIDMatchProcessor", "zoomIDScanResult == zoomIDScanResult Not " + zoomIDScanResult);
      // Cancel last request in flight.  This handles case where processing is is taking place but cancellation or Context Switch occurs.
        // Our handling here ends the latest in flight request and simply re-does the normal logic, which will cancel out.
        NetworkingHelpers.cancelPendingRequests();

        // cancellation, timeout, etc.
        if(zoomIDScanResult.getStatus() != FaceTecIDScanStatus.SUCCESS) {
          Log.i("PhotoIDMatchProcessor", "PhotoIDMatchProcessor == Not suceess");
            zoomIDScanResultCallback.cancel();
            this.zoomIDScanResultCallback = null;
            return;
        }

        // NOTE: some guesswork here
        if(zoomIDScanResult.getIDType() == null) {
          Log.i("PhotoIDMatchProcessor", "PhotoIDMatchProcessor metrics == Null");
          zoomIDScanResultCallback.cancel();
            this.zoomIDScanResultCallback = null;
            return;
        }

        if(zoomIDScanResult.getIDScan() == null) {
          Log.i("PhotoIDMatchProcessor", "PhotoIDMatchProcessor id == Null");
          zoomIDScanResultCallback.cancel();
            this.zoomIDScanResultCallback = null;
            return;
        }

        // Create and parse request to ZoOm Server.
        NetworkingHelpers.getPhotoIDMatchResponseFromZoomServer(id, zoomIDScanResult, zoomIDScanResultCallback, new FaceTecManagedAPICallback() {
            @Override
            public void onResponse(JSONObject responseJSON) {
              Log.i("PhotoIDMatchProcessor", "PhotoIDMatchProcessor == responseJSON Not " + responseJSON.toString());

              IDScanUXNextStep nextStep = ServerResultHelpers.getPhotoIDMatchNextStep(responseJSON);
                if (nextStep == IDScanUXNextStep.Succeed) {
                    _isSuccess = true;
                    // Dynamically set the success message.
                    FaceTecCustomization.overrideResultScreenSuccessMessage = "Your 3D Face\nMatched Your ID";
                    zoomIDScanResultCallback.succeed();
                  Log.i("PhotoIDMatchProcessor", "PhotoIDMatchProcessor == success " + responseJSON.toString());
                  JSONObject obj = new JSONObject();
                  try {

                    obj.put("responseJSON", responseJSON.getJSONObject("data").toString());
                    obj.put("FrontImagesCompressedBase64", zoomIDScanResult.getFrontImagesCompressedBase64().get(0));
                    if(zoomIDScanResult.getBackImagesCompressedBase64() != null && zoomIDScanResult.getBackImagesCompressedBase64().size() > 0){
                        obj.put("BackImagesCompressedBase64", zoomIDScanResult.getBackImagesCompressedBase64().get(0));
                    }


                    sessionTokenSuccessCallback.onSuccess(obj.toString());
                  } catch (JSONException e) {
                      sessionTokenSuccessCallback.onSuccess(responseJSON.toString());
                      try{
                        obj.put("responseJSON", responseJSON.toString());
                        if(zoomIDScanResult.getFrontImagesCompressedBase64() != null && zoomIDScanResult.getFrontImagesCompressedBase64().size() > 0){
                            obj.put("FrontImagesCompressedBase64", zoomIDScanResult.getFrontImagesCompressedBase64().get(0));
                        }
                          if(zoomIDScanResult.getBackImagesCompressedBase64() != null && zoomIDScanResult.getBackImagesCompressedBase64().size() > 0){
                              obj.put("BackImagesCompressedBase64", zoomIDScanResult.getBackImagesCompressedBase64().get(0));
                          }

                      }catch (Exception e1){
                        e1.printStackTrace();
                      }
                      e.printStackTrace();
                    }
                }
                else if (nextStep == IDScanUXNextStep.Retry) {
//                  sessionTokenErrorCallback.onError(responseJSON.toString());
                  zoomIDScanResultCallback.retry(FaceTecIDScanRetryMode.FRONT);
                }
                else if (nextStep == IDScanUXNextStep.RetryInvalidId) {
//                  sessionTokenErrorCallback.onError(responseJSON.toString());
                  zoomIDScanResultCallback.retry(FaceTecIDScanRetryMode.FRONT, "Photo ID\nNot Fully Visible");
                }
                else {
                  JSONObject obj = new JSONObject();
                    try{
                      obj.put("responseJSON", responseJSON.toString());
                      obj.put("FrontImagesCompressedBase64", zoomIDScanResult.getFrontImagesCompressedBase64());
                      obj.put("BackImagesCompressedBase64", zoomIDScanResult.getBackImagesCompressedBase64());
                      sessionTokenErrorCallback.onError(obj.toString());

                    }catch (Exception w){
                      w.printStackTrace();
                      sessionTokenErrorCallback.onError(responseJSON.toString());
                    }

                  zoomIDScanResultCallback.cancel();
                }
            }
        });
    }
}
