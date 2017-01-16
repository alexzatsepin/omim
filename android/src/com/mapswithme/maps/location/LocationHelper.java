package com.mapswithme.maps.location;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.mapswithme.maps.Framework;
import com.mapswithme.maps.MwmApplication;
import com.mapswithme.maps.R;
import com.mapswithme.maps.bookmarks.data.Banner;
import com.mapswithme.maps.bookmarks.data.MapObject;
import com.mapswithme.maps.routing.RoutingController;
import com.mapswithme.util.Config;
import com.mapswithme.util.Listeners;
import com.mapswithme.util.LocationUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.concurrency.UiThread;
import com.mapswithme.util.log.DebugLogger;
import com.mapswithme.util.log.Logger;

import static com.mapswithme.maps.background.AppBackgroundTracker.*;
import java.lang.ref.WeakReference;

public enum LocationHelper
{
  INSTANCE;

  // These constants should correspond to values defined in platform/location.hpp
  // Leave 0-value as no any error.
  public static final int ERROR_NOT_SUPPORTED = 1;
  public static final int ERROR_DENIED = 2;
  public static final int ERROR_GPS_OFF = 3;
  public static final int ERROR_UNKNOWN = 0;

  private static final long INTERVAL_FOLLOW_AND_ROTATE_MS = 3000;
  private static final long INTERVAL_FOLLOW_MS = 1000;
  private static final long INTERVAL_NOT_FOLLOW_MS = 3000;
  private static final long INTERVAL_NAVIGATION_VEHICLE_MS = 500;

  // TODO (trashkalmar): Correct value
  private static final long INTERVAL_NAVIGATION_BICYCLE_MS = 1000;
  private static final long INTERVAL_NAVIGATION_PEDESTRIAN_MS = 1000;

  private static final long STOP_DELAY_MS = 5000;

  private boolean mErrorOccurred;

  public interface UiCallback
  {
    Activity getActivity();
    void onMyPositionModeChanged(int newMode);
    void onLocationUpdated(@NonNull Location location);
    void onCompassUpdated(@NonNull CompassData compass);
    void onLocationError();
    boolean shouldNotifyLocationNotFound();
  }

  private final OnTransitionListener mOnTransition = new OnTransitionListener()
  {
    private final GPSCheck mReceiver = new GPSCheck();
    private boolean mReceiverRegistered;

    @Override
    public void onTransit(boolean foreground)
    {
      if (foreground && !mReceiverRegistered)
      {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        MwmApplication.get().registerReceiver(mReceiver, filter);
        mReceiverRegistered = true;
        return;
      }

      if (!foreground && mReceiverRegistered)
      {
        MwmApplication.get().unregisterReceiver(mReceiver);
        mReceiverRegistered = false;
      }
    }
  };

  @NonNull
  private final LocationListener mLocationListener = new LocationListener()
  {
    @Override
    public void onLocationUpdated(Location location)
    {
      mLogger.d("onLocationUpdated()");

      mPredictor.onLocationUpdated(location);

      nativeLocationUpdated(location.getTime(),
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getAccuracy(),
                            location.getAltitude(),
                            location.getSpeed(),
                            location.getBearing());

      if (mUiCallback != null)
        mUiCallback.onLocationUpdated(location);
    }

    @Override
    public void onCompassUpdated(long time, double magneticNorth, double trueNorth, double accuracy)
    {
      if (mCompassData == null)
        mCompassData = new CompassData();

      mCompassData.update(magneticNorth, trueNorth);

      if (mUiCallback != null)
        mUiCallback.onCompassUpdated(mCompassData);
    }


    @Override
    public void onLocationError(int errorCode)
    {
      mErrorOccurred = true;
      mLogger.d("onLocationError errorCode = " + errorCode);

      nativeOnLocationError(errorCode);
      mLogger.d("nativeOnLocationError errorCode = " + errorCode +
                ", current state = " + LocationState.nameOf(LocationState.getMode()));
      stop();

      if (mUiCallback == null)
        return;

      mUiCallback.onLocationError();
    }

    @Override
    public String toString()
    {
      return "LocationHelper.mLocationListener";
    }
  };

  private final Logger mLogger = new DebugLogger(LocationHelper.class.getSimpleName());
  @NonNull
  private final Listeners<LocationListener> mListeners = new Listeners<>();

  private boolean mActive;
  private boolean mLocationStopped;
  private boolean mColdStart = true;
  private boolean mPendingNoLocation;

  private Location mSavedLocation;
  private MapObject mMyPosition;
  private long mSavedLocationTime;
  @NonNull
  private final SensorHelper mSensorHelper = new SensorHelper();
  private BaseLocationProvider mLocationProvider;
  @NonNull
  private final LocationPredictor mPredictor = new LocationPredictor(mLocationListener);
  @Nullable
  private UiCallback mUiCallback;
  private WeakReference<UiCallback> mPrevUiCallback;

  private long mInterval;
  private boolean mHighAccuracy;
  private CompassData mCompassData;

  @SuppressWarnings("FieldCanBeLocal")
  private final LocationState.ModeChangeListener mModeChangeListener = new LocationState.ModeChangeListener()
  {
    @Override
    public void onMyPositionModeChanged(int newMode)
    {
      notifyMyPositionModeChanged(newMode);
      mLogger.d("onMyPositionModeChanged mode = " + LocationState.nameOf(newMode) +
                " mColdStart = " + mColdStart + " mErrorOccurred = " + mErrorOccurred);
      switch (newMode)
      {
      case LocationState.PENDING_POSITION:
        addListener(mLocationListener, true);
        break;

      case LocationState.NOT_FOLLOW_NO_POSITION:
        if (mColdStart && !mErrorOccurred)
        {
          LocationState.nativeSwitchToNextMode();
          break;
        }

        removeListener(mLocationListener);

        if (mLocationStopped)
          break;

        if (!hasPendingNoLocation() && LocationUtils.areLocationServicesTurnedOn())
        {
          mLocationStopped = true;
          notifyLocationNotFound();
        }
        break;

      default:
        mLocationStopped = false;
        restart();
        break;
      }

      mColdStart = false;
      mErrorOccurred = false;
    }
  };

  private final Runnable mStopLocationTask = new Runnable()
  {
    @Override
    public void run()
    {
      mLogger.d("mStopLocationTask.run(). Was active: " + mActive);

      if (mActive)
        stopInternal();
    }
  };

  LocationHelper()
  {
    mLogger.d("ctor()");

    // TODO consider refactoring.
    // Actually we shouldn't initialize Framework here,
    // to allow app components to retrieve location updates without all heavy framework's stuff initialized.
    // For now this is necessary to connect mModeChangeListener below.
    MwmApplication.get().initNativeCore();
    LocationState.nativeSetListener(mModeChangeListener);

    calcParams();
    initProvider(false);
    MwmApplication.backgroundTracker().addListener(mOnTransition);
  }

  public void initProvider(boolean forceNative)
  {
    mLogger.d("initProvider forceNative = " + forceNative);
    mActive = !mListeners.isEmpty();
    if (mActive)
    {
      mLogger.d("Stop the active provider '" + mLocationProvider + "' before starting the new one");
      stopInternal();
    }

    final MwmApplication application = MwmApplication.get();
    final boolean containsGoogleServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(application) == ConnectionResult.SUCCESS;
    final boolean googleServicesTurnedInSettings = Config.useGoogleServices();
    if (!forceNative &&
        containsGoogleServices &&
        googleServicesTurnedInSettings)
    {
      mLogger.d("Use fused provider.");
      mLocationProvider = new GoogleFusedLocationProvider();
    }
    else
    {
      mLogger.d("Use native provider.");
      mLocationProvider = new AndroidNativeProvider();
    }

    mActive = !mListeners.isEmpty();
    if (mActive)
      startInternal();
  }

  public void onLocationUpdated(@NonNull Location location)
  {
    mSavedLocation = location;
    mMyPosition = null;
    mSavedLocationTime = System.currentTimeMillis();
  }

  /**
   * @return MapObject.MY_POSITION, null if location is not yet determined or "My position" button is switched off.
   */
  @Nullable
  public MapObject getMyPosition()
  {
    if (!LocationState.isTurnedOn())
    {
      mMyPosition = null;
      return null;
    }

    if (mSavedLocation == null)
      return null;

    if (mMyPosition == null)
      mMyPosition = new MapObject(MapObject.MY_POSITION, "", "", "", mSavedLocation.getLatitude(),
          mSavedLocation.getLongitude(), "", Banner.EMPTY, false);

    return mMyPosition;
  }

  /**
   * <p>Obtains last known saved location. It depends on "My position" button mode and is erased on "No follow, no position" one.
   * <p>If you need the location regardless of the button's state, use {@link #getLastKnownLocation()}.
   * @return {@code null} if no location is saved or "My position" button is in "No follow, no position" mode.
   */
  @Nullable
  public Location getSavedLocation() { return mSavedLocation; }

  public long getSavedLocationTime() { return mSavedLocationTime; }

  public void switchToNextMode()
  {
    if (mErrorOccurred)
    {
      mLogger.d("Location services are still not available, no need to switch to the next mode.");
      notifyLocationError(ERROR_DENIED);
      return;
    }
    LocationState.nativeSwitchToNextMode();
  }

  /**
   * @see LocationState#isTurnedOn()
   */
  public boolean isTurnedOn()
  {
    return LocationState.isTurnedOn();
  }

  void notifyCompassUpdated(long time, double magneticNorth, double trueNorth, double accuracy)
  {
    for (LocationListener listener : mListeners)
      listener.onCompassUpdated(time, magneticNorth, trueNorth, accuracy);
    mListeners.finishIterate();
  }

  void notifyLocationUpdated()
  {
    mLogger.d("notifyLocationUpdated()");

    if (mSavedLocation == null)
    {
      mLogger.d("No saved location - skip");
      return;
    }

    for (LocationListener listener : mListeners)
      listener.onLocationUpdated(mSavedLocation);
    mListeners.finishIterate();
  }

  private void notifyLocationUpdated(LocationListener listener)
  {
    mLogger.d("notifyLocationUpdated(), listener: " + listener);

    if (mSavedLocation == null)
    {
      mLogger.d("No saved location - skip");
      return;
    }

    listener.onLocationUpdated(mSavedLocation);
  }

  private void notifyLocationError(int errCode)
  {
    mLogger.d("notifyLocationError(): " + errCode);

    for (LocationListener listener : mListeners)
      listener.onLocationError(errCode);
    mListeners.finishIterate();
  }

  private void notifyMyPositionModeChanged(int newMode)
  {
    mLogger.d("notifyMyPositionModeChanged(): " + LocationState.nameOf(newMode));

    if (mUiCallback != null)
      mUiCallback.onMyPositionModeChanged(newMode);

    mPredictor.onMyPositionModeChanged(newMode);
  }

  private void notifyLocationNotFound()
  {
    mLogger.d("notifyLocationNotFound()");

    setHasPendingNoLocation(mUiCallback == null);
    if (hasPendingNoLocation())
    {
      mLogger.d("UI detached - schedule notification");
      return;
    }

    if (!mUiCallback.shouldNotifyLocationNotFound())
      return;

    Activity activity = mUiCallback.getActivity();
    String message = String.format("%s\n\n%s", activity.getString(R.string.current_location_unknown_message),
                                               activity.getString(R.string.current_location_unknown_title));
    new AlertDialog.Builder(activity)
        .setMessage(message)
        .setNegativeButton(R.string.current_location_unknown_stop_button, null)
        .setPositiveButton(R.string.current_location_unknown_continue_button, new DialogInterface.OnClickListener()
        {
          @Override
          public void onClick(DialogInterface dialog, int which)
          {
            mLocationStopped = false;
            LocationState.nativeSwitchToNextMode();
          }
        }).setOnDismissListener(new DialogInterface.OnDismissListener()
        {
          @Override
          public void onDismiss(DialogInterface dialog)
          {
            setHasPendingNoLocation(false);
          }
        }).show();
  }

  public boolean hasPendingNoLocation()
  {
    return mPendingNoLocation;
  }

  public void setHasPendingNoLocation(boolean hasPending)
  {
    mLogger.d("setHasPendingNoLocation(), hasPending: " + hasPending);
    mPendingNoLocation = hasPending;
  }

  boolean isLocationStopped()
  {
    return mLocationStopped;
  }

  public void stop()
  {
    mLogger.d("stop()");
    mLocationStopped = true;
    removeListener(mLocationListener, false);
  }

  void checkProvidersAndStartIfNeeded()
  {
    Context context = MwmApplication.get();
    LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    mLocationStopped = !networkEnabled && !gpsEnabled;
    mLogger.d("Providers availability: " + !mLocationStopped +
              "; network provider = " + networkEnabled + ", gps provider = " + gpsEnabled);

    if (mLocationStopped)
    {
      if (LocationState.getMode() == LocationState.PENDING_POSITION)
        notifyLocationError(ERROR_DENIED);
      return;
    }

    initProvider(false);
    LocationState.nativeSwitchToNextMode();
  }

  /**
   * Registers listener about location changes. Starts polling on the first listener registration.
   * @param listener listener to register.
   * @param forceUpdate instantly notify given listener about available location, if any.
   */
  @android.support.annotation.UiThread
  public void addListener(LocationListener listener, boolean forceUpdate)
  {
    mLogger.d("addListener(): " + listener + ", forceUpdate: " + forceUpdate);
    mLogger.d(" - listener count was: " + mListeners.getSize());

    UiThread.cancelDelayedTasks(mStopLocationTask);

    boolean wasEmpty = mListeners.isEmpty();
    mListeners.register(listener);

    if (wasEmpty)
    {
      calcParams();
      startInternal();
    }

    if (mActive && forceUpdate)
      notifyLocationUpdated(listener);
  }

  @android.support.annotation.UiThread
  private void removeListener(LocationListener listener, boolean delayed)
  {
    mLogger.d("removeListener(), delayed: " + delayed + ", listener: " + listener);
    mLogger.d(" - listener count was: " + mListeners.getSize());

    boolean wasEmpty = mListeners.isEmpty();
    mListeners.unregister(listener);

    if (!wasEmpty && mListeners.isEmpty())
    {
      mLogger.d(" - was not empty");

      if (delayed)
      {
        mLogger.d(" - schedule stop");
        stopDelayed();
      }
      else
      {
        mLogger.d(" - stop now");
        stopInternal();
      }
    }
  }

  /**
   * Removes given location listener. Stops polling if there are no listeners left.
   * @param listener listener to unregister.
   */
  @android.support.annotation.UiThread
  public void removeListener(LocationListener listener)
  {
    removeListener(listener, false);
  }

  @android.support.annotation.UiThread
  public void removeListener()
  {
    removeListener(mLocationListener);
  }

  void startSensors()
  {
    mSensorHelper.start();
  }

  void resetMagneticField(Location location)
  {
    mSensorHelper.resetMagneticField(mSavedLocation, location);
  }

  private void calcParams()
  {
    mHighAccuracy = true;
    if (RoutingController.get().isNavigating())
    {
      final @Framework.RouterType int router = Framework.nativeGetRouter();
      switch (router)
      {
      case Framework.ROUTER_TYPE_PEDESTRIAN:
        mInterval = INTERVAL_NAVIGATION_PEDESTRIAN_MS;
        break;

      case Framework.ROUTER_TYPE_VEHICLE:
        mInterval = INTERVAL_NAVIGATION_VEHICLE_MS;
        break;

      case Framework.ROUTER_TYPE_BICYCLE:
        mInterval = INTERVAL_NAVIGATION_BICYCLE_MS;
        break;

      default:
        throw new IllegalArgumentException("Unsupported router type: " + router);
      }

      return;
    }

    int mode = LocationState.getMode();
    switch (mode)
    {
    default:
    case LocationState.NOT_FOLLOW:
      mHighAccuracy = false;
      mInterval = INTERVAL_NOT_FOLLOW_MS;
      break;

    case LocationState.FOLLOW:
      mInterval = INTERVAL_FOLLOW_MS;
      break;

    case LocationState.FOLLOW_AND_ROTATE:
      mInterval = INTERVAL_FOLLOW_AND_ROTATE_MS;
      break;
    }
  }

  long getInterval()
  {
    return mInterval;
  }

  // TODO (trashkalmar): Usage of this method was temporarily commented out from GoogleFusedLocationProvider.start(). See comments there.
  boolean isHighAccuracy()
  {
    return mHighAccuracy;
  }

  /**
   * Recalculates location parameters and restarts locator if it was in a progress before.
   * <p>Does nothing if there were no subscribers.
   */
  public void restart()
  {
    mLogger.d("restart()");
    mActive &= !mListeners.isEmpty();
    if (!mActive)
    {
      stopInternal();
      return;
    }

    boolean oldHighAccuracy = mHighAccuracy;
    long oldInterval = mInterval;
    mLogger.d("restart. Old params: " + oldInterval + " / " + (oldHighAccuracy ? "high" : "normal"));

    calcParams();
    mLogger.d("New params: " + mInterval + " / " + (mHighAccuracy ? "high" : "normal"));

    if (mHighAccuracy != oldHighAccuracy || mInterval != oldInterval)
    {
      boolean active = mActive;
      stopInternal();

      if (active)
        startInternal();
    }
  }

  /**
   * Actually starts location polling.
   */
  private void startInternal()
  {
    mLogger.d("startInternal(), current provider is '" + mLocationProvider + "'");

    mActive = mLocationProvider.start();
    mLogger.d(mActive ? "SUCCESS" : "FAILURE");

    if (mActive)
    {
      mErrorOccurred = false;
      mPredictor.resume();
    }
    else
      notifyLocationError(LocationHelper.ERROR_DENIED);
  }

  /**
   * Actually stops location polling.
   */
  private void stopInternal()
  {
    mLogger.d("stopInternal()");

    mActive = false;
    mLocationProvider.stop();
    mSensorHelper.stop();
    mPredictor.pause();
    notifyMyPositionModeChanged(LocationState.getMode());
  }

  /**
   * Schedules poll termination after {@link #STOP_DELAY_MS}.
   */
  private void stopDelayed()
  {
    mLogger.d("stopDelayed()");
    UiThread.runLater(mStopLocationTask, STOP_DELAY_MS);
  }

  /**
   * Attach UI to helper.
   */
  public void attach(UiCallback callback)
  {
    mLogger.d("attach() callback = " + callback);

    if (mUiCallback != null)
    {
      mLogger.d(" - already attached. Skip.");
      return;
    }

    mUiCallback = callback;
    compareWithPreviousCallback();

    Utils.keepScreenOn(true, mUiCallback.getActivity().getWindow());

    mUiCallback.onMyPositionModeChanged(LocationState.getMode());
    if (mCompassData != null)
      mUiCallback.onCompassUpdated(mCompassData);

    if (hasPendingNoLocation())
      notifyLocationNotFound();

    if (!mLocationStopped)
      addListener(mLocationListener, true);
    else
      checkProvidersAndStartIfNeeded();
  }

  private void compareWithPreviousCallback()
  {
    if (mPrevUiCallback != null)
    {
      UiCallback prev = mPrevUiCallback.get();
      if (prev != null && prev != mUiCallback)
      {
        // Another UI instance attached, cancel pending "No location" dialog.
        setHasPendingNoLocation(false);
      }
    }
    mPrevUiCallback = new WeakReference<>(mUiCallback);
  }

  /**
   * Detach UI from helper.
   */
  public void detach(boolean delayed)
  {
    mLogger.d("detach(), delayed: " + delayed);

    if (mUiCallback == null)
    {
      mLogger.d(" - already detached. Skip.");
      return;
    }

    Utils.keepScreenOn(false, mUiCallback.getActivity().getWindow());
    mUiCallback = null;
    removeListener(mLocationListener, delayed);
  }

  /**
   * Obtains last known location regardless of "My position" button state.
   * @return {@code null} on failure.
   */
  @Nullable
  public Location getLastKnownLocation(long expirationMillis)
  {
    if (mSavedLocation != null)
      return mSavedLocation;

    return AndroidNativeProvider.findBestNotExpiredLocation(expirationMillis);
  }

  @Nullable
  public Location getLastKnownLocation()
  {
    return getLastKnownLocation(LocationUtils.LOCATION_EXPIRATION_TIME_MILLIS_LONG);
  }

  @Nullable
  public CompassData getCompassData()
  {
    return mCompassData;
  }

  private static native void nativeOnLocationError(int errorCode);
  private static native void nativeLocationUpdated(long time, double lat, double lon, float accuracy,
                                                   double altitude, float speed, float bearing);
  static native float[] nativeUpdateCompassSensor(int ind, float[] arr);
}
