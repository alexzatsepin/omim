package com.mapswithme.maps.analytics;

import android.app.Application;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.text.TextUtils;

import com.appsflyer.AppsFlyerConversionListener;
import com.appsflyer.AppsFlyerLib;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.crashlytics.android.ndk.CrashlyticsNdk;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.mapswithme.maps.BuildConfig;
import com.mapswithme.maps.Framework;
import com.mapswithme.maps.MwmApplication;
import com.mapswithme.maps.PrivateVariables;
import com.mapswithme.maps.R;
import com.mapswithme.maps.ads.Banner;
import com.mapswithme.util.CrashlyticsUtils;
import com.mapswithme.util.PermissionsUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;
import com.mopub.common.MoPub;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.privacy.PersonalInfoManager;
import com.my.target.common.MyTargetPrivacy;
import io.fabric.sdk.android.Fabric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class ExternalLibrariesMediator
{
  private boolean mCrashlyticsInitialized;

  private static final String TAG = ExternalLibrariesMediator.class.getSimpleName();
  private static final Logger LOGGER = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.MISC);

  @NonNull
  private final Application mApplication;
  @NonNull
  private volatile EventLogger mEventLogger;
  private boolean mEventLoggerInitialized;
  @Nullable
  private AdvertisingInfo mAdvertisingInfo;
  @Nullable
  private String mFirstLaunchDeepLink;
  @NonNull
  private List<AdvertisingObserver> mAdvertisingObservers = new ArrayList<>();

  public ExternalLibrariesMediator(@NonNull Application application)
  {
    mApplication = application;
    mEventLogger = new DefaultEventLogger(application);
  }

  public void initSensitiveDataToleranceLibraries()
  {
    initMoPub();
    initCrashlytics();
    initAppsFlyer();
  }

  public void initSensitiveDataStrictLibrariesAsync()
  {
    GetAdInfoTask getAdInfoTask = new GetAdInfoTask(this);
    getAdInfoTask.execute();
  }

  private void initSensitiveEventLogger()
  {
    if (!com.mapswithme.util.concurrency.UiThread.isUiThread())
      throw new IllegalStateException("Must be call from Ui thread");

    if (mEventLoggerInitialized)
      return;

    mEventLogger = new EventLoggerAggregator(mApplication);
    mEventLogger.initialize();
    mEventLoggerInitialized = true;
  }

  private void initAppsFlyer()
  {
    AppsFlyerLib.getInstance().init(PrivateVariables.appsFlyerKey(), new FirstLaunchDeeplinkListener());
    AppsFlyerLib.getInstance().setDebugLog(BuildConfig.DEBUG);
    AppsFlyerLib.getInstance().setResolveDeepLinkURLs();
    AppsFlyerLib.getInstance().startTracking(mApplication);
  }

  public void initCrashlytics()
  {
    if (!isCrashlyticsEnabled())
      return;

    if (isCrashlyticsInitialized())
      return;

    Crashlytics core = new Crashlytics
        .Builder()
        .core(new CrashlyticsCore.Builder().disabled(!isFabricEnabled()).build())
        .build();

    Fabric.with(mApplication, core, new CrashlyticsNdk());
    nativeInitCrashlytics();
    mCrashlyticsInitialized = true;
  }

  public boolean isCrashlyticsEnabled()
  {
    return !BuildConfig.FABRIC_API_KEY.startsWith("0000");
  }

  private boolean isFabricEnabled()
  {
    String prefKey = mApplication.getResources().getString(R.string.pref_opt_out_fabric_activated);
    return MwmApplication.prefs(mApplication).getBoolean(prefKey, true);
  }

  @NonNull
  public EventLogger getEventLogger()
  {
    return mEventLogger;
  }

  public boolean isCrashlyticsInitialized()
  {
    return mCrashlyticsInitialized;
  }

  public boolean setInstallationIdToCrashlytics()
  {
    if (!isCrashlyticsEnabled())
      return false;

    final String installationId = Utils.getInstallationId();
    // If installation id is not found this means id was not
    // generated by alohalytics yet and it is a first run.
    if (TextUtils.isEmpty(installationId))
      return false;

    Crashlytics.setString("AlohalyticsInstallationId", installationId);
    return true;
  }

  private void initMoPub()
  {
    SdkConfiguration sdkConfiguration = new SdkConfiguration
        .Builder(Framework.nativeMoPubInitializationBannerId())
        .build();

    MoPub.initializeSdk(mApplication, sdkConfiguration, null);
    PersonalInfoManager manager = MoPub.getPersonalInformationManager();
    if (manager != null)
      manager.grantConsent();
  }

  @UiThread
  private void setAdvertisingInfo(@NonNull AdvertisingInfo info)
  {
    mAdvertisingInfo = info;
  }

  @UiThread
  private void notifyObservers()
  {
    for (AdvertisingObserver observer : mAdvertisingObservers)
      observer.onAdvertisingInfoObtained();
  }

  @UiThread
  public boolean isAdvertisingInfoObtained()
  {
    return mAdvertisingInfo != null;
  }

  @UiThread
  public boolean isLimitAdTrackingEnabled()
  {
    if (mAdvertisingInfo == null)
      throw new IllegalStateException("Advertising info must be obtained first!");

    return mAdvertisingInfo.isLimitAdTrackingEnabled();
  }

  public void disableAdProvider(@NonNull Banner.Type type)
  {
    Framework.disableAdProvider(type);
    MyTargetPrivacy.setUserConsent(false);
  }

  @NonNull
  Application getApplication()
  {
    return mApplication;
  }

  @UiThread
  public void initSensitiveData()
  {
    initSensitiveEventLogger();
    if (PermissionsUtils.isLocationGranted(getApplication()))
      return;

    MyTargetPrivacy.setUserConsent(false);
  }

  @Nullable
  public String retrieveFirstLaunchDeeplink()
  {
    String firstLaunchDeepLink = mFirstLaunchDeepLink;
    mFirstLaunchDeepLink = null;
    return firstLaunchDeepLink;
  }

  public void addAdvertisingObserver(@NonNull AdvertisingObserver observer)
  {
    mAdvertisingObservers.add(observer);
  }

  public void removeAdvertisingObserver(@NonNull AdvertisingObserver observer)
  {
    mAdvertisingObservers.remove(observer);
  }

  private class FirstLaunchDeeplinkListener implements AppsFlyerConversionListener
  {
    @Override
    public void onInstallConversionDataLoaded(Map<String, String> conversionData)
    {
      if (conversionData == null || conversionData.isEmpty())
        return;

      for (String attrName : conversionData.keySet())
      {
        LOGGER.d(TAG, "onInstallConversion attribute: " + attrName + " = "
                      + conversionData.get(attrName));
      }

      if (!AppsFlyerUtils.isFirstLaunch(conversionData))
        return;

      mFirstLaunchDeepLink = AppsFlyerUtils.getDeepLink(conversionData);
    }

    @Override
    public void onInstallConversionFailure(String errorMessage)
    {
      LOGGER.e(TAG, "onInstallConversionFailure: " + errorMessage);
    }

    @Override
    public void onAppOpenAttribution(Map<String, String> conversionData)
    {
      if (conversionData == null || conversionData.isEmpty())
        return;

      for (String attrName : conversionData.keySet())
      {
        LOGGER.d(TAG, "onAppOpenAttribution attribute: " + attrName + " = "
                      + conversionData.get(attrName));
      }
    }

    @Override
    public void onAttributionFailure(String errorMessage)
    {
      LOGGER.d(TAG, "onAttributionFailure: " + errorMessage);
    }
  }

  private static class GetAdInfoTask extends AsyncTask<Void, Void, AdvertisingInfo>
  {
    private final static long ADS_INFO_GETTING_TIMEOUT_MS = 4000;
    private final static long ADS_INFO_GETTING_CHECK_INTERVAL_MS = 500;
    @NonNull
    private final ExternalLibrariesMediator mMediator;
    @NonNull
    private final CountDownTimer mTimer = new CountDownTimer(ADS_INFO_GETTING_TIMEOUT_MS,
                                                             ADS_INFO_GETTING_CHECK_INTERVAL_MS)
    {
      @Override
      public void onTick(long millisUntilFinished)
      {
        if (getStatus() == Status.FINISHED)
        {
          LOGGER.i(TAG, "Timer could be cancelled, advertising id already obtained");
          cancel();
        }
      }

      @Override
      public void onFinish()
      {
        if (getStatus() == Status.FINISHED)
          return;

        LOGGER.w(TAG, "Cancel getting advertising id request, timeout exceeded.");
        GetAdInfoTask.this.cancel(true);
        mMediator.setAdvertisingInfo(new AdvertisingInfo(null));
        mMediator.notifyObservers();
      }
    };

    private GetAdInfoTask(@NonNull ExternalLibrariesMediator mediator)
    {
      mMediator = mediator;
    }

    @Override
    protected void onPreExecute()
    {
      super.onPreExecute();
      mTimer.start();
    }

    @Override
    protected AdvertisingInfo doInBackground(Void... voids)
    {
      try
      {
        Application application = mMediator.getApplication();
        LOGGER.i(TAG, "Start of getting advertising info");
        AdvertisingIdClient.Info info = AdvertisingIdClient.getAdvertisingIdInfo(application);
        if (isCancelled())
        {
          String msg = "Advertising id wasn't obtained within " + ADS_INFO_GETTING_TIMEOUT_MS + " ms";
          LOGGER.w(TAG, msg);
          throw new TimeoutException(msg);
        }
        LOGGER.i(TAG, "End of getting advertising info");
        return new AdvertisingInfo(info);
      }
      catch (GooglePlayServicesNotAvailableException | IOException
          | GooglePlayServicesRepairableException | TimeoutException e)
      {
        LOGGER.e(TAG, "Failed to obtain advertising id: ", e);
        CrashlyticsUtils.logException(e);
        return new AdvertisingInfo(null);
      }
    }

    @Override
    protected void onPostExecute(@NonNull AdvertisingInfo info)
    {
      LOGGER.i(TAG, "onPostExecute, info: " + info);
      super.onPostExecute(info);
      mMediator.setAdvertisingInfo(info);
      mMediator.notifyObservers();
    }

    @Override
    protected void onCancelled()
    {
      LOGGER.i(TAG, "onCancelled");
      super.onCancelled();
    }
  }

  private static class AdvertisingInfo
  {
    @Nullable
    private final AdvertisingIdClient.Info mInfo;

    private AdvertisingInfo(@Nullable AdvertisingIdClient.Info info)
    {
      mInfo = info;
    }

    @UiThread
    boolean isLimitAdTrackingEnabled()
    {
      return mInfo != null && mInfo.isLimitAdTrackingEnabled();
    }

    @Override
    public String toString()
    {
      return "AdvertisingInfo{" +
             "mInfo=" + mInfo +
             '}';
    }
  }

  @UiThread
  private static native void nativeInitCrashlytics();
}
