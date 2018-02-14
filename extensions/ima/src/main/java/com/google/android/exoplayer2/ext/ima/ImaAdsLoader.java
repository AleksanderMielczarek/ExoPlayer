/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ext.ima;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdError.AdErrorCode;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent.AdErrorListener;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener;
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ads.AdPlaybackState;
import com.google.android.exoplayer2.source.ads.AdPlaybackState.AdState;
import com.google.android.exoplayer2.source.ads.AdsLoader;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads ads using the IMA SDK. All methods are called on the main thread.
 */
public final class ImaAdsLoader extends Player.DefaultEventListener implements AdsLoader,
    VideoAdPlayer, ContentProgressProvider, AdErrorListener, AdsLoadedListener, AdEventListener {

  static {
    ExoPlayerLibraryInfo.registerModule("goog.exo.ima");
  }

  /** Builder for {@link ImaAdsLoader}. */
  public static final class Builder {

    private final Context context;

    private @Nullable ImaSdkSettings imaSdkSettings;
    private long vastLoadTimeoutMs;

    /**
     * Creates a new builder for {@link ImaAdsLoader}.
     *
     * @param context The context;
     */
    public Builder(Context context) {
      this.context = Assertions.checkNotNull(context);
      vastLoadTimeoutMs = C.TIME_UNSET;
    }

    /**
     * Sets the IMA SDK settings. The provided settings instance's player type and version fields
     * may be overwritten.
     *
     * <p>If this method is not called the default settings will be used.
     *
     * @param imaSdkSettings The {@link ImaSdkSettings}.
     * @return This builder, for convenience.
     */
    public Builder setImaSdkSettings(ImaSdkSettings imaSdkSettings) {
      this.imaSdkSettings = Assertions.checkNotNull(imaSdkSettings);
      return this;
    }

    /**
     * Sets the VAST load timeout, in milliseconds.
     *
     * @param vastLoadTimeoutMs The VAST load timeout, in milliseconds.
     * @return This builder, for convenience.
     * @see AdsRequest#setVastLoadTimeout(float)
     */
    public Builder setVastLoadTimeoutMs(long vastLoadTimeoutMs) {
      Assertions.checkArgument(vastLoadTimeoutMs >= 0);
      this.vastLoadTimeoutMs = vastLoadTimeoutMs;
      return this;
    }

    /**
     * Returns a new {@link ImaAdsLoader} for the specified ad tag.
     *
     * @param adTagUri The URI of a compatible ad tag to load. See
     *     https://developers.google.com/interactive-media-ads/docs/sdks/android/compatibility for
     *     information on compatible ad tags.
     * @return The new {@link ImaAdsLoader}.
     */
    public ImaAdsLoader buildForAdTag(Uri adTagUri) {
      return new ImaAdsLoader(context, adTagUri, imaSdkSettings, null, vastLoadTimeoutMs);
    }

    /**
     * Returns a new {@link ImaAdsLoader} with the specified sideloaded ads response.
     *
     * @param adsResponse The sideloaded VAST, VMAP, or ad rules response to be used instead of
     *     making a request via an ad tag URL.
     * @return The new {@link ImaAdsLoader}.
     */
    public ImaAdsLoader buildForAdsResponse(String adsResponse) {
      return new ImaAdsLoader(context, null, imaSdkSettings, adsResponse, vastLoadTimeoutMs);
    }
  }

  private static final boolean DEBUG = false;
  private static final String TAG = "ImaAdsLoader";

  /**
   * Whether to enable preloading of ads in {@link AdsRenderingSettings}.
   */
  private static final boolean ENABLE_PRELOADING = true;

  private static final String IMA_SDK_SETTINGS_PLAYER_TYPE = "google/exo.ext.ima";
  private static final String IMA_SDK_SETTINGS_PLAYER_VERSION = ExoPlayerLibraryInfo.VERSION;

  /** The value used in {@link VideoProgressUpdate}s to indicate an unset duration. */
  private static final long IMA_DURATION_UNSET = -1L;

  /**
   * Threshold before the end of content at which IMA is notified that content is complete if the
   * player buffers, in milliseconds.
   */
  private static final long END_OF_CONTENT_POSITION_THRESHOLD_MS = 5000;

  /**
   * The "Skip ad" button rendered in the IMA WebView does not gain focus by default and cannot be
   * clicked via a keypress event. Workaround this issue by calling focus() on the HTML element in
   * the WebView directly when an ad starts. See [Internal: b/62371030].
   */
  private static final String FOCUS_SKIP_BUTTON_WORKAROUND_JS = "javascript:"
      + "try{ document.getElementsByClassName(\"videoAdUiSkipButton\")[0].focus(); } catch (e) {}";

  /** The state of ad playback. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({IMA_AD_STATE_NONE, IMA_AD_STATE_PLAYING, IMA_AD_STATE_PAUSED})
  private @interface ImaAdState {}
  /**
   * The ad playback state when IMA is not playing an ad.
   */
  private static final int IMA_AD_STATE_NONE = 0;
  /**
   * The ad playback state when IMA has called {@link #playAd()} and not {@link #pauseAd()}.
   */
  private static final int IMA_AD_STATE_PLAYING = 1;
  /**
   * The ad playback state when IMA has called {@link #pauseAd()} while playing an ad.
   */
  private static final int IMA_AD_STATE_PAUSED = 2;

  private final @Nullable Uri adTagUri;
  private final @Nullable String adsResponse;
  private final long vastLoadTimeoutMs;
  private final Timeline.Period period;
  private final List<VideoAdPlayerCallback> adCallbacks;
  private final ImaSdkFactory imaSdkFactory;
  private final AdDisplayContainer adDisplayContainer;
  private final com.google.ads.interactivemedia.v3.api.AdsLoader adsLoader;

  private Object pendingAdRequestContext;
  private List<String> supportedMimeTypes;
  private EventListener eventListener;
  private Player player;
  private ViewGroup adUiViewGroup;
  private VideoProgressUpdate lastContentProgress;
  private VideoProgressUpdate lastAdProgress;

  private AdsManager adsManager;
  private AdErrorEvent pendingAdErrorEvent;
  private Timeline timeline;
  private long contentDurationMs;
  private int podIndexOffset;
  private AdPlaybackState adPlaybackState;

  // Fields tracking IMA's state.

  /** The expected ad group index that IMA should load next. */
  private int expectedAdGroupIndex;
  /**
   * The index of the current ad group that IMA is loading.
   */
  private int adGroupIndex;
  /**
   * Whether IMA has sent an ad event to pause content since the last resume content event.
   */
  private boolean imaPausedContent;
  /** The current ad playback state. */
  private @ImaAdState int imaAdState;
  /**
   * Whether {@link com.google.ads.interactivemedia.v3.api.AdsLoader#contentComplete()} has been
   * called since starting ad playback.
   */
  private boolean sentContentComplete;

  // Fields tracking the player/loader state.

  /**
   * Whether the player is playing an ad.
   */
  private boolean playingAd;
  /**
   * If the player is playing an ad, stores the ad index in its ad group. {@link C#INDEX_UNSET}
   * otherwise.
   */
  private int playingAdIndexInAdGroup;
  /**
   * Whether there's a pending ad preparation error which IMA needs to be notified of when it
   * transitions from playing content to playing the ad.
   */
  private boolean shouldNotifyAdPrepareError;
  /**
   * If a content period has finished but IMA has not yet sent an ad event with {@link
   * AdEvent.AdEventType#CONTENT_PAUSE_REQUESTED}, stores the value of {@link
   * SystemClock#elapsedRealtime()} when the content stopped playing. This can be used to determine
   * a fake, increasing content position. {@link C#TIME_UNSET} otherwise.
   */
  private long fakeContentProgressElapsedRealtimeMs;
  /**
   * If {@link #fakeContentProgressElapsedRealtimeMs} is set, stores the offset from which the
   * content progress should increase. {@link C#TIME_UNSET} otherwise.
   */
  private long fakeContentProgressOffsetMs;
  /**
   * Stores the pending content position when a seek operation was intercepted to play an ad.
   */
  private long pendingContentPositionMs;
  /**
   * Whether {@link #getContentProgress()} has sent {@link #pendingContentPositionMs} to IMA.
   */
  private boolean sentPendingContentPositionMs;

  /**
   * Creates a new IMA ads loader.
   *
   * <p>If you need to customize the ad request, use {@link ImaAdsLoader.Builder} instead.
   *
   * @param context The context.
   * @param adTagUri The {@link Uri} of an ad tag compatible with the Android IMA SDK. See
   *     https://developers.google.com/interactive-media-ads/docs/sdks/android/compatibility for
   *     more information.
   */
  public ImaAdsLoader(Context context, Uri adTagUri) {
    this(context, adTagUri, null, null, C.TIME_UNSET);
  }

  /**
   * Creates a new IMA ads loader.
   *
   * @param context The context.
   * @param adTagUri The {@link Uri} of an ad tag compatible with the Android IMA SDK. See
   *     https://developers.google.com/interactive-media-ads/docs/sdks/android/compatibility for
   *     more information.
   * @param imaSdkSettings {@link ImaSdkSettings} used to configure the IMA SDK, or {@code null} to
   *     use the default settings. If set, the player type and version fields may be overwritten.
   * @deprecated Use {@link ImaAdsLoader.Builder}.
   */
  @Deprecated
  public ImaAdsLoader(Context context, Uri adTagUri, ImaSdkSettings imaSdkSettings) {
    this(context, adTagUri, imaSdkSettings, null, C.TIME_UNSET);
  }

  private ImaAdsLoader(
      Context context,
      @Nullable Uri adTagUri,
      @Nullable ImaSdkSettings imaSdkSettings,
      @Nullable String adsResponse,
      long vastLoadTimeoutMs) {
    Assertions.checkArgument(adTagUri != null || adsResponse != null);
    this.adTagUri = adTagUri;
    this.adsResponse = adsResponse;
    this.vastLoadTimeoutMs = vastLoadTimeoutMs;
    period = new Timeline.Period();
    adCallbacks = new ArrayList<>(1);
    imaSdkFactory = ImaSdkFactory.getInstance();
    adDisplayContainer = imaSdkFactory.createAdDisplayContainer();
    adDisplayContainer.setPlayer(this);
    if (imaSdkSettings == null) {
      imaSdkSettings = imaSdkFactory.createImaSdkSettings();
    }
    imaSdkSettings.setPlayerType(IMA_SDK_SETTINGS_PLAYER_TYPE);
    imaSdkSettings.setPlayerVersion(IMA_SDK_SETTINGS_PLAYER_VERSION);
    adsLoader = imaSdkFactory.createAdsLoader(context, imaSdkSettings);
    adsLoader.addAdErrorListener(this);
    adsLoader.addAdsLoadedListener(this);
    fakeContentProgressElapsedRealtimeMs = C.TIME_UNSET;
    fakeContentProgressOffsetMs = C.TIME_UNSET;
    pendingContentPositionMs = C.TIME_UNSET;
    adGroupIndex = C.INDEX_UNSET;
    contentDurationMs = C.TIME_UNSET;
  }

  /**
   * Returns the underlying {@code com.google.ads.interactivemedia.v3.api.AdsLoader} wrapped by
   * this instance.
   */
  public com.google.ads.interactivemedia.v3.api.AdsLoader getAdsLoader() {
    return adsLoader;
  }

  /**
   * Requests ads, if they have not already been requested. Must be called on the main thread.
   *
   * <p>Ads will be requested automatically when the player is prepared if this method has not been
   * called, so it is only necessary to call this method if you want to request ads before preparing
   * the player.
   *
   * @param adUiViewGroup A {@link ViewGroup} on top of the player that will show any ad UI.
   */
  public void requestAds(ViewGroup adUiViewGroup) {
    if (adPlaybackState != null || adsManager != null || pendingAdRequestContext != null) {
      // Ads have already been requested.
      return;
    }
    adDisplayContainer.setAdContainer(adUiViewGroup);
    pendingAdRequestContext = new Object();
    AdsRequest request = imaSdkFactory.createAdsRequest();
    if (adTagUri != null) {
      request.setAdTagUrl(adTagUri.toString());
    } else /* adsResponse != null */ {
      request.setAdsResponse(adsResponse);
    }
    if (vastLoadTimeoutMs != C.TIME_UNSET) {
      request.setVastLoadTimeout(vastLoadTimeoutMs);
    }
    request.setAdDisplayContainer(adDisplayContainer);
    request.setContentProgressProvider(this);
    request.setUserRequestContext(pendingAdRequestContext);
    adsLoader.requestAds(request);
  }

  // AdsLoader implementation.

  @Override
  public void setSupportedContentTypes(@C.ContentType int... contentTypes) {
    List<String> supportedMimeTypes = new ArrayList<>();
    for (@C.ContentType int contentType : contentTypes) {
      if (contentType == C.TYPE_DASH) {
        supportedMimeTypes.add(MimeTypes.APPLICATION_MPD);
      } else if (contentType == C.TYPE_HLS) {
        supportedMimeTypes.add(MimeTypes.APPLICATION_M3U8);
      } else if (contentType == C.TYPE_OTHER) {
        supportedMimeTypes.addAll(Arrays.asList(
            MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_WEBM, MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MPEG,
            MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_MPEG));
      } else if (contentType == C.TYPE_SS) {
        // IMA does not support Smooth Streaming ad media.
      }
    }
    this.supportedMimeTypes = Collections.unmodifiableList(supportedMimeTypes);
  }

  @Override
  public void attachPlayer(ExoPlayer player, EventListener eventListener, ViewGroup adUiViewGroup) {
    this.player = player;
    this.eventListener = eventListener;
    this.adUiViewGroup = adUiViewGroup;
    lastAdProgress = null;
    lastContentProgress = null;
    adDisplayContainer.setAdContainer(adUiViewGroup);
    player.addListener(this);
    maybeNotifyAdError();
    if (adPlaybackState != null) {
      // Pass the ad playback state to the player, and resume ads if necessary.
      eventListener.onAdPlaybackState(adPlaybackState);
      if (imaPausedContent && player.getPlayWhenReady()) {
        adsManager.resume();
      }
    } else if (adsManager != null) {
      // Ads have loaded but the ads manager is not initialized.
      startAdPlayback();
    } else {
      // Ads haven't loaded yet, so request them.
      requestAds(adUiViewGroup);
    }
  }

  @Override
  public void detachPlayer() {
    if (adsManager != null && imaPausedContent) {
      adPlaybackState =
          adPlaybackState.withAdResumePositionUs(
              playingAd ? C.msToUs(player.getCurrentPosition()) : 0);
      adsManager.pause();
    }
    lastAdProgress = getAdProgress();
    lastContentProgress = getContentProgress();
    player.removeListener(this);
    player = null;
    eventListener = null;
    adUiViewGroup = null;
  }

  @Override
  public void release() {
    pendingAdRequestContext = null;
    if (adsManager != null) {
      adsManager.destroy();
      adsManager = null;
    }
  }

  @Override
  public void handlePrepareError(int adGroupIndex, int adIndexInAdGroup, IOException exception) {
    if (player == null) {
      return;
    }
    if (DEBUG) {
      Log.d(
          TAG, "Prepare error for ad " + adIndexInAdGroup + " in group " + adGroupIndex, exception);
    }
    if (imaAdState == IMA_AD_STATE_NONE) {
      // Send IMA a content position at the ad group so that it will try to play it, at which point
      // we can notify that it failed to load.
      fakeContentProgressElapsedRealtimeMs = SystemClock.elapsedRealtime();
      fakeContentProgressOffsetMs = C.usToMs(adPlaybackState.adGroupTimesUs[adGroupIndex]);
      if (fakeContentProgressOffsetMs == C.TIME_END_OF_SOURCE) {
        fakeContentProgressOffsetMs = contentDurationMs;
      }
      shouldNotifyAdPrepareError = true;
    } else {
      // We're already playing an ad.
      if (adIndexInAdGroup > playingAdIndexInAdGroup) {
        // Mark the playing ad as ended so we can notify the error on the next ad and remove it,
        // which means that the ad after will load (if any).
        for (int i = 0; i < adCallbacks.size(); i++) {
          adCallbacks.get(i).onEnded();
        }
      }
      playingAdIndexInAdGroup = adPlaybackState.adGroups[adGroupIndex].getFirstAdIndexToPlay();
      for (int i = 0; i < adCallbacks.size(); i++) {
        adCallbacks.get(i).onError();
      }
    }
    adPlaybackState = adPlaybackState.withAdLoadError(adGroupIndex, adIndexInAdGroup);
    updateAdPlaybackState();
  }

  // com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener implementation.

  @Override
  public void onAdsManagerLoaded(AdsManagerLoadedEvent adsManagerLoadedEvent) {
    AdsManager adsManager = adsManagerLoadedEvent.getAdsManager();
    if (!Util.areEqual(pendingAdRequestContext, adsManagerLoadedEvent.getUserRequestContext())) {
      adsManager.destroy();
      return;
    }
    pendingAdRequestContext = null;
    this.adsManager = adsManager;
    adsManager.addAdErrorListener(this);
    adsManager.addAdEventListener(this);
    if (player != null) {
      // If a player is attached already, start playback immediately.
      startAdPlayback();
    }
  }

  // AdEvent.AdEventListener implementation.

  @Override
  public void onAdEvent(AdEvent adEvent) {
    AdEventType adEventType = adEvent.getType();
    if (DEBUG) {
      Log.d(TAG, "onAdEvent: " + adEventType);
    }
    if (adsManager == null) {
      Log.w(TAG, "Dropping ad event after release: " + adEvent);
      return;
    }
    Ad ad = adEvent.getAd();
    switch (adEvent.getType()) {
      case LOADED:
        // The ad position is not always accurate when using preloading. See [Internal: b/62613240].
        AdPodInfo adPodInfo = ad.getAdPodInfo();
        int podIndex = adPodInfo.getPodIndex();
        adGroupIndex =
            podIndex == -1 ? (adPlaybackState.adGroupCount - 1) : (podIndex + podIndexOffset);
        int adPosition = adPodInfo.getAdPosition();
        int adCount = adPodInfo.getTotalAds();
        adsManager.start();
        if (DEBUG) {
          Log.d(TAG, "Loaded ad " + adPosition + " of " + adCount + " in group " + adGroupIndex);
        }
        int oldAdCount = adPlaybackState.adGroups[adGroupIndex].count;
        if (adCount != oldAdCount) {
          if (oldAdCount == C.LENGTH_UNSET) {
            adPlaybackState = adPlaybackState.withAdCount(adGroupIndex, adCount);
            updateAdPlaybackState();
          } else {
            // IMA sometimes unexpectedly decreases the ad count in an ad group.
            Log.w(TAG, "Unexpected ad count in LOADED, " + adCount + ", expected " + oldAdCount);
          }
        }
        if (adGroupIndex != expectedAdGroupIndex) {
          Log.w(
              TAG,
              "Expected ad group index "
                  + expectedAdGroupIndex
                  + ", actual ad group index "
                  + adGroupIndex);
          expectedAdGroupIndex = adGroupIndex;
        }
        break;
      case CONTENT_PAUSE_REQUESTED:
        // After CONTENT_PAUSE_REQUESTED, IMA will playAd/pauseAd/stopAd to show one or more ads
        // before sending CONTENT_RESUME_REQUESTED.
        imaPausedContent = true;
        pauseContentInternal();
        break;
      case STARTED:
        if (ad.isSkippable()) {
          focusSkipButton();
        }
        break;
      case TAPPED:
        if (eventListener != null) {
          eventListener.onAdTapped();
        }
        break;
      case CLICKED:
        if (eventListener != null) {
          eventListener.onAdClicked();
        }
        break;
      case CONTENT_RESUME_REQUESTED:
        imaPausedContent = false;
        resumeContentInternal();
        break;
      case LOG:
        Map<String, String> adData = adEvent.getAdData();
        Log.i(TAG, "Log AdEvent: " + adData);
        if ("adLoadError".equals(adData.get("type"))) {
          handleAdGroupLoadError();
        }
        break;
      case ALL_ADS_COMPLETED:
      default:
        break;
    }
  }

  // AdErrorEvent.AdErrorListener implementation.

  @Override
  public void onAdError(AdErrorEvent adErrorEvent) {
    AdError error = adErrorEvent.getError();
    if (DEBUG) {
      Log.d(TAG, "onAdError", error);
    }
    if (adsManager == null) {
      // No ads were loaded, so allow playback to start without any ads.
      pendingAdRequestContext = null;
      adPlaybackState = new AdPlaybackState();
      updateAdPlaybackState();
    } else if (isAdGroupLoadError(error)) {
      handleAdGroupLoadError();
    }
    if (pendingAdErrorEvent == null) {
      pendingAdErrorEvent = adErrorEvent;
    }
    maybeNotifyAdError();
  }

  // ContentProgressProvider implementation.

  @Override
  public VideoProgressUpdate getContentProgress() {
    if (player == null) {
      return lastContentProgress;
    }
    boolean hasContentDuration = contentDurationMs != C.TIME_UNSET;
    long contentPositionMs;
    if (pendingContentPositionMs != C.TIME_UNSET) {
      sentPendingContentPositionMs = true;
      contentPositionMs = pendingContentPositionMs;
      expectedAdGroupIndex =
          adPlaybackState.getAdGroupIndexForPositionUs(C.msToUs(contentPositionMs));
    } else if (fakeContentProgressElapsedRealtimeMs != C.TIME_UNSET) {
      long elapsedSinceEndMs = SystemClock.elapsedRealtime() - fakeContentProgressElapsedRealtimeMs;
      contentPositionMs = fakeContentProgressOffsetMs + elapsedSinceEndMs;
      expectedAdGroupIndex =
          adPlaybackState.getAdGroupIndexForPositionUs(C.msToUs(contentPositionMs));
    } else if (imaAdState == IMA_AD_STATE_NONE && hasContentDuration) {
      contentPositionMs = player.getCurrentPosition();
      // Keep track of the ad group index that IMA will load for the current content position.
      expectedAdGroupIndex =
          adPlaybackState.getAdGroupIndexAfterPositionUs(C.msToUs(contentPositionMs));
    } else {
      return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
    }
    long contentDurationMs = hasContentDuration ? this.contentDurationMs : IMA_DURATION_UNSET;
    return new VideoProgressUpdate(contentPositionMs, contentDurationMs);
  }

  // VideoAdPlayer implementation.

  @Override
  public VideoProgressUpdate getAdProgress() {
    if (player == null) {
      return lastAdProgress;
    } else if (imaAdState == IMA_AD_STATE_NONE) {
      return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
    } else {
      long adDuration = player.getDuration();
      return adDuration == C.TIME_UNSET ? VideoProgressUpdate.VIDEO_TIME_NOT_READY
          : new VideoProgressUpdate(player.getCurrentPosition(), adDuration);
    }
  }

  @Override
  public void loadAd(String adUriString) {
    if (adGroupIndex == C.INDEX_UNSET) {
      Log.w(
          TAG,
          "Unexpected loadAd without LOADED event; assuming ad group index is actually "
              + expectedAdGroupIndex);
      adGroupIndex = expectedAdGroupIndex;
      adsManager.start();
    }
    if (DEBUG) {
      Log.d(TAG, "loadAd in ad group " + adGroupIndex);
    }
    int adIndexInAdGroup = getAdIndexInAdGroupToLoad(adGroupIndex);
    adPlaybackState =
        adPlaybackState.withAdUri(adGroupIndex, adIndexInAdGroup, Uri.parse(adUriString));
    updateAdPlaybackState();
  }

  @Override
  public void addCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
    adCallbacks.add(videoAdPlayerCallback);
  }

  @Override
  public void removeCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
    adCallbacks.remove(videoAdPlayerCallback);
  }

  @Override
  public void playAd() {
    if (DEBUG) {
      Log.d(TAG, "playAd");
    }
    switch (imaAdState) {
      case IMA_AD_STATE_PLAYING:
        // IMA does not always call stopAd before resuming content.
        // See [Internal: b/38354028, b/63320878].
        Log.w(TAG, "Unexpected playAd without stopAd");
        break;
      case IMA_AD_STATE_NONE:
        imaAdState = IMA_AD_STATE_PLAYING;
        for (int i = 0; i < adCallbacks.size(); i++) {
          adCallbacks.get(i).onPlay();
        }
        if (shouldNotifyAdPrepareError) {
          shouldNotifyAdPrepareError = false;
          for (int i = 0; i < adCallbacks.size(); i++) {
            adCallbacks.get(i).onError();
          }
        }
        break;
      case IMA_AD_STATE_PAUSED:
        imaAdState = IMA_AD_STATE_PLAYING;
        for (int i = 0; i < adCallbacks.size(); i++) {
          adCallbacks.get(i).onResume();
        }
        break;
      default:
        throw new IllegalStateException();
    }
    if (player == null) {
      // Sometimes messages from IMA arrive after detaching the player. See [Internal: b/63801642].
      Log.w(TAG, "Unexpected playAd while detached");
    } else if (!player.getPlayWhenReady()) {
      adsManager.pause();
    }
  }

  @Override
  public void stopAd() {
    if (DEBUG) {
      Log.d(TAG, "stopAd");
    }
    if (player == null) {
      // Sometimes messages from IMA arrive after detaching the player. See [Internal: b/63801642].
      Log.w(TAG, "Unexpected stopAd while detached");
    }
    if (imaAdState == IMA_AD_STATE_NONE) {
      Log.w(TAG, "Unexpected stopAd");
      return;
    }
    stopAdInternal();
  }

  @Override
  public void pauseAd() {
    if (DEBUG) {
      Log.d(TAG, "pauseAd");
    }
    if (imaAdState == IMA_AD_STATE_NONE) {
      // This method is called after content is resumed.
      return;
    }
    imaAdState = IMA_AD_STATE_PAUSED;
    for (int i = 0; i < adCallbacks.size(); i++) {
      adCallbacks.get(i).onPause();
    }
  }

  @Override
  public void resumeAd() {
    // This method is never called. See [Internal: b/18931719].
    throw new IllegalStateException();
  }

  // Player.EventListener implementation.

  @Override
  public void onTimelineChanged(Timeline timeline, Object manifest,
      @Player.TimelineChangeReason int reason) {
    if (reason == Player.TIMELINE_CHANGE_REASON_RESET) {
      // The player is being reset and this source will be released.
      return;
    }
    Assertions.checkArgument(timeline.getPeriodCount() == 1);
    this.timeline = timeline;
    long contentDurationUs = timeline.getPeriod(0, period).durationUs;
    contentDurationMs = C.usToMs(contentDurationUs);
    if (contentDurationUs != C.TIME_UNSET) {
      adPlaybackState = adPlaybackState.withContentDurationUs(contentDurationUs);
    }
    updateImaStateForPlayerState();
  }

  @Override
  public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
    if (adsManager == null) {
      return;
    }

    if (imaAdState == IMA_AD_STATE_PLAYING && !playWhenReady) {
      adsManager.pause();
      return;
    }

    if (imaAdState == IMA_AD_STATE_PAUSED && playWhenReady) {
      adsManager.resume();
      return;
    }

    if (imaAdState == IMA_AD_STATE_NONE && playbackState == Player.STATE_BUFFERING
        && playWhenReady) {
      checkForContentComplete();
    } else if (imaAdState != IMA_AD_STATE_NONE && playbackState == Player.STATE_ENDED) {
      // IMA is waiting for the ad playback to finish so invoke the callback now.
      // Either CONTENT_RESUME_REQUESTED will be passed next, or playAd will be called again.
      for (int i = 0; i < adCallbacks.size(); i++) {
        adCallbacks.get(i).onEnded();
      }
      if (DEBUG) {
        Log.d(TAG, "VideoAdPlayerCallback.onEnded in onPlayerStateChanged");
      }
    }
  }

  @Override
  public void onPlayerError(ExoPlaybackException error) {
    if (imaAdState != IMA_AD_STATE_NONE) {
      for (int i = 0; i < adCallbacks.size(); i++) {
        adCallbacks.get(i).onError();
      }
    }
  }

  @Override
  public void onPositionDiscontinuity(@Player.DiscontinuityReason int reason) {
    if (adsManager == null) {
      return;
    }
    if (!playingAd && !player.isPlayingAd()) {
      checkForContentComplete();
      if (sentContentComplete) {
        for (int i = 0; i < adPlaybackState.adGroupCount; i++) {
          if (adPlaybackState.adGroupTimesUs[i] != C.TIME_END_OF_SOURCE) {
            adPlaybackState = adPlaybackState.withSkippedAdGroup(i);
          }
        }
        updateAdPlaybackState();
      } else {
        long positionMs = player.getCurrentPosition();
        timeline.getPeriod(0, period);
        int newAdGroupIndex = period.getAdGroupIndexForPositionUs(C.msToUs(positionMs));
        if (newAdGroupIndex != C.INDEX_UNSET) {
          sentPendingContentPositionMs = false;
          pendingContentPositionMs = positionMs;
          if (newAdGroupIndex != adGroupIndex) {
            shouldNotifyAdPrepareError = false;
          }
        }
      }
    } else {
      updateImaStateForPlayerState();
    }
  }

  // Internal methods.

  private void startAdPlayback() {
    ImaSdkFactory imaSdkFactory = ImaSdkFactory.getInstance();
    AdsRenderingSettings adsRenderingSettings = imaSdkFactory.createAdsRenderingSettings();
    adsRenderingSettings.setEnablePreloading(ENABLE_PRELOADING);
    adsRenderingSettings.setMimeTypes(supportedMimeTypes);

    // Set up the ad playback state, skipping ads based on the start position as required.
    pendingContentPositionMs = player.getCurrentPosition();
    long[] adGroupTimesUs = getAdGroupTimesUs(adsManager.getAdCuePoints());
    adPlaybackState = new AdPlaybackState(adGroupTimesUs);
    int adGroupIndexForPosition =
        adPlaybackState.getAdGroupIndexForPositionUs(C.msToUs(pendingContentPositionMs));
    if (adGroupIndexForPosition == 0) {
      podIndexOffset = 0;
    } else if (adGroupIndexForPosition == C.INDEX_UNSET) {
      pendingContentPositionMs = C.TIME_UNSET;
      // There is no preroll and midroll pod indices start at 1.
      podIndexOffset = -1;
    } else /* adGroupIndexForPosition > 0 */ {
      // Skip ad groups before the one at or immediately before the playback position.
      for (int i = 0; i < adGroupIndexForPosition; i++) {
        adPlaybackState = adPlaybackState.withSkippedAdGroup(i);
      }
      // Play ads after the midpoint between the ad to play and the one before it, to avoid issues
      // with rounding one of the two ad times.
      long adGroupForPositionTimeUs = adGroupTimesUs[adGroupIndexForPosition];
      long adGroupBeforeTimeUs = adGroupTimesUs[adGroupIndexForPosition - 1];
      double midpointTimeUs = (adGroupForPositionTimeUs + adGroupBeforeTimeUs) / 2d;
      adsRenderingSettings.setPlayAdsAfterTime(midpointTimeUs / C.MICROS_PER_SECOND);

      // We're removing one or more ads, which means that the earliest ad (if any) will be a
      // midroll/postroll. Midroll pod indices start at 1.
      podIndexOffset = adGroupIndexForPosition - 1;
    }

    // Start ad playback.
    adsManager.init(adsRenderingSettings);
    updateAdPlaybackState();
    if (DEBUG) {
      Log.d(TAG, "Initialized with ads rendering settings: " + adsRenderingSettings);
    }
  }

  private void maybeNotifyAdError() {
    if (eventListener != null && pendingAdErrorEvent != null) {
      IOException exception =
          new IOException("Ad error: " + pendingAdErrorEvent, pendingAdErrorEvent.getError());
      eventListener.onLoadError(exception);
      pendingAdErrorEvent = null;
    }
  }

  private void updateImaStateForPlayerState() {
    boolean wasPlayingAd = playingAd;
    int oldPlayingAdIndexInAdGroup = playingAdIndexInAdGroup;
    playingAd = player.isPlayingAd();
    playingAdIndexInAdGroup = playingAd ? player.getCurrentAdIndexInAdGroup() : C.INDEX_UNSET;
    if (!sentContentComplete) {
      boolean adFinished = wasPlayingAd && playingAdIndexInAdGroup != oldPlayingAdIndexInAdGroup;
      if (adFinished) {
        // IMA is waiting for the ad playback to finish so invoke the callback now.
        // Either CONTENT_RESUME_REQUESTED will be passed next, or playAd will be called again.
        for (int i = 0; i < adCallbacks.size(); i++) {
          adCallbacks.get(i).onEnded();
        }
        if (DEBUG) {
          Log.d(TAG, "VideoAdPlayerCallback.onEnded in onTimelineChanged/onPositionDiscontinuity");
        }
      }
      if (!wasPlayingAd && playingAd) {
        int adGroupIndex = player.getCurrentAdGroupIndex();
        // IMA hasn't sent CONTENT_PAUSE_REQUESTED yet, so fake the content position.
        fakeContentProgressElapsedRealtimeMs = SystemClock.elapsedRealtime();
        fakeContentProgressOffsetMs = C.usToMs(adPlaybackState.adGroupTimesUs[adGroupIndex]);
        if (fakeContentProgressOffsetMs == C.TIME_END_OF_SOURCE) {
          fakeContentProgressOffsetMs = contentDurationMs;
        }
      }
    }
  }

  private void resumeContentInternal() {
    if (imaAdState != IMA_AD_STATE_NONE) {
      imaAdState = IMA_AD_STATE_NONE;
      if (DEBUG) {
        Log.d(TAG, "Unexpected CONTENT_RESUME_REQUESTED without stopAd");
      }
    }
    if (adGroupIndex != C.INDEX_UNSET) {
      adPlaybackState = adPlaybackState.withSkippedAdGroup(adGroupIndex);
      adGroupIndex = C.INDEX_UNSET;
      updateAdPlaybackState();
    }
  }

  private void pauseContentInternal() {
    imaAdState = IMA_AD_STATE_NONE;
    if (sentPendingContentPositionMs) {
      pendingContentPositionMs = C.TIME_UNSET;
      sentPendingContentPositionMs = false;
    }
    // IMA is requesting to pause content, so stop faking the content position.
    fakeContentProgressElapsedRealtimeMs = C.TIME_UNSET;
    fakeContentProgressOffsetMs = C.TIME_UNSET;
  }

  private void stopAdInternal() {
    imaAdState = IMA_AD_STATE_NONE;
    int adIndexInAdGroup = adPlaybackState.adGroups[adGroupIndex].getFirstAdIndexToPlay();
    // TODO: Handle the skipped event so the ad can be marked as skipped rather than played.
    adPlaybackState =
        adPlaybackState.withPlayedAd(adGroupIndex, adIndexInAdGroup).withAdResumePositionUs(0);
    updateAdPlaybackState();
    if (!playingAd) {
      adGroupIndex = C.INDEX_UNSET;
    }
  }

  private void handleAdGroupLoadError() {
    int adGroupIndex =
        this.adGroupIndex == C.INDEX_UNSET ? expectedAdGroupIndex : this.adGroupIndex;
    AdPlaybackState.AdGroup adGroup = adPlaybackState.adGroups[adGroupIndex];
    if (adGroup.count == C.LENGTH_UNSET) {
      adPlaybackState =
          adPlaybackState.withAdCount(adGroupIndex, Math.max(1, adGroup.states.length));
    }
    for (int i = 0; i < adGroup.count; i++) {
      if (adGroup.states[i] == AdPlaybackState.AD_STATE_UNAVAILABLE) {
        if (DEBUG) {
          Log.d(TAG, "Removing ad " + i + " in ad group " + adGroupIndex);
        }
        adPlaybackState = adPlaybackState.withAdLoadError(adGroupIndex, i);
      }
    }
    updateAdPlaybackState();
  }

  private void checkForContentComplete() {
    if (contentDurationMs != C.TIME_UNSET && pendingContentPositionMs == C.TIME_UNSET
        && player.getContentPosition() + END_OF_CONTENT_POSITION_THRESHOLD_MS >= contentDurationMs
        && !sentContentComplete) {
      adsLoader.contentComplete();
      if (DEBUG) {
        Log.d(TAG, "adsLoader.contentComplete");
      }
      sentContentComplete = true;
    }
  }

  private void updateAdPlaybackState() {
    // Ignore updates while detached. When a player is attached it will receive the latest state.
    if (eventListener != null) {
      eventListener.onAdPlaybackState(adPlaybackState);
    }
  }

  private void focusSkipButton() {
    if (playingAd && adUiViewGroup != null && adUiViewGroup.getChildCount() > 0
        && adUiViewGroup.getChildAt(0) instanceof WebView) {
      WebView webView = (WebView) (adUiViewGroup.getChildAt(0));
      webView.requestFocus();
      webView.loadUrl(FOCUS_SKIP_BUTTON_WORKAROUND_JS);
    }
  }

  /**
   * Returns the next ad index in the specified ad group to load, or {@link C#INDEX_UNSET} if all
   * ads in the ad group have loaded.
   */
  private int getAdIndexInAdGroupToLoad(int adGroupIndex) {
    @AdState int[] states = adPlaybackState.adGroups[adGroupIndex].states;
    int adIndexInAdGroup = 0;
    // IMA loads ads in order.
    while (adIndexInAdGroup < states.length
        && states[adIndexInAdGroup] != AdPlaybackState.AD_STATE_UNAVAILABLE) {
      adIndexInAdGroup++;
    }
    return adIndexInAdGroup == states.length ? C.INDEX_UNSET : adIndexInAdGroup;
  }

  private static long[] getAdGroupTimesUs(List<Float> cuePoints) {
    if (cuePoints.isEmpty()) {
      // If no cue points are specified, there is a preroll ad.
      return new long[] {0};
    }

    int count = cuePoints.size();
    long[] adGroupTimesUs = new long[count];
    int adGroupIndex = 0;
    for (int i = 0; i < count; i++) {
      double cuePoint = cuePoints.get(i);
      if (cuePoint == -1.0) {
        adGroupTimesUs[count - 1] = C.TIME_END_OF_SOURCE;
      } else {
        adGroupTimesUs[adGroupIndex++] = (long) (C.MICROS_PER_SECOND * cuePoint);
      }
    }
    // Cue points may be out of order, so sort them.
    Arrays.sort(adGroupTimesUs, 0, adGroupIndex);
    return adGroupTimesUs;
  }

  private static boolean isAdGroupLoadError(AdError adError) {
    // TODO: Find out what other errors need to be handled (if any), and whether each one relates to
    // a single ad, ad group or the whole timeline.
    return adError.getErrorCode() == AdErrorCode.VAST_LINEAR_ASSET_MISMATCH;
  }
}
