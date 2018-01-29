package com.mapswithme.util.gcm;

import android.util.Log;

import com.google.android.gms.iid.InstanceIDListenerService;
import ru.mail.libnotify.api.NotificationFactory;

public class GcmInstanceIDListenerService extends InstanceIDListenerService
{
  private final String LOG_TAG = "App:GcmIDService";

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.v(LOG_TAG, "service destroyed");
  }

  @Override
  public void onTokenRefresh() {
    super.onTokenRefresh();
    Log.v(LOG_TAG, "token refresh");
    NotificationFactory.refreshGcmToken(this);
  }
}
