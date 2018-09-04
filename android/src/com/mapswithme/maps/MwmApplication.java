package com.mapswithme.maps;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.multidex.MultiDex;
import android.text.TextUtils;
import android.util.Log;

import com.appsflyer.AppsFlyerLib;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.crashlytics.android.ndk.CrashlyticsNdk;
import com.mapswithme.maps.background.AppBackgroundTracker;
import com.mapswithme.maps.background.NotificationChannelFactory;
import com.mapswithme.maps.background.NotificationChannelProvider;
import com.mapswithme.maps.background.Notifier;
import com.mapswithme.maps.bookmarks.data.BookmarkManager;
import com.mapswithme.maps.downloader.CountryItem;
import com.mapswithme.maps.downloader.MapManager;
import com.mapswithme.maps.editor.Editor;
import com.mapswithme.maps.location.LocationHelper;
import com.mapswithme.maps.location.TrackRecorder;
import com.mapswithme.maps.maplayer.subway.SubwayManager;
import com.mapswithme.maps.maplayer.traffic.TrafficManager;
import com.mapswithme.maps.purchase.BillingFactory;
import com.mapswithme.maps.purchase.PurchaseValidator;
import com.mapswithme.maps.routing.RoutingController;
import com.mapswithme.maps.scheduling.ConnectivityJobScheduler;
import com.mapswithme.maps.scheduling.ConnectivityListener;
import com.mapswithme.maps.sound.TtsPlayer;
import com.mapswithme.maps.ugc.UGC;
import com.mapswithme.util.Config;
import com.mapswithme.util.Counters;
import com.mapswithme.util.KeyValue;
import com.mapswithme.util.SharedPropertiesUtils;
import com.mapswithme.util.StorageUtils;
import com.mapswithme.util.ThemeSwitcher;
import com.mapswithme.util.UiUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;
import com.mapswithme.util.statistics.PushwooshHelper;
import com.mapswithme.util.statistics.Statistics;
import com.mopub.common.MoPub;
import com.mopub.common.SdkConfiguration;
import com.my.tracker.MyTracker;
import com.my.tracker.MyTrackerParams;
import com.pushwoosh.Pushwoosh;
import io.fabric.sdk.android.Fabric;
import ru.mail.libnotify.api.NotificationApi;
import ru.mail.libnotify.api.NotificationFactory;
import ru.mail.notify.core.api.BackgroundAwakeMode;
import ru.mail.notify.core.api.NetworkSyncMode;

import java.util.HashMap;
import java.util.List;

public class MwmApplication extends Application
{
  private Logger mLogger;
  private final static String TAG = "MwmApplication";

  private static final String PW_EMPTY_APP_ID = "XXXXX";

  private static MwmApplication sSelf;
  private SharedPreferences mPrefs;
  private AppBackgroundTracker mBackgroundTracker;
  @SuppressWarnings("NullableProblems")
  @NonNull
  private SubwayManager mSubwayManager;
  @SuppressWarnings("NullableProblems")
  @NonNull
  private PushwooshHelper mPushwooshHelper;
  @NonNull
  private final PurchaseValidator mPurchaseValidator = BillingFactory.ADS_REMOVAL.createPurchaseManager();

  private boolean mFrameworkInitialized;
  private boolean mPlatformInitialized;
  private boolean mCrashlyticsInitialized;

  private Handler mMainLoopHandler;
  private final Object mMainQueueToken = new Object();
  @NonNull
  private final AppBackgroundTracker.OnVisibleAppLaunchListener mVisibleAppLaunchListener = new VisibleAppLaunchListener();
  @SuppressWarnings("NullableProblems")
  @NonNull
  private ConnectivityListener mConnectivityListener;
  @NonNull
  private final MapManager.StorageCallback mStorageCallbacks = new StorageCallbackImpl();
  @NonNull
  private final AppBackgroundTracker.OnTransitionListener mBackgroundListener = new TransitionListener();

  @NonNull
  public SubwayManager getSubwayManager()
  {
    return mSubwayManager;
  }

  public MwmApplication()
  {
    super();
    sSelf = this;
  }

  public static MwmApplication get()
  {
    return sSelf;
  }

  public static AppBackgroundTracker backgroundTracker()
  {
    return sSelf.mBackgroundTracker;
  }

  /**
   *
   * Use {@link #prefs(Context)} instead.
   */
  @Deprecated
  public synchronized static SharedPreferences prefs()
  {
    if (sSelf.mPrefs == null)
      sSelf.mPrefs = sSelf.getSharedPreferences(sSelf.getString(R.string.pref_file_name), MODE_PRIVATE);

    return sSelf.mPrefs;
  }

  @NonNull
  public static SharedPreferences prefs(@NonNull Context context)
  {
    String prefFile = context.getString(R.string.pref_file_name);
    return context.getSharedPreferences(prefFile, MODE_PRIVATE);
  }

  public boolean isCrashlyticsEnabled()
  {
    return !BuildConfig.FABRIC_API_KEY.startsWith("0000");
  }

  private boolean isFabricEnabled()
  {
    String prefKey = getResources().getString(R.string.pref_opt_out_fabric_activated);
    return mPrefs.getBoolean(prefKey, true);
  }

  @Override
  protected void attachBaseContext(Context base)
  {
    super.attachBaseContext(base);
    MultiDex.install(this);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Override
  public void onCreate()
  {
    super.onCreate();
    mLogger = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.MISC);
    mLogger.d(TAG, "Application is created");
    mMainLoopHandler = new Handler(getMainLooper());

    mPrefs = getSharedPreferences(getString(R.string.pref_file_name), MODE_PRIVATE);
    initCoreIndependentSdks();
    initNotificationChannels();

    mBackgroundTracker = new AppBackgroundTracker();
    mBackgroundTracker.addListener(mVisibleAppLaunchListener);
    mSubwayManager = new SubwayManager(this);
    mConnectivityListener = new ConnectivityJobScheduler(this);
    mConnectivityListener.listen();
  }

  private void initNotificationChannels()
  {
    NotificationChannelProvider channelProvider = NotificationChannelFactory.createProvider(this);
    channelProvider.setAuthChannel();
    channelProvider.setDownloadingChannel();
  }

  private void initCoreIndependentSdks()
  {
    initMoPub();
    initCrashlytics();
    initLibnotify();
    initPushWoosh();
    initAppsFlyer();
    initTracker();
  }

  private void initMoPub()
  {
    SdkConfiguration sdkConfiguration = new SdkConfiguration
        .Builder(Framework.nativeMoPubInitializationBannerId())
        .build();

    MoPub.initializeSdk(this, sdkConfiguration, null);
  }

  /**
   * Initialize native core of application: platform and framework. Caller must handle returned value
   * and do nothing with native code if initialization is failed.
   *
   * @return boolean - indicator whether native initialization is successful or not.
   */
  public boolean initCore()
  {
    initNativePlatform();
    if (!mPlatformInitialized)
      return false;

    initNativeFramework();
    return mFrameworkInitialized;
  }

  private void initNativePlatform()
  {
    if (mPlatformInitialized)
      return;

    final boolean isInstallationIdFound = setInstallationIdToCrashlytics();

    final String settingsPath = StorageUtils.getSettingsPath();
    mLogger.d(TAG, "onCreate(), setting path = " + settingsPath);
    final String filesPath = StorageUtils.getFilesPath();
    mLogger.d(TAG, "onCreate(), files path = " + filesPath);
    final String tempPath = StorageUtils.getTempPath();
    mLogger.d(TAG, "onCreate(), temp path = " + tempPath);

    // If platform directories are not created it means that native part of app will not be able
    // to work at all. So, we just ignore native part initialization in this case, e.g. when the
    // external storage is damaged or not available (read-only).
    if (!createPlatformDirectories(settingsPath, filesPath, tempPath))
      return;

    // First we need initialize paths and platform to have access to settings and other components.
    nativeInitPlatform(StorageUtils.getApkPath(), StorageUtils.getStoragePath(settingsPath),
                       filesPath, tempPath, StorageUtils.getObbGooglePath(), BuildConfig.FLAVOR,
                       BuildConfig.BUILD_TYPE, UiUtils.isTablet());

    Config.setStatisticsEnabled(SharedPropertiesUtils.isStatisticsEnabled());

    @SuppressWarnings("unused")
    Statistics s = Statistics.INSTANCE;

    if (!isInstallationIdFound)
      setInstallationIdToCrashlytics();

    mBackgroundTracker.addListener(mBackgroundListener);
    TrackRecorder.init();
    Editor.init(this);
    UGC.init(this);
    mPlatformInitialized = true;
  }

  private boolean createPlatformDirectories(@NonNull String settingsPath, @NonNull String filesPath,
                                            @NonNull String tempPath)
  {
    if (SharedPropertiesUtils.shouldEmulateBadExternalStorage())
      return false;

    return StorageUtils.createDirectory(settingsPath) &&
           StorageUtils.createDirectory(filesPath) &&
           StorageUtils.createDirectory(tempPath);
  }

  private void initNativeFramework()
  {
    if (mFrameworkInitialized)
      return;

    nativeInitFramework();

    MapManager.nativeSubscribe(mStorageCallbacks);

    initNativeStrings();
    BookmarkManager.loadBookmarks();
    TtsPlayer.INSTANCE.init(this);
    ThemeSwitcher.restart(false);
    LocationHelper.INSTANCE.initialize();
    RoutingController.get().initialize();
    TrafficManager.INSTANCE.initialize();
    SubwayManager.from(this).initialize();
    mPurchaseValidator.initialize();
    mFrameworkInitialized = true;
  }

  private void initNativeStrings()
  {
    nativeAddLocalization("core_entrance", getString(R.string.core_entrance));
    nativeAddLocalization("core_exit", getString(R.string.core_exit));
    nativeAddLocalization("core_my_places", getString(R.string.core_my_places));
    nativeAddLocalization("core_my_position", getString(R.string.core_my_position));
    nativeAddLocalization("core_placepage_unknown_place", getString(R.string.core_placepage_unknown_place));
    nativeAddLocalization("wifi", getString(R.string.wifi));
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

    Fabric.with(this, core, new CrashlyticsNdk());
    nativeInitCrashlytics();
    mCrashlyticsInitialized = true;
  }

  public boolean isCrashlyticsInitialized()
  {
    return mCrashlyticsInitialized;
  }

  private boolean setInstallationIdToCrashlytics()
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

  public boolean arePlatformAndCoreInitialized()
  {
    return mFrameworkInitialized && mPlatformInitialized;
  }

  static
  {
    System.loadLibrary("mapswithme");
  }

  private void initPushWoosh()
  {
    try
    {
      if (BuildConfig.PW_APPID.equals(PW_EMPTY_APP_ID))
        return;

      Pushwoosh pushManager = Pushwoosh.getInstance();
      pushManager.registerForPushNotifications();

      NotificationApi api = NotificationFactory.get(this);
      mPushwooshHelper = new PushwooshHelper(api);
    }
    catch(Exception e)
    {
      mLogger.e("Pushwoosh", "Failed to init Pushwoosh", e);
    }
  }

  private void initLibnotify()
  {
    if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE.equals("beta"))
    {
      NotificationFactory.enableDebugMode();
      NotificationFactory.setLogReceiver(LoggerFactory.INSTANCE.createLibnotifyLogger());
      NotificationFactory.setUncaughtExceptionListener((thread, throwable) -> {
        Logger l = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.THIRD_PARTY);
        l.e("LIBNOTIFY", "Thread: " + thread, throwable);
      });
    }
    NotificationFactory.setNetworkSyncMode(NetworkSyncMode.WIFI_ONLY);
    NotificationFactory.setBackgroundAwakeMode(BackgroundAwakeMode.DISABLED);
    NotificationFactory.initialize(this);
  }

  private void initAppsFlyer()
  {
    // There is no necessary to use a conversion data listener for a while.
    // When it's needed keep in mind that the core can't be used from the mentioned listener unless
    // the AppsFlyer sdk initializes after core initialization.
    AppsFlyerLib.getInstance().init(PrivateVariables.appsFlyerKey(),
                                    null /* conversionDataListener */);
    AppsFlyerLib.getInstance().setDebugLog(BuildConfig.DEBUG);
    AppsFlyerLib.getInstance().startTracking(this);
  }

  @SuppressWarnings("unused")
  void sendAppsFlyerTags(@NonNull String tag, @NonNull KeyValue[] params)
  {
    HashMap<String, Object> paramsMap = new HashMap<>();
    for (KeyValue p : params)
      paramsMap.put(p.mKey, p.mValue);
    AppsFlyerLib.getInstance().trackEvent(this, tag, paramsMap);
  }

  @SuppressWarnings("unused")
  void sendPushWooshTags(String tag, String[] values)
  {
    try
    {
      mPushwooshHelper.sendTags(tag, values);
    }
    catch(Exception e)
    {
      mLogger.e("Pushwoosh", "Failed to send pushwoosh tags", e);
    }
  }

  private void initTracker()
  {
    MyTracker.setDebugMode(BuildConfig.DEBUG);
    MyTracker.createTracker(PrivateVariables.myTrackerKey(), this);
    final MyTrackerParams myParams = MyTracker.getTrackerParams();
    if (myParams != null)
      myParams.setDefaultVendorAppPackage();
    MyTracker.initTracker();
  }

  @NonNull
  public PurchaseValidator getPurchaseValidator()
  {
    return mPurchaseValidator;
  }

  public static void onUpgrade()
  {
    Counters.resetAppSessionCounters();
  }

  @SuppressWarnings("unused")
  void forwardToMainThread(final long taskPointer)
  {
    Message m = Message.obtain(mMainLoopHandler, new Runnable()
    {
      @Override
      public void run()
      {
        nativeProcessTask(taskPointer);
      }
    });
    m.obj = mMainQueueToken;
    mMainLoopHandler.sendMessage(m);
  }

  @NonNull
  public ConnectivityListener getConnectivityListener()
  {
    return mConnectivityListener;
  }

  private native void nativeInitPlatform(String apkPath, String storagePath, String privatePath,
                                         String tmpPath, String obbGooglePath, String flavorName,
                                         String buildType, boolean isTablet);

  private static native void nativeInitFramework();
  private static native void nativeProcessTask(long taskPointer);
  private static native void nativeAddLocalization(String name, String value);

  @UiThread
  private static native void nativeInitCrashlytics();

  private static class VisibleAppLaunchListener implements AppBackgroundTracker.OnVisibleAppLaunchListener
  {
    @Override
    public void onVisibleAppLaunch()
    {
      Statistics.INSTANCE.trackColdStartupInfo();
    }
  }

  private class StorageCallbackImpl implements MapManager.StorageCallback
  {
    @Override
    public void onStatusChanged(List<MapManager.StorageCallbackData> data)
    {
      Notifier notifier = Notifier.from(MwmApplication.this);
      for (MapManager.StorageCallbackData item : data)
        if (item.isLeafNode && item.newStatus == CountryItem.STATUS_FAILED)
        {
          if (MapManager.nativeIsAutoretryFailed())
          {
            notifier.notifyDownloadFailed(item.countryId, MapManager.nativeGetName(item.countryId));
            MapManager.sendErrorStat(Statistics.EventName.DOWNLOADER_ERROR, MapManager.nativeGetError(item.countryId));
          }

          return;
        }
    }

    @Override
    public void onProgress(String countryId, long localSize, long remoteSize) {}
  }

  private static class TransitionListener implements AppBackgroundTracker.OnTransitionListener
  {
    @Override
    public void onTransit(boolean foreground)
    {
      if (!foreground && LoggerFactory.INSTANCE.isFileLoggingEnabled())
      {
        Log.i(TAG, "The app goes to background. All logs are going to be zipped.");
        LoggerFactory.INSTANCE.zipLogs(null);
      }
    }
  }
}
