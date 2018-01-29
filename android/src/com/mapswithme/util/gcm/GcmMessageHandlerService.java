package com.mapswithme.util.gcm;

import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.gcm.GcmListenerService;
import ru.mail.libnotify.api.NotificationFactory;

public class GcmMessageHandlerService extends GcmListenerService
{
  private static final String LOG_TAG = "App:GcmHandlerService";

  @Override
  public void onMessageReceived(String from, Bundle data) {
    super.onMessageReceived(from, data);
    Log.v(LOG_TAG, String.format("message received from %s", from));
    NotificationFactory.deliverGcmMessageIntent(this, from, data);
  }
}
