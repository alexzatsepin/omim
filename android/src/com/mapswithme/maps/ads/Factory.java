package com.mapswithme.maps.ads;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public class Factory
{
  @NonNull
  private static NativeAdLoader createFacebookAdLoader(@Nullable OnAdCacheModifiedListener cacheListener,
                                        @Nullable AdTracker tracker)
  {
    return new FacebookAdsLoader(cacheListener, tracker);
  }

  @NonNull
  private static NativeAdLoader createMyTargetAdLoader(@Nullable OnAdCacheModifiedListener cacheListener,
                                                      @Nullable AdTracker tracker)
  {
    return new MyTargetAdsLoader(cacheListener, tracker);
  }

  @NonNull
  static NativeAdLoader createLoaderForBanner(@NonNull Banner banner,
                                                     @Nullable OnAdCacheModifiedListener cacheListener,
                                                     @Nullable AdTracker tracker)
  {
    String provider = banner.getProvider();
    switch (provider)
    {
      case Providers.FACEBOOK:
        return createFacebookAdLoader(cacheListener, tracker);
      case Providers.MY_TARGET:
        return createMyTargetAdLoader(cacheListener, tracker);
      case Providers.MOPUB:
        return new MopubNativeDownloader(cacheListener, tracker);
      default:
        throw new UnsupportedOperationException("Unknown ads provider: " + provider);
    }
  }

  @NonNull
  public static CompoundNativeAdLoader createCompoundLoader(
      @Nullable OnAdCacheModifiedListener cacheListener, @Nullable AdTracker tracker)
  {
    return new CompoundNativeAdLoader(cacheListener, tracker);
  }
}
