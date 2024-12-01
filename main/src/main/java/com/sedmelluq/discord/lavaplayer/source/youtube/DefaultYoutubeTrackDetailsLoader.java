package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.PLAYER_URL;
import static com.sedmelluq.discord.lavaplayer.tools.ExceptionTools.throwWithDebugInfo;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static java.nio.charset.StandardCharsets.UTF_8;

public class DefaultYoutubeTrackDetailsLoader implements YoutubeTrackDetailsLoader {
  private static final Logger log = LoggerFactory.getLogger(DefaultYoutubeTrackDetailsLoader.class);

  private YoutubeAccessTokenTracker tokenTracker;

  private volatile CachedPlayerScript cachedPlayerScript = null;

  @Override
  public void setTokenTracker(YoutubeAccessTokenTracker tokenTracker) {
    this.tokenTracker = tokenTracker;
  }

  @Override
  public YoutubeTrackDetails loadDetails(HttpInterface httpInterface, String videoId, boolean requireFormats, YoutubeAudioSourceManager sourceManager, YoutubeClientConfig clientConfig) {
    try {
      return load(httpInterface, videoId, requireFormats, sourceManager, clientConfig);
    } catch (IOException e) {
      throw ExceptionTools.toRuntimeException(e);
    }
  }

  @Override
  public YoutubeTrackDetails loadDetails(HttpInterface httpInterface, String videoId, boolean requireFormats, YoutubeAudioSourceManager sourceManager) {
    try {
      return load(httpInterface, videoId, requireFormats, sourceManager, null);
    } catch (IOException e) {
      throw ExceptionTools.toRuntimeException(e);
    }
  }

  private YoutubeTrackDetails load(
      HttpInterface httpInterface,
      String videoId,
      boolean requireFormats,
      YoutubeAudioSourceManager sourceManager,
      YoutubeClientConfig clientConfig
  ) throws IOException {
    JsonBrowser mainInfo = loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager, null, clientConfig);

    try {
      YoutubeTrackJsonData initialData = loadBaseResponse(mainInfo, httpInterface, videoId, sourceManager);

      if (initialData == null) {
        return null;
      }

      String responseVideoId = initialData.playerResponse.get("videoDetails").get("videoId").text();

      if (!videoId.equals(responseVideoId)) {
        if (clientConfig == null) {
          log.warn("Received different YouTube video ({}, want: {}) to what was requested, retrying with WEB client...", responseVideoId, videoId);
          return load(httpInterface, videoId, requireFormats, sourceManager, YoutubeClientConfig.WEB);
        }

        throw new FriendlyException("Video returned by YouTube isn't what was requested", COMMON,
            new IllegalStateException(initialData.playerResponse.format()));
      }

      YoutubeTrackJsonData finalData = augmentWithPlayerScript(initialData, httpInterface, videoId, requireFormats);
      return new DefaultYoutubeTrackDetails(videoId, finalData);
    } catch (FriendlyException e) {
      throw e;
    } catch (Exception e) {
      throw throwWithDebugInfo(log, e, "Error when extracting data", "mainJson", mainInfo.format());
    }
  }

  protected YoutubeTrackJsonData loadBaseResponse(
      JsonBrowser mainInfo,
      HttpInterface httpInterface,
      String videoId,
      YoutubeAudioSourceManager sourceManager
  ) throws IOException {
    YoutubeTrackJsonData data = YoutubeTrackJsonData.fromMainResult(mainInfo);
    InfoStatus status = checkPlayabilityStatus(data.playerResponse, false);

    if (status == InfoStatus.DOES_NOT_EXIST) {
      return null;
    }

    if (status == InfoStatus.PREMIERE_TRAILER) {
      JsonBrowser trackInfo = loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager, status, null);
      data = YoutubeTrackJsonData.fromMainResult(trackInfo
          .get("playabilityStatus")
          .get("errorScreen")
          .get("ypcTrailerRenderer")
          .get("unserializedPlayerResponse")
      );
      status = checkPlayabilityStatus(data.playerResponse, true);
    }

    if (status == InfoStatus.REQUIRES_LOGIN) {
      JsonBrowser trackInfo = loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager, status, null);
      data = YoutubeTrackJsonData.fromMainResult(trackInfo);
      status = checkPlayabilityStatus(data.playerResponse, true);
    }

    if (status == InfoStatus.NON_EMBEDDABLE) {
      JsonBrowser trackInfo = loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager, status, null);
      data = YoutubeTrackJsonData.fromMainResult(trackInfo);
      checkPlayabilityStatus(data.playerResponse, true);
    }

    return data;
  }

  protected InfoStatus checkPlayabilityStatus(JsonBrowser playerResponse, boolean secondCheck) {
    JsonBrowser statusBlock = playerResponse.get("playabilityStatus");

    if (statusBlock.isNull()) {
      throw new RuntimeException("No playability status block.");
    }

    String status = statusBlock.get("status").text();

    if (status == null) {
      throw new RuntimeException("No playability status field.");
    } else if ("OK".equals(status)) {
      return InfoStatus.INFO_PRESENT;
    } else if ("ERROR".equals(status)) {
      String errorReason = statusBlock.get("reason").text();

      if (errorReason.contains("This video is unavailable")) {
        return InfoStatus.DOES_NOT_EXIST;
      } else {
        throw new FriendlyException(errorReason, COMMON, null);
      }
    } else if ("UNPLAYABLE".equals(status)) {
      String unplayableReason = getUnplayableReason(statusBlock);

      if (unplayableReason.contains("Playback on other websites has been disabled by the video owner")) {
        return InfoStatus.NON_EMBEDDABLE;
      }

      throw new FriendlyException(unplayableReason, COMMON, null);
    } else if ("LOGIN_REQUIRED".equals(status)) {
      String loginReason = statusBlock.get("reason").safeText();

      if (loginReason.contains("This video is private")) {
        throw new FriendlyException("This is a private video.", COMMON, null);
      }

      if (loginReason.contains("This video may be inappropriate for some users") && secondCheck) {
        throw new FriendlyException("This video requires age verification.", SUSPICIOUS,
            new IllegalStateException("You did not set email and password in YoutubeAudioSourceManager."));
      }

      return InfoStatus.REQUIRES_LOGIN;
    } else if ("CONTENT_CHECK_REQUIRED".equals(status)) {
      throw new FriendlyException(getUnplayableReason(statusBlock), COMMON, null);
    } else if ("LIVE_STREAM_OFFLINE".equals(status)) {
      if (!statusBlock.get("errorScreen").get("ypcTrailerRenderer").isNull()) {
        return InfoStatus.PREMIERE_TRAILER;
      }

      throw new FriendlyException(getUnplayableReason(statusBlock), COMMON, null);
    } else {
      throw new FriendlyException("This video cannot be viewed anonymously.", COMMON, null);
    }
  }

  protected enum InfoStatus {
    INFO_PRESENT,
    REQUIRES_LOGIN,
    DOES_NOT_EXIST,
    CONTENT_CHECK_REQUIRED,
    LIVE_STREAM_OFFLINE,
    PREMIERE_TRAILER,
    NON_EMBEDDABLE
  }

  protected String getUnplayableReason(JsonBrowser statusBlock) {
    JsonBrowser playerErrorMessage = statusBlock.get("errorScreen").get("playerErrorMessageRenderer");
    String unplayableReason = statusBlock.get("reason").text();

    if (!playerErrorMessage.get("subreason").isNull()) {
      JsonBrowser subreason = playerErrorMessage.get("subreason");

      if (!subreason.get("simpleText").isNull()) {
        unplayableReason = subreason.get("simpleText").text();
      } else if (!subreason.get("runs").isNull() && subreason.get("runs").isList()) {
        StringBuilder reasonBuilder = new StringBuilder();
        subreason.get("runs").values().forEach(
            item -> reasonBuilder.append(item.get("text").text()).append('\n')
        );
        unplayableReason = reasonBuilder.toString();
      }
    }

    return unplayableReason;
  }

  protected JsonBrowser loadTrackInfoFromInnertube(
      HttpInterface httpInterface,
      String videoId,
      YoutubeAudioSourceManager sourceManager,
      InfoStatus infoStatus,
      YoutubeClientConfig clientOverride
  ) throws IOException {
    if (cachedPlayerScript == null) fetchScript(videoId, httpInterface);

    YoutubeSignatureCipher playerScriptTimestamp = sourceManager.getSignatureResolver().getExtractedScript(
        httpInterface,
        cachedPlayerScript.playerScriptUrl
    );
    HttpPost post = new HttpPost(PLAYER_URL);
    YoutubeClientConfig config = clientOverride;

    if (config == null) {
      if (infoStatus == InfoStatus.PREMIERE_TRAILER) { // Base64 protobuf response, requires WEB
        config = YoutubeClientConfig.WEB.copy();
      } else if (infoStatus == InfoStatus.NON_EMBEDDABLE) { // When age restriction bypass fails, this request should succeed if we have valid auth.
        config = YoutubeClientConfig.ANDROID.copy()
            .withRootField("params", YoutubeConstants.PLAYER_PARAMS);
      } else if (infoStatus == InfoStatus.REQUIRES_LOGIN) { // Age restriction, requires TV_EMBEDDED
        config = YoutubeClientConfig.TV_EMBEDDED.copy();
      } else { // Default payload from what we start trying to get required data
        config = YoutubeClientConfig.ANDROID.copy()
            .withClientField("clientScreen", "EMBED")
            .withThirdPartyEmbedUrl("https://google.com")
            .withRootField("params", YoutubeConstants.PLAYER_PARAMS);
      }
    }

    String payload = config.setAttributes(httpInterface)
        .withRootField("racyCheckOk", true)
        .withRootField("contentCheckOk", true)
        .withRootField("videoId", videoId)
        .withClientField("visitorData", tokenTracker.getVisitorId())
        .withPlaybackSignatureTimestamp(playerScriptTimestamp.scriptTimestamp)
        .toJsonString();

    log.debug("Loading track info with payload: {}", payload);

    post.setEntity(new StringEntity(payload, "UTF-8"));
    try (CloseableHttpResponse response = httpInterface.execute(post)) {
      HttpClientTools.assertSuccessWithContent(response, "video page response");

      String responseText = EntityUtils.toString(response.getEntity(), UTF_8);

      try {
        return JsonBrowser.parse(responseText);
      } catch (FriendlyException e) {
        throw e;
      } catch (Exception e) {
        if ("Invalid status code for video page response: 400".equals(e.getMessage()) && clientOverride == null) {
          YoutubeClientConfig retryConfig = YoutubeClientConfig.WEB.copy()
              .withClientField("clientScreen", "EMBED")
              .withThirdPartyEmbedUrl("https://google.com");

          return loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager, infoStatus, retryConfig);
        }

        throw new FriendlyException("Received unexpected response from YouTube.", SUSPICIOUS,
            new RuntimeException("Failed to parse: " + responseText, e));
      }
    }
  }

  protected YoutubeTrackJsonData augmentWithPlayerScript(
      YoutubeTrackJsonData data,
      HttpInterface httpInterface,
      String videoId,
      boolean requireFormats
  ) throws IOException {
    long now = System.currentTimeMillis();

    if (data.playerScriptUrl != null) {
      cachedPlayerScript = new CachedPlayerScript(data.playerScriptUrl, now);
      return data;
    } else if (!requireFormats) {
      return data;
    }

    CachedPlayerScript cached = cachedPlayerScript;

    if (cached != null && cached.timestamp + 600000L >= now) {
      return data.withPlayerScriptUrl(cached.playerScriptUrl);
    }

    return data.withPlayerScriptUrl(fetchScript(videoId, httpInterface));
  }

  private String fetchScript(String videoId, HttpInterface httpInterface) throws IOException {
    long now = System.currentTimeMillis();

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://www.youtube.com/embed/" + videoId))) {
      HttpClientTools.assertSuccessWithContent(response, "youtube embed video id");

      String responseText = EntityUtils.toString(response.getEntity());
      String encodedUrl = DataFormatTools.extractBetween(responseText, "\"jsUrl\":\"", "\"");

      if (encodedUrl == null) {
        throw throwWithDebugInfo(log, null, "no jsUrl found", "html", responseText);
      }

      String fetchedPlayerScript = JsonBrowser.parse("{\"url\":\"" + encodedUrl + "\"}").get("url").text();
      cachedPlayerScript = new CachedPlayerScript(fetchedPlayerScript, now);

      return fetchedPlayerScript;
    }
  }

  protected static class CachedPlayerScript {
    public final String playerScriptUrl;
    public final long timestamp;

    public CachedPlayerScript(String playerScriptUrl, long timestamp) {
      this.playerScriptUrl = playerScriptUrl;
      this.timestamp = timestamp;
    }
  }
}