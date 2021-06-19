// Demonstrates performing a ZoOm Session, proving Liveness, then scanning the ID and performing a Photo ID Match

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
import androidx.annotation.Nullable;

import com.facetec.sdk.FaceTecCustomization;
import com.facetec.sdk.FaceTecFaceScanProcessor;
import com.facetec.sdk.FaceTecFaceScanResultCallback;
import com.facetec.sdk.FaceTecIDScanProcessor;
import com.facetec.sdk.FaceTecIDScanResult;
import com.facetec.sdk.FaceTecIDScanResultCallback;
import com.facetec.sdk.FaceTecIDScanStatus;
import com.facetec.sdk.FaceTecSDK;
import com.facetec.sdk.FaceTecSessionActivity;
import com.facetec.sdk.FaceTecSessionResult;
import com.facetec.sdk.FaceTecSessionStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;

public class PhotoIDMatchProcessor extends Processor implements FaceTecFaceScanProcessor, FaceTecIDScanProcessor {
  private boolean _isSuccess = false;
  private boolean faceScanWasSuccessful = false;

  private boolean successCallBackCalled = false;

  SessionTokenSuccessCallback sessionTokenSuccessCallback;
  SessionTokenErrorCallback sessionTokenErrorCallback;
  String id;
  ArrayList<String> frontImagesCompressedBase64;
  ArrayList<String> backImagesCompressedBase64;

  //
  // Part 1:  Starting the FaceTec Session
  //
  // Required parameters:
  // - Context:  Unique for Android, a Context is passed in, which is required for the final onActivityResult function after the FaceTec SDK is done.
  // - FaceTecFaceScanProcessor:  A class that implements FaceTecFaceScanProcessor, which handles the FaceScan when the User completes a Session.  In this example, "self" implements the class.
  // - sessionToken:  A valid Session Token you just created by calling your API to get a Session Token from the Server SDK.
  //
  public PhotoIDMatchProcessor(String userId, String id, final Context context, final SessionTokenErrorCallback sessionTokenErrorCallback, SessionTokenSuccessCallback sessionTokenSuccessCallback) {
    // For demonstration purposes, generate a new uuid for each Photo ID Match.  Enroll this in the DB and compare against the ID after it is scanned.
    this.sessionTokenSuccessCallback = sessionTokenSuccessCallback;
    this.sessionTokenErrorCallback = sessionTokenErrorCallback;
    this.id = userId;

    FaceTecSessionActivity.createAndLaunchSession(context, PhotoIDMatchProcessor.this, PhotoIDMatchProcessor.this, id);
  }

  public boolean isSuccess() {
    return this._isSuccess;
  }

  //
  // Part 2:  Handling the Result of a FaceScan
  //
  public void processSessionWhileFaceTecSDKWaits(final FaceTecSessionResult sessionResult, final FaceTecFaceScanResultCallback faceScanResultCallback) {
    //
    // DEVELOPER NOTE:  These properties are for demonstration purposes only so the Sample App can get information about what is happening in the processor.
    // In the code in your own App, you can pass around signals, flags, intermediates, and results however you would like.
    //

    //
    // Part 3:  Handles early exit scenarios where there is no FaceScan to handle -- i.e. User Cancellation, Timeouts, etc.
    //
    if(sessionResult.getStatus() != FaceTecSessionStatus.SESSION_COMPLETED_SUCCESSFULLY) {
      NetworkingHelpers.cancelPendingRequests();
      faceScanResultCallback.cancel();
      return;
    }

    // IMPORTANT:  FaceTecSDK.FaceTecSessionStatus.SessionCompletedSuccessfully DOES NOT mean the Liveness Check was Successful.
    // It simply means the User completed the Session and a 3D FaceScan was created.  You still need to perform the Liveness Check on your Servers.

    //
    // Part 4:  Get essential data off the FaceTecSessionResult
    //
    JSONObject parameters = new JSONObject();
    try {
      parameters.put("faceScan", sessionResult.getFaceScanBase64());
      parameters.put("auditTrailImage", sessionResult.getAuditTrailCompressedBase64()[0]);
      parameters.put("lowQualityAuditTrailImage", sessionResult.getLowQualityAuditTrailCompressedBase64()[0]);
    }
    catch(JSONException e) {
      e.printStackTrace();
      Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to create JSON payload for upload.");
    }

    //
    // Part 5:  Make the Networking Call to Your Servers.  Below is just example code, you are free to customize based on how your own API works.
    //
    okhttp3.Request request = new okhttp3.Request.Builder()
      .url(Config.BaseURL + "/liveness-3d")
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
          if(wasProcessed) {

            // Demonstrates dynamically setting the Success Screen Message.
            FaceTecCustomization.overrideResultScreenSuccessMessage = "Liveness\nConfirmed";

            // In v9.2.0+, simply pass in scanResultBlob to the proceedToNextStep function to advance the User flow.
            // scanResultBlob is a proprietary, encrypted blob that controls the logic for what happens next for the User.

            _isSuccess = true;
            faceScanWasSuccessful = faceScanResultCallback.proceedToNextStep(scanResultBlob);
//            sessionTokenSuccessCallback.onSuccess("PhotoIDMatchProcessor");
          }
          else {
            // CASE:  UNEXPECTED response from API.  Our Sample Code keys off a wasProcessed boolean on the root of the JSON object --> You define your own API contracts with yourself and may choose to do something different here based on the error.
            faceScanResultCallback.cancel();
          }
        }
        catch(JSONException e) {
          // CASE:  Parsing the response into JSON failed --> You define your own API contracts with yourself and may choose to do something different here based on the error.  Solid server-side code should ensure you don't get to this case.
          e.printStackTrace();
          Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to parse JSON result.");
          faceScanResultCallback.cancel();
        }
      }

      @Override
      public void onFailure(@NonNull Call call, @Nullable IOException e) {
        // CASE:  Network Request itself is erroring --> You define your own API contracts with yourself and may choose to do something different here based on the error.
        Log.d("FaceTecSDKSampleApp", "Exception raised while attempting HTTPS call.");
        faceScanResultCallback.cancel();
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

  //
  // Part 1:  Handling the Result of an IDScan
  //
  public void processIDScanWhileFaceTecSDKWaits(final FaceTecIDScanResult idScanResult, final FaceTecIDScanResultCallback idScanResultCallback) {
    //
    // DEVELOPER NOTE:  These properties are for demonstration purposes only so the Sample App can get information about what is happening in the processor.
    // In the code in your own App, you can pass around signals, flags, intermediates, and results however you would like.
    //

    //
    // Part 2:  Handles early exit scenarios where there is no IDScan to handle -- i.e. User Cancellation, Timeouts, etc.
    //
    if(idScanResult.getStatus() != FaceTecIDScanStatus.SUCCESS) {
      NetworkingHelpers.cancelPendingRequests();
      idScanResultCallback.cancel();
      return;
    }

    // IMPORTANT:  FaceTecSDK.FaceTecIDScanStatus.Success DOES NOT mean the IDScan 3d-2d Matching was Successful.
    // It simply means the User completed the Session and a 3D FaceScan was created. You still need to perform the IDScan 3d-2d Matching on your Servers.

    //
    // minMatchLevel allows Developers to specify a Match Level that they would like to target in order for success to be true in the response.
    // minMatchLevel cannot be set to 0.
    // minMatchLevel setting does not affect underlying Algorithm behavior.
    final int minMatchLevel = 3;

    //
    // Part 3: Get essential data off the FaceTecIDScanResult
    //
    JSONObject parameters = new JSONObject();
    try {
      parameters.put("externalDatabaseRefID", id);
      parameters.put("idScan", idScanResult.getIDScanBase64());
      parameters.put("minMatchLevel", minMatchLevel);

      frontImagesCompressedBase64 = idScanResult.getFrontImagesCompressedBase64();
      backImagesCompressedBase64 = idScanResult.getBackImagesCompressedBase64();
      if(frontImagesCompressedBase64.size() > 0) {
        parameters.put("idScanFrontImage", frontImagesCompressedBase64.get(0));
      }
      if(backImagesCompressedBase64.size() > 0) {
        parameters.put("idScanBackImage", backImagesCompressedBase64.get(0));
      }
    }
    catch(JSONException e) {
      e.printStackTrace();
      Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to create JSON payload for upload.");
    }

    //
    // Part 4:  Make the Networking Call to Your Servers.  Below is just example code, you are free to customize based on how your own API works.
    //
    okhttp3.Request request = new okhttp3.Request.Builder()
      .url(Config.BaseURL + "/match-3d-2d-idscan")
      .header("Content-Type", "application/json")
      .header("X-Device-Key", Config.DeviceKeyIdentifier)
      .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(idScanResult.getSessionId()))

      //
      // Part 6:  Demonstrates updating the Progress Bar based on the progress event.
      //
      .post(new ProgressRequestBody(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), parameters.toString()),
        new ProgressRequestBody.Listener() {
          @Override
          public void onUploadProgressChanged(long bytesWritten, long totalBytes) {
            final float uploadProgressPercent = ((float)bytesWritten) / ((float)totalBytes);
            idScanResultCallback.uploadProgress(uploadProgressPercent);
          }
        }))
      .build();

    //
    // Part 7:  Actually send the request.
    //
    NetworkingHelpers.getApiClient().newCall(request).enqueue(new Callback() {
      @Override
      public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
        //
        // Part 17:  In our Sample, we evaluate a boolean response and treat true as success, false as "User Needs to Retry",
        // and handle all other non-nominal responses by cancelling out.  You may have different paradigms in your own API and are free to customize based on these.
        //
        String responseString = response.body().string();
        response.body().close();
        try {
          JSONObject responseJSON = new JSONObject(responseString);

          boolean wasProcessed = responseJSON.getBoolean("wasProcessed");
          String scanResultBlob = responseJSON.getString("scanResultBlob");
          //
          // DEVELOPER NOTE:  These properties are for demonstration purposes only so the Sample App can get information about what is happening in the processor.
          // In the code in your own App, you can pass around signals, flags, intermediates, and results however you would like.
          //

          int fullIDStatusEnumInt = responseJSON.getInt("fullIDStatusEnumInt");
          int digitalIDSpoofStatusEnumInt = responseJSON.getInt("digitalIDSpoofStatusEnumInt");

          if (wasProcessed) {
            // CASE:  Success!  The ID Match was performed and the User successfully matched.

            // In v9.2.0+, configure the messages that will be displayed to the User in each of the possible cases.
            // Based on the internal processing and decision logic about how the flow gets advanced, the FaceTec SDK will use the appropriate, configured message.
            // Please note that this programmatic API overrides these same Strings that can also be set via our standard, non-programmatic Text Customization & Localization APIs.
            FaceTecCustomization.setIDScanResultScreenMessageOverrides(
              "Your 3D Face\nMatched Your ID", // Successful scan of ID front-side (ID Types with no back-side).
              "Your 3D Face\nMatched Your ID", // Successful scan of ID front-side (ID Types that do have a back-side).
              "Back of ID Captured", // Successful scan of the ID back-side.
              "ID Verification Complete", // Successful upload of final IDScan containing User-Confirmed ID Text.
              "Face Didn't Match\nHighly Enough", // Case where a Retry is needed because the Face on the Photo ID did not Match the User's Face highly enough.
              "ID Document\nNot Fully Visible", // Case where a Retry is needed because a Full ID was not detected with high enough confidence.
              "ID Text Not Legible" // Case where a Retry is needed because the OCR did not produce good enough results and the User should Retry with a better capture.
            );

            // In v9.2.0+, simply pass in scanResultBlob to the proceedToNextStep function to advance the User flow.
            // scanResultBlob is a proprietary, encrypted blob that controls the logic for what happens next for the User.
            // Cases:
            //   1.  User must re-scan the same side of the ID that they just tried.
            //   2.  User succeeded in scanning the Front Side of the ID, there is no Back Side, and the User is now sent to the User OCR Confirmation UI.
            //   3.  User succeeded in scanning the Front Side of the ID, there is a Back Side, and the User is sent to the Auto-Capture UI for the Back Side of their ID.
            //   4.  User succeeded in scanning the Back Side of the ID, and the User is now sent to the User OCR Confirmation UI.
            //   5.  The entire process is complete.  This occurs after sending up the final IDScan that contains the User OCR Data.


            JSONObject obj = new JSONObject();
            try {
              obj.put("responseJSON", responseJSON.toString());

              if(frontImagesCompressedBase64.size() > 0) {
                obj.put("FrontImagesCompressedBase64", frontImagesCompressedBase64.get(0));
              }
              if(backImagesCompressedBase64.size() > 0) {
                obj.put("BackImagesCompressedBase64", backImagesCompressedBase64.get(0));
              }
            } catch (Exception e) {
              e.printStackTrace();
            }

            if (!successCallBackCalled) {
              sessionTokenSuccessCallback.onSuccess(obj.toString());
              successCallBackCalled = true;
            }

            _isSuccess = idScanResultCallback.proceedToNextStep(scanResultBlob);
          }
          else {
            // CASE:  UNEXPECTED response from API.  Our Sample Code keys of a success boolean on the root of the JSON object --> You define your own API contracts with yourself and may choose to do something different here based on the error.
            idScanResultCallback.cancel();
            sessionTokenErrorCallback.onError("PhotoIDMatchProcessor");
          }
        }
        catch(JSONException e) {
          // CASE:  Parsing the response into JSON failed --> You define your own API contracts with yourself and may choose to do something different here based on the error.  Solid server-side code should ensure you don't get to this case.
          e.printStackTrace();
          Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to parse JSON result.");
          idScanResultCallback.cancel();
        }
      }

      @Override
      public void onFailure(Call call, IOException e) {
        // CASE:  Network Request itself is erroring --> You define your own API contracts with yourself and may choose to do something different here based on the error.
        Log.d("FaceTecSDKSampleApp", "Exception raised while attempting HTTPS call.");
        idScanResultCallback.cancel();
      }
    });

    //
    // Part 8:  For better UX, update the User if the upload is taking a while.  You are free to customize and enhance this behavior to your liking.
    //
    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
      @Override
      public void run() {
        if(idScanResultCallback == null) { return; }
        idScanResultCallback.uploadMessageOverride("Still Uploading...");
      }
    }, 6000);
  }

}
