// Demonstrates performing an Enrollment.

// The FaceTec Device SDKs will cancel from the Progress Screen if onProgress() is not called for
// 60 seconds. This provides a failsafe for users getting stuck in the process because of a networking
// issue. If you would like to force users to stay on the Progress Screen for longer than 60 seconds,
// you can write code in the FaceMap or ID Scan Processor to call onProgress() via your own custom logic.
package com.reactnativefacetec.Processors;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facetec.sdk.FaceTecCustomization;
import com.facetec.sdk.FaceTecFaceScanProcessor;
import com.facetec.sdk.FaceTecFaceScanResultCallback;
import com.facetec.sdk.FaceTecSDK;
import com.facetec.sdk.FaceTecSessionActivity;
import com.facetec.sdk.FaceTecSessionResult;
import com.facetec.sdk.FaceTecSessionStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;

public class EnrollmentProcessor extends Processor implements FaceTecFaceScanProcessor {
  private boolean _isSuccess = false;
  SessionTokenSuccessCallback sessionTokenSuccessCallback;
  SessionTokenErrorCallback sessionTokenErrorCallback;
  String id ;

  //
  // Part 1:  Starting the FaceTec Session
  //
  // Required parameters:
  // - Context:  Unique for Android, a Context is passed in, which is required for the final onActivityResult function after the FaceTec SDK is done.
  // - FaceTecFaceScanProcessor:  A class that implements FaceTecFaceScanProcessor, which handles the FaceScan when the User completes a Session.  In this example, "self" implements the class.
  // - sessionToken:  A valid Session Token you just created by calling your API to get a Session Token from the Server SDK.
  //
  public EnrollmentProcessor(String userId, String sessionToken, Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
    this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
    this.sessionTokenErrorCallback = sessionTokenErrorCallback;
    this.id = userId;

    FaceTecSessionActivity.createAndLaunchSession(context, EnrollmentProcessor.this, sessionToken);
  }

  public boolean isSuccess() {
    return this._isSuccess;
  }

  //
  // Part 2:  Handling the Result of a FaceScan
  //
  public void processSessionWhileFaceTecSDKWaits(final FaceTecSessionResult sessionResult, final FaceTecFaceScanResultCallback faceScanResultCallback) {

    if(sessionResult.getStatus() != FaceTecSessionStatus.SESSION_COMPLETED_SUCCESSFULLY) {
      sessionTokenErrorCallback.onError("EnrollmentProcessor");
      NetworkingHelpers.cancelPendingRequests();
      faceScanResultCallback.cancel();
      return;
    }

    //
    // Part 4:  Get essential data off the FaceTecSessionResult
    //
    JSONObject parameters = new JSONObject();
    try {
      parameters.put("faceScan", sessionResult.getFaceScanBase64());
      parameters.put("auditTrailImage", sessionResult.getAuditTrailCompressedBase64()[0]);
      parameters.put("lowQualityAuditTrailImage", sessionResult.getLowQualityAuditTrailCompressedBase64()[0]);
      parameters.put("externalDatabaseRefID", id);
    }
    catch(JSONException e) {
      e.printStackTrace();
      sessionTokenErrorCallback.onError("EnrollmentProcessor");
      Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to create JSON payload for upload.");
    }

    //
    // Part 5:  Make the Networking Call to Your Servers.  Below is just example code, you are free to customize based on how your own API works.
    //
    okhttp3.Request request = new okhttp3.Request.Builder()
      .url(Config.BaseURL + "/enrollment-3d")
      .header("Content-Type", "application/json")
      .header("X-Device-Key", Config.DeviceKeyIdentifier)
      .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(sessionResult.getSessionId()))

      //
      // Part 7:  Demonstrates updating the Progress Bar based on the progress event.
      //
      .post(new ProgressRequestBody(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), parameters.toString()),
        new ProgressRequestBody.Listener() {
          @Override
          public void onUploadProgressChanged(long bytesWritten, long totalBytes) {
            final float uploadProgressPercent = ((float)bytesWritten) / ((float)totalBytes);
            faceScanResultCallback.uploadProgress(uploadProgressPercent);
          }
        }))
      .build();

    //
    // Part 8:  Actually send the request.
    //
    NetworkingHelpers.getApiClient().newCall(request).enqueue(new Callback() {
      @Override
      public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
        //
        // Part 6:  In our Sample, we evaluate a boolean response and treat true as was successfully processed and should proceed to next step,
        // and handle all other responses by cancelling out.
        // You may have different paradigms in your own API and are free to customize based on these.
        //

        String responseString = response.body().string();
        response.body().close();
        try {
          JSONObject responseJSON = new JSONObject(responseString);
          boolean wasProcessed = responseJSON.getBoolean("wasProcessed");
          String scanResultBlob = responseJSON.getString("scanResultBlob");


          // In v9.2.0+, we key off a new property called wasProcessed to determine if we successfully processed the Session result on the Server.
          // Device SDK UI flow is now driven by the proceedToNextStep function, which should receive the scanResultBlob from the Server SDK response.

          if (wasProcessed) {
            // CASE:  Success!  The Enrollment was performed and the User successfully enrolled.

            // Demonstrates dynamically setting the Success Screen Message.
            FaceTecCustomization.overrideResultScreenSuccessMessage = "Enrollment\nSucceeded";

            // In v9.2.0+, simply pass in scanResultBlob to the proceedToNextStep function to advance the User flow.
            // scanResultBlob is a proprietary, encrypted blob that controls the logic for what happens next for the User.
            _isSuccess = faceScanResultCallback.proceedToNextStep(scanResultBlob);

            try {
              sessionTokenSuccessCallback.onSuccess(responseJSON.getJSONObject("data").toString());
            } catch (JSONException e) {
              sessionTokenSuccessCallback.onSuccess(responseJSON.toString());
              e.printStackTrace();
            }
          }
          else {
            // CASE:  UNEXPECTED response from API.  Our Sample Code keys of a success boolean on the root of the JSON object --> You define your own API contracts with yourself and may choose to do something different here based on the error.
            faceScanResultCallback.cancel();
            sessionTokenErrorCallback.onError("EnrollmentProcessor");
          }
        }
        catch(JSONException e) {
          // CASE:  Parsing the response into JSON failed --> You define your own API contracts with yourself and may choose to do something different here based on the error.  Solid server-side code should ensure you don't get to this case.
          e.printStackTrace();
          sessionTokenErrorCallback.onError("EnrollmentProcessor");
          Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to parse JSON result.");
          faceScanResultCallback.cancel();
        }
      }

      @Override
      public void onFailure(Call call, IOException e) {
        // CASE:  Network Request itself is erroring --> You define your own API contracts with yourself and may choose to do something different here based on the error.
        Log.d("FaceTecSDKSampleApp", "Exception raised while attempting HTTPS call.");
        faceScanResultCallback.cancel();
        sessionTokenErrorCallback.onError("EnrollmentProcessor");
      }
    });

    //
    // Part 9:  For better UX, update the User if the upload is taking a while.  You are free to customize and enhance this behavior to your liking.
    //
    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
      @Override
      public void run() {
        if(faceScanResultCallback == null) { return; }
        faceScanResultCallback.uploadMessageOverride("Still Uploading...");
      }
    }, 6000);
  }
}
