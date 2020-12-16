package com.reactnativefacetec.Processors;

public abstract class Processor {
    public abstract boolean isSuccess();
    public interface SessionTokenErrorCallback {
      void onError(String msg);
    }

    public interface SessionTokenSuccessCallback {
      void onSuccess(String msg);
    }
}

