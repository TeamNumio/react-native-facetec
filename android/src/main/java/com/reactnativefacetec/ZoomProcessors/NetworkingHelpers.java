// Demonstrates calling the FaceTec Managed Testing API and/or ZoOm Server

package com.reactnativefacetec.ZoomProcessors;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facetec.sdk.ZoomFaceScanResultCallback;
import com.facetec.sdk.FaceTecIDScanResult;
import com.facetec.sdk.FaceTecIDScanResultCallback;
import com.facetec.sdk.FaceTecSDK;
import com.facetec.sdk.FaceTecSessionResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;

// Possible directives after parsing the result from ZoOm Server.
enum UXNextStep {
    Succeed,
    Retry,
    Cancel
}

// Possible directives after parsing ID Scan result from ZoOm Server.
enum IDScanUXNextStep {
    Succeed,
    Retry,
    RetryInvalidId,
    Cancel
}

public class NetworkingHelpers {
    private static boolean requestInProgress = false;
    private static OkHttpClient _apiClient = null;
    private static OkHttpClient createApiClient() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        // Enabling support for TLSv1.1 and TLSv1.2 on Android 4.4 and below.
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                client = new OkHttpClient.Builder()
                        .sslSocketFactory(new TLSSocketFactory())
                        .build();
            } catch (KeyManagementException e) {
                e.printStackTrace();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            return client;
        }

        return client;
    }

    static String OK_HTTP_BUILDER_TAG = "zoomAPIRequest";
    static String OK_HTTP_RESPONSE_CANCELED = "Canceled";

    interface SessionTokenCallback {
        void onResponse(String sessionToken);
        void onError();
    }

    public static void getSessionToken(final SessionTokenCallback sessionTokenCallback) {
        // Do the network call and handle result
        okhttp3.Request request = new okhttp3.Request.Builder()
                .header("X-Device-License-Key", ZoomGlobalState.DeviceLicenseKeyIdentifier)
                .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(""))
                .url(ZoomGlobalState.ZoomServerBaseURL + "/session-token")
                .get()
                .build();

        getApiClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                Log.d("FaceTecSDK", "Exception raised while attempting HTTPS call.");

                // If this comes from HTTPS cancel call, don't set the sub code to NETWORK_ERROR.
                if(!e.getMessage().equals(OK_HTTP_RESPONSE_CANCELED)) {
                    sessionTokenCallback.onError();
                }
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                String responseString = response.body().string();
                response.body().close();
                try {
                    JSONObject responseJSON = new JSONObject(responseString);
                    if(responseJSON.has("data") && responseJSON.getJSONObject("data").has("sessionToken")) {
                        sessionTokenCallback.onResponse(responseJSON.getJSONObject("data").getString("sessionToken"));
                    }
                    else {
                        sessionTokenCallback.onError();
                    }
                }
                catch(JSONException e) {
                    e.printStackTrace();
                    Log.d("FaceTecSDK", "Exception raised while attempting to parse JSON result.");
                    sessionTokenCallback.onError();
                }
            }
        });
    }

    // Set up common parameters needed to communicate to the API.
    public static JSONObject getCommonParameters(FaceTecSessionResult zoomSessionResult) {
        String zoomFaceScanBase64 = zoomSessionResult.getFaceScanBase64();

        JSONObject parameters = new JSONObject();
        try {
            parameters.put("faceScan", zoomFaceScanBase64);
            parameters.put("sessionId", zoomSessionResult.getSessionId());
            if(zoomSessionResult.getAuditTrailCompressedBase64().length > 0) {
                String compressedBase64AuditTrailImage = zoomSessionResult.getAuditTrailCompressedBase64()[0];
                parameters.put("auditTrailImage", compressedBase64AuditTrailImage);
            }
            // pass the low quality audit trail image
            if(zoomSessionResult.getLowQualityAuditTrailCompressedBase64().length > 0) {
                String compressedBase64AuditTrailImage = zoomSessionResult.getLowQualityAuditTrailCompressedBase64()[0];
                parameters.put("lowQualityAuditTrailImage", compressedBase64AuditTrailImage);
            }

        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.d("FaceTecSDK", "Exception raised while attempting to create JSON payload for upload.");
        }
        return parameters;
    }

    // Set up parameters needed to communicate to the API for Liveness + Matching (Authenticate).
    public static JSONObject getAuthenticateParameters(String id, FaceTecSessionResult zoomSessionResult) {
        String zoomFaceScanBase64 = zoomSessionResult.getFaceScanBase64();

        JSONObject parameters = new JSONObject();
        JSONObject sourceObject = new JSONObject();
        JSONObject targetObject = new JSONObject();

        try {
            targetObject.put("faceScan", zoomFaceScanBase64);
            sourceObject.put("enrollmentIdentifier", id);
            parameters.put("performContinuousLearning", true);
            parameters.put("target", targetObject);
            parameters.put("source", sourceObject);
            parameters.put("sessionId", zoomSessionResult.getSessionId());

            if(zoomSessionResult.getAuditTrailCompressedBase64().length > 0) {
                String compressedBase64AuditTrailImage = zoomSessionResult.getAuditTrailCompressedBase64()[0];
                parameters.put("auditTrailImage", compressedBase64AuditTrailImage);
            }
            // pass the low quality audit trail image
            if(zoomSessionResult.getLowQualityAuditTrailCompressedBase64().length > 0) {
                String compressedBase64AuditTrailImage = zoomSessionResult.getLowQualityAuditTrailCompressedBase64()[0];
                parameters.put("lowQualityAuditTrailImage", compressedBase64AuditTrailImage);
            }
        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.d("FaceTecSDK", "Exception raised while attempting to create JSON payload for upload.");
        }
        return parameters;
    }

    // Set up parameters needed to communicate to the API for Photo ID Match.
    public static JSONObject getPhotoIDParameters(String id, FaceTecIDScanResult zoomIDScanResult) {
        String zoomIDScanBase64 = zoomIDScanResult.getIDScanBase64();
        String sessionId = zoomIDScanResult.getSessionId();
        JSONObject parameters = new JSONObject();
        try {
            parameters.put("enrollmentIdentifier", id);
            parameters.put("idScan", zoomIDScanBase64);
            parameters.put("sessionId", sessionId);

            ArrayList<String> frontImagesCompressedBase64 = zoomIDScanResult.getFrontImagesCompressedBase64();
            ArrayList<String> backImagesCompressedBase64 = zoomIDScanResult.getBackImagesCompressedBase64();

            if(frontImagesCompressedBase64.size() > 0) {
                parameters.put("idScanFrontImage", frontImagesCompressedBase64.get(0));
            }
            if(backImagesCompressedBase64.size() > 0) {
                parameters.put("idScanBackImage", backImagesCompressedBase64.get(0));
            }
        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.d("FaceTecSDK", "Exception raised while attempting to create JSON payload for upload.");
        }
        return parameters;
    }

    // Create and send the request.  Parse the results and send the caller what the next step should be (Succeed, Retry, or Cancel).
    public static void getLivenessCheckResponseFromZoomServer(FaceTecSessionResult zoomSessionResult, ZoomFaceScanResultCallback ZoomFaceScanResultCallback, FaceTecManagedAPICallback resultCallback ) {
        JSONObject parameters = getCommonParameters(zoomSessionResult);
        callToZoomServerForResult(
                ZoomGlobalState.ZoomServerBaseURL + "/liveness",
                parameters,
                zoomSessionResult,
                ZoomFaceScanResultCallback,
                resultCallback
        );
    }

    // Create and send the request.  Parse the results and send the caller what the next step should be (Succeed, Retry, or Cancel).
    public static void getEnrollmentResponseFromZoomServer(FaceTecSessionResult zoomSessionResult, ZoomFaceScanResultCallback ZoomFaceScanResultCallback, FaceTecManagedAPICallback resultCallback ) {
        JSONObject parameters = getCommonParameters(zoomSessionResult);
        try {
            parameters.put("enrollmentIdentifier", ZoomGlobalState.randomUsername);
        }
        catch(JSONException e) {
            e.printStackTrace();
        }
        callToZoomServerForResult(
                ZoomGlobalState.ZoomServerBaseURL + "/enrollment",
                parameters,
                zoomSessionResult,
                ZoomFaceScanResultCallback,
                resultCallback
        );
    }

    // Create and send the request.  Parse the results and send the caller what the next step should be (Succeed, Retry, or Cancel).
    public static void getAuthenticateResponseFromZoomServer(String id, FaceTecSessionResult zoomSessionResult, ZoomFaceScanResultCallback ZoomFaceScanResultCallback, FaceTecManagedAPICallback resultCallback ) {
        JSONObject parameters = getAuthenticateParameters(id, zoomSessionResult);
        callToZoomServerForResult(
                ZoomGlobalState.ZoomServerBaseURL + "/match-3d-3d",
                parameters,
                zoomSessionResult,
                ZoomFaceScanResultCallback,
                resultCallback
        );
    }

    // Create and send the request.  Parse the results and send the caller what the next step should be (Succeed, Retry, or Cancel).
    public static void getPhotoIDMatchResponseFromZoomServer(String id, FaceTecIDScanResult zoomIDScanResult, FaceTecIDScanResultCallback zoomIDScanResultCallback, FaceTecManagedAPICallback resultCallback ){
        JSONObject parameters = getPhotoIDParameters(id, zoomIDScanResult);
        callFaceTecManagedAPIForIDCheck(
                ZoomGlobalState.ZoomServerBaseURL + "/id-check",
                parameters,
                zoomIDScanResult,
                zoomIDScanResultCallback,
                resultCallback
        );
    }

    static synchronized OkHttpClient getApiClient() {
        if (_apiClient == null) {
            _apiClient = createApiClient();
        }
        return _apiClient;
    }

    // Makes the actual call to the API.
    // Note that for initial integration this sends to the FaceTec Managed Testing API.
    // After deployment of your own instance of ZoOm Server, this will be your own configurable endpoint.
    private static void callToZoomServerForResult(String endpoint, JSONObject parameters, FaceTecSessionResult zoomSessionResult, final ZoomFaceScanResultCallback ZoomFaceScanResultCallback, final FaceTecManagedAPICallback resultCallback)  {
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), parameters.toString());
        ProgressRequestBody progressRequestBody = new ProgressRequestBody(requestBody,
                new ProgressRequestBody.Listener() {
                    @Override
                    public void onUploadProgressChanged(long bytesWritten, long totalBytes) {
                        final float uploadProgressPercent = ((float)bytesWritten) / ((float)totalBytes);
                        ZoomFaceScanResultCallback.uploadProgress(uploadProgressPercent);
                    }
                });
        // Do the network call and handle result
        okhttp3.Request request = new okhttp3.Request.Builder()
                .header("Content-Type", "application/json")
                .header("X-Device-License-Key", ZoomGlobalState.DeviceLicenseKeyIdentifier)
                .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(zoomSessionResult.getSessionId()))
                .url(endpoint)
                .post(progressRequestBody)
                .build();

        getApiClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                requestInProgress = false;
                e.printStackTrace();
                Log.d("FaceTecSDK", "Exception raised while attempting HTTPS call.");

                // If this comes from HTTPS cancel call, don't set the sub code to NETWORK_ERROR.
                if(!e.getMessage().equals(OK_HTTP_RESPONSE_CANCELED)) {
                    ZoomFaceScanResultCallback.cancel();
                }
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                requestInProgress = false;
                String responseString = response.body().string();
                response.body().close();
                try {
                    JSONObject responseJSON = new JSONObject(responseString);
                    resultCallback.onResponse(responseJSON);
                }
                catch(JSONException e) {
                    e.printStackTrace();
                    Log.d("FaceTecSDK", "Exception raised while attempting to parse JSON result.");
                    ZoomFaceScanResultCallback.cancel();
                }
            }
        });
        requestInProgress = true;

        // After a short delay, if upload is not complete, update the upload message text to notify the user of the in-progress request.
        Handler longUploadHandler = new Handler(Looper.getMainLooper());
        longUploadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(ZoomFaceScanResultCallback == null || !requestInProgress) {
                    return;
                }
                ZoomFaceScanResultCallback.uploadMessageOverride("Still Uploading...");
            }
        }, 6000);

    }

    // Makes the actual call to the API.
    // Note that for initial integration this sends to the FaceTec Managed Testing API.
    // After deployment of your own instance of ZoOm Server, this will be your own configurable endpoint.
    private static void callFaceTecManagedAPIForIDCheck(String endpoint, JSONObject parameters, FaceTecIDScanResult zoomIDScanResult, final FaceTecIDScanResultCallback zoomIDScanResultCallback, final FaceTecManagedAPICallback resultCallback) {
        String sessionId = zoomIDScanResult.getSessionId();

        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), parameters.toString());
        ProgressRequestBody progressRequestBody = new ProgressRequestBody(requestBody,
                new ProgressRequestBody.Listener() {
                    @Override
                    public void onUploadProgressChanged(long bytesWritten, long totalBytes) {
                        final float uploadProgressPercent = ((float)bytesWritten) / ((float)totalBytes);
                        zoomIDScanResultCallback.uploadProgress(uploadProgressPercent);
                    }
                });
        // Do the network call and handle result
        okhttp3.Request request = new okhttp3.Request.Builder()
                .header("Content-Type", "application/json")
                .header("X-Device-License-Key", ZoomGlobalState.DeviceLicenseKeyIdentifier)
                .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(sessionId))
                .url(endpoint)
                .post(progressRequestBody)
                .build();

        getApiClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                requestInProgress = false;
                e.printStackTrace();
                Log.d("FaceTecSDK", "Exception raised while attempting HTTPS call.");

                // If this comes from HTTPS cancel call, don't set the sub code to NETWORK_ERROR.
                if(!e.getMessage().equals(OK_HTTP_RESPONSE_CANCELED)) {
                    zoomIDScanResultCallback.cancel();
                }
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                requestInProgress = false;
                String responseString = response.body().string();
                response.body().close();
                try {
                    JSONObject responseJSON = new JSONObject(responseString);
                    resultCallback.onResponse(responseJSON);
                }
                catch(JSONException e) {
                    e.printStackTrace();
                    Log.d("FaceTecSDK", "Exception raised while attempting to parse JSON result.");
                    zoomIDScanResultCallback.cancel();
                }
            }
        });
        requestInProgress = true;

        // After a short delay, if upload is not complete, update the upload message text to notify the user of the in-progress request.
        Handler longUploadHandler = new Handler(Looper.getMainLooper());
        longUploadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(zoomIDScanResultCallback == null || !requestInProgress) {
                    return;
                }
                zoomIDScanResultCallback.uploadMessageOverride("Still Uploading...");
            }
        }, 6000);
    }
    /*
     * Cancels all in flight requests.
     */
    static public void cancelPendingRequests() {
        OkHttpClient client = getApiClient();

        // Cancel all queued calls
        for (Call call : client.dispatcher().queuedCalls()) {
            if (call.request().tag().equals(OK_HTTP_BUILDER_TAG))
                call.cancel();
        }
        // Cancel all running calls
        for (Call call : client.dispatcher().runningCalls()) {
            if (call.request().tag().equals(OK_HTTP_BUILDER_TAG))
                call.cancel();
        }
    }

}

/*
 * Implementation of RequestBody that allows upload progress to be retrieved
 */
class ProgressRequestBody extends RequestBody {
    private final RequestBody requestBody;
    private Listener listener;

    ProgressRequestBody(RequestBody requestBody, Listener listener) {
        this.requestBody = requestBody;
        this.listener = listener;
    }

    @Override
    public MediaType contentType() {
        return requestBody.contentType();
    }

    @Override
    public long contentLength() throws IOException {
        return requestBody.contentLength();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        ProgressStream progressStream = new ProgressStream(sink.outputStream(), contentLength());
        BufferedSink progressSink = Okio.buffer(Okio.sink(progressStream));
        requestBody.writeTo(progressSink);
        progressSink.flush();
    }

    protected final class ProgressStream extends OutputStream {
        private final OutputStream stream;
        private long totalBytes;
        private long bytesSent;

        ProgressStream(OutputStream stream, long totalBytes) {
            this.stream = stream;
            this.totalBytes = totalBytes;
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            this.stream.write(b, off, len);
            if(len < b.length) {
                this.bytesSent += len;
            }
            else {
                this.bytesSent += b.length;
            }
            listener.onUploadProgressChanged(this.bytesSent, this.totalBytes);
        }

        @Override
        public void write(int b) throws IOException {
            this.stream.write(b);
            this.bytesSent += 1;
            listener.onUploadProgressChanged(this.bytesSent, this.totalBytes);
        }
    }

    interface Listener {
        void onUploadProgressChanged(long bytesWritten, long totalBytes);
    }
}

abstract class FaceTecManagedAPICallback {
    public abstract void onResponse(JSONObject responseJSON);
}

// Helpers for parsing API response to determine if result was a success vs. user needs retry vs. unexpected (cancel out)
// Developers are encouraged to change API call parameters and results to fit their own
class ServerResultHelpers {
    // If Liveness was Determined, succeed.  Otherwise fail.  Unexpected responses cancel.
    public static UXNextStep getLivenessNextStep(JSONObject responseJSONObj){
        try {
            if (responseJSONObj.getJSONObject("data").getInt("livenessStatus") == 0) {
                return UXNextStep.Succeed;
            } else if (responseJSONObj.getJSONObject("data").getInt("livenessStatus") == 1) {
                return UXNextStep.Retry;
            } else {
                return UXNextStep.Cancel;
            }
        }
        catch(JSONException e ){
            return UXNextStep.Cancel;
        }
    }

    // If isEnrolled and Liveness was Determined, succeed.  Otherwise retry.  Unexpected responses cancel.
    public static UXNextStep getEnrollmentNextStep(JSONObject responseJSONObj){
        try {
            if (responseJSONObj.getJSONObject("meta").getInt("code") == 200
                    && ((responseJSONObj.getJSONObject("data").getBoolean("isEnrolled") == true)
                    && responseJSONObj.getJSONObject("data").getInt("livenessStatus") == 0))
            {
                return UXNextStep.Succeed;
            }
            else if (responseJSONObj.getJSONObject("meta").getInt("code") == 200
                    && (responseJSONObj.getJSONObject("data").getBoolean("isEnrolled") == false ))
            {
                return UXNextStep.Retry;
            }
            else
            {
                return UXNextStep.Cancel;
            }
        }
        catch(JSONException e ){
            return UXNextStep.Cancel;
        }
    }

    // If isEnrolled and Liveness was Determined, succeed.  Otherwise retry.  Unexpected responses cancel.
    public static UXNextStep getAuthenticateNextStep(JSONObject responseJSONObj){
        // If both FaceScans have Liveness Proven, and Match Level is 10 (1 in 4.2 million), then succeed.  Otherwise retry.  Unexpected responses cancel.
        try {
            if(responseJSONObj.has("data") && responseJSONObj.getJSONObject("data").getInt("matchLevel") == 10 &&
                    responseJSONObj.getJSONObject("data").has("targetFaceScan") && responseJSONObj.getJSONObject("data").getJSONObject("targetFaceScan").getInt("livenessStatus") == 0 &&
                    responseJSONObj.getJSONObject("data").has("sourceFaceScan") && responseJSONObj.getJSONObject("data").getJSONObject("sourceFaceScan").getInt("livenessStatus") == 0) {
                return UXNextStep.Succeed;
            }
            else if(responseJSONObj.has("data") &&
                    responseJSONObj.getJSONObject("data").has("targetFaceScan") &&
                    responseJSONObj.getJSONObject("data").has("sourceFaceScan") &&
                    responseJSONObj.getJSONObject("data").getInt("matchLevel") != 10 ||
                    responseJSONObj.getJSONObject("data").getJSONObject("targetFaceScan").getInt("livenessStatus") != 0 ||
                    responseJSONObj.getJSONObject("data").getJSONObject("sourceFaceScan").getInt("livenessStatus") != 0) {
                return UXNextStep.Retry;
            }
            else {
                return UXNextStep.Cancel;
            }
        }
        catch(JSONException e) {
            return UXNextStep.Cancel;
        }
    }

    // If Liveness was Determined and 3D FaceScan matches the ID Scan, succeed.  Otherwise retry.  Unexpected responses cancel.
    public static IDScanUXNextStep getPhotoIDMatchNextStep(JSONObject responseJSON) {
        // If Liveness Proven on FaceScan, and Match Level between FaceScan and ID Photo is non-zero, then succeed.  Otherwise retry.  Unexpected responses cancel.
        try {
            if(responseJSON.has("meta") && responseJSON.getJSONObject("meta").getBoolean("ok") &&
                    responseJSON.getJSONObject("data").getInt("livenessStatus") == 0 &&
                    responseJSON.getJSONObject("data").has("matchLevel") && responseJSON.getJSONObject("data").getInt("matchLevel") != 0) {

                return IDScanUXNextStep.Succeed;
            }
            else if(responseJSON.has("data") &&
                    responseJSON.getJSONObject("data").has("matchLevel") &&
                    responseJSON.getJSONObject("data").getInt("livenessStatus") != 0 ||
                    responseJSON.getJSONObject("data").getInt("matchLevel") == 0) {

                if (responseJSON.getJSONObject("data").optInt("fullIDStatus") == 1) {
                    return IDScanUXNextStep.RetryInvalidId;
                }
                else {
                    return IDScanUXNextStep.Retry;
                }
            }
            else {
                return IDScanUXNextStep.Cancel;
            }
        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.d("FaceTecSDK", "Error while parsing JSON result.");
            return IDScanUXNextStep.Cancel;
        }
    }
}

// A custom networking class is required in order to support 4.4 and below.
class TLSSocketFactory extends SSLSocketFactory {

    private SSLSocketFactory delegate;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        delegate = context.getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return enableTLSOnSocket(delegate.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket enableTLSOnSocket(Socket socket) {
        if(socket != null && (socket instanceof SSLSocket)) {
            ((SSLSocket)socket).setEnabledProtocols(new String[] {"TLSv1.1", "TLSv1.2"});
        }
        return socket;
    }
}
