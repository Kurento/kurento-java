/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.test.functional.player;

import java.awt.Color;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.kurento.client.EndOfStreamEvent;
import org.kurento.client.EventListener;
import org.kurento.client.MediaFlowInStateChangeEvent;
import org.kurento.client.MediaFlowOutStateChangeEvent;
import org.kurento.client.MediaFlowState;
import org.kurento.client.MediaPipeline;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.test.base.PlayerTest;
import org.kurento.test.browser.WebRtcChannel;
import org.kurento.test.browser.WebRtcMode;
import org.kurento.test.config.Protocol;
import org.kurento.test.config.VideoFormat;
import org.kurento.test.utils.CheckAudioTimerTask;

/**
 * Base for player tests.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 6.1.1
 */
public class SimplePlayer extends PlayerTest {

  public void testPlayerWithRtsp(WebRtcChannel webRtcChannel) throws Exception {
    getPage().getBrowser().setTimeout(200);
    testPlayer("rtsp://mm2.pcslab.com/mm/7m2000.mp4", webRtcChannel, 0.0, 0.0, 50, 50, Color.BLACK);
  }

  public void testPlayerWithSmallFileVideoOnly(Protocol protocol, VideoFormat videoFormat,
      WebRtcChannel webRtcChannel) throws InterruptedException {
    testPlayerWithSmallFile(protocol, videoFormat, webRtcChannel, true);
  }

  public void testPlayerWithSmallFile(Protocol protocol, VideoFormat videoFormat,
      WebRtcChannel webRtcChannel) throws InterruptedException {
    testPlayerWithSmallFile(protocol, videoFormat, webRtcChannel, false);
  }

  private void testPlayerWithSmallFile(Protocol protocol, VideoFormat videoFormat,
      WebRtcChannel webRtcChannel, boolean videoOnly) throws InterruptedException {
    String nameMedia = "/video/format/";
    nameMedia += videoOnly ? "small_video_only." : "small.";
    nameMedia += videoFormat.toString();

    String mediaUrl = getMediaUrl(protocol, nameMedia);

    final double playTime = 5.0; // seconds
    final double thresholdTime = playTime * 0.30; // seconds

    final int x = 100;
    final int y = 100;
    final Color expectedColor = new Color(128, 85, 46);

    log.debug(
        ">>>> Playing small video ({}), URL: '{}', playtime: {} seconds, expected color {} at ({}, {})",
        webRtcChannel, mediaUrl, playTime, expectedColor, x, y);

    // Set maximum play time allowed to diverge from the expected one
    getPage().setThresholdTime(thresholdTime);

    testPlayer(mediaUrl, webRtcChannel, playTime, thresholdTime, x, y, expectedColor);
  }

  public void testPlayer(String mediaUrl, WebRtcChannel webRtcChannel, double playtime,
      double thresholdTime) throws InterruptedException {
    testPlayer(mediaUrl, webRtcChannel, playtime, thresholdTime, 0, 0, null);
  }

  public void testPlayer(String mediaUrl, WebRtcChannel webRtcChannel, double playtime,
      double thresholdTime, int x, int y, Color expectedColor) throws InterruptedException {
    Timer gettingStats = new Timer();

    // Media Pipeline
    MediaPipeline mp = kurentoClient.createMediaPipeline();
    PlayerEndpoint playerEp = new PlayerEndpoint.Builder(mp, mediaUrl).build();
    WebRtcEndpoint webRtcEp = new WebRtcEndpoint.Builder(mp).build();
    playerEp.connect(webRtcEp);

    final CountDownLatch flowingLatch = new CountDownLatch(1);
    webRtcEp.addMediaFlowInStateChangeListener(new EventListener<MediaFlowInStateChangeEvent>() {
      @Override
      public void onEvent(MediaFlowInStateChangeEvent event) {
        log.debug(
            "[Kms.WebRtcEndpoint.MediaFlowInStateChange] -> endpoint: {}, mediaType: {}, state: {}",
            webRtcEp.getId(), event.getMediaType(), event.getState());
        if (event.getState().equals(MediaFlowState.FLOWING)) {
          flowingLatch.countDown();
        }
      }
    });

    playerEp.addMediaFlowOutStateChangeListener(new EventListener<MediaFlowOutStateChangeEvent>() {
      @Override
      public void onEvent(MediaFlowOutStateChangeEvent event) {
        log.debug(
            "[Kms.PlayerEndpoint.MediaFlowOutStateChange] -> endpoint: {}, mediaType: {}, state: {}",
            playerEp.getId(), event.getMediaType(), event.getState());
      }
    });

    final CountDownLatch eosLatch = new CountDownLatch(1);
    playerEp.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.debug("[Kms.PlayerEndpoint.EndOfStream] Event received");
        eosLatch.countDown();
      }
    });

    // Test execution
    getPage().subscribeEvents("playing");
    getPage().initWebRtc(webRtcEp, webRtcChannel, WebRtcMode.RCV_ONLY);
    playerEp.play();

    // Assertions
    Assert.assertTrue(
        String.format("Not received FLOWING IN event in webRtcEp: %s %s", mediaUrl, webRtcChannel),
        flowingLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    Assert.assertTrue(String.format("Not received media (timeout waiting for 'playing' event): %s %s",
        mediaUrl, webRtcChannel), getPage().waitForEvent("playing"));

    final CountDownLatch audioReceptionLatch = new CountDownLatch(1);
    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      // Check continuous reception of audio packets
      getPage().activatePeerConnectionInboundStats("webRtcPeer.peerConnection");

      // Set maximum time allowed without receiving audio
      gettingStats.schedule(new CheckAudioTimerTask(audioReceptionLatch, getPage(), thresholdTime),
          100, 200);
    }

    if (webRtcChannel != WebRtcChannel.AUDIO_ONLY) {
      Assert.assertTrue(
          String.format("The color of the video should be %s at point (%d, %d): %s %s",
              expectedColor, x, y, mediaUrl, webRtcChannel),
          getPage().similarColorAt(expectedColor, x, y));
    }

    Assert.assertTrue("Not received EOS event in player: " + mediaUrl + " " + webRtcChannel,
        eosLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    gettingStats.cancel();

    final double currentTime = getPage().getCurrentTime();
    if (playtime > 0.0) {
      Assert.assertTrue(String.format(
          "Error in play time (expected: %.2fs, allowed: [%.2f, %.2f]s, real: %.2fs), URL: '%s', channel: %s",
          playtime, playtime - getPage().getThresholdTime(),
          playtime + getPage().getThresholdTime(), currentTime, mediaUrl, webRtcChannel),
          getPage().compare(playtime, currentTime));
    }

    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      Assert.assertTrue(
          String.format("Audio is missing: more than %.2f seconds without receiving packets",
              thresholdTime),
          audioReceptionLatch.getCount() == 1);
    }

    // Release Media Pipeline
    playerEp.release();
    mp.release();
  }

  public void testPlayerPause(String mediaUrl, WebRtcChannel webRtcChannel, int pauseTimeSeconds,
      Color[] expectedColors) throws Exception {

    Timer gettingStats = new Timer();

    MediaPipeline mp = kurentoClient.createMediaPipeline();
    PlayerEndpoint playerEp = new PlayerEndpoint.Builder(mp, mediaUrl).build();
    WebRtcEndpoint webRtcEp = new WebRtcEndpoint.Builder(mp).build();
    playerEp.connect(webRtcEp);

    final CountDownLatch eosLatch = new CountDownLatch(1);
    playerEp.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.debug("[Kms.PlayerEndpoint.EndOfStream] Event received");
        eosLatch.countDown();
      }
    });

    final CountDownLatch flowingLatch = new CountDownLatch(1);
    webRtcEp.addMediaFlowInStateChangeListener(new EventListener<MediaFlowInStateChangeEvent>() {
      @Override
      public void onEvent(MediaFlowInStateChangeEvent event) {
        log.debug(
            "[Kms.WebRtcEndpoint.MediaFlowInStateChange] -> endpoint: {}, mediaType: {}, state: {}",
            webRtcEp.getId(), event.getMediaType(), event.getState());
        if (event.getState().equals(MediaFlowState.FLOWING)) {
          flowingLatch.countDown();
        }
      }
    });

    // Test execution
    getPage().subscribeEvents("playing");
    getPage().initWebRtc(webRtcEp, webRtcChannel, WebRtcMode.RCV_ONLY);
    playerEp.play();

    Assert.assertTrue(
        "Not received FLOWING IN event in webRtcEp: " + mediaUrl + " " + webRtcChannel,
        flowingLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    Assert.assertTrue(
        "Not received media (timeout waiting playing event): " + mediaUrl + " " + webRtcChannel,
        getPage().waitForEvent("playing"));

    if (webRtcChannel != WebRtcChannel.AUDIO_ONLY) {
      // Assert initial color, pause stream and wait x seconds
      Assert.assertTrue("At the beginning, the color of the video should be " + expectedColors[0],
          getPage().similarColor(expectedColors[0]));
    } else {
      Thread.sleep(TimeUnit.SECONDS.toMillis(pauseTimeSeconds / 2));
    }

    playerEp.pause();
    Thread.sleep(TimeUnit.SECONDS.toMillis(pauseTimeSeconds));
    if (webRtcChannel != WebRtcChannel.AUDIO_ONLY) {
      Assert.assertTrue("After the pause, the color of the video should be " + expectedColors[0],
          getPage().similarColor(expectedColors[0]));
    }

    playerEp.play();

    final CountDownLatch audioReceptionLatch = new CountDownLatch(1);
    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      // Checking continuity of the audio
      getPage().activatePeerConnectionInboundStats("webRtcPeer.peerConnection");

      gettingStats.schedule(new CheckAudioTimerTask(audioReceptionLatch, getPage()), 100,
          200);
    }

    if (webRtcChannel != WebRtcChannel.AUDIO_ONLY) {
      for (Color expectedColor : expectedColors) {
        Assert.assertTrue(
            "After the pause and the play, the color of the video should be " + expectedColor,
            getPage().similarColor(expectedColor));
      }
    }

    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      Assert.assertTrue("Check audio. There were more than 2 seconds without receiving packets",
          audioReceptionLatch.getCount() == 1);
    }

    // Assertions
    Assert.assertTrue("Not received EOS event in player: " + mediaUrl + " " + webRtcChannel,
        eosLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    gettingStats.cancel();

    // Release Media Pipeline
    playerEp.release();
    mp.release();
  }

  public void testPlayerSeek(String mediaUrl, WebRtcChannel webRtcChannel, int pauseTimeSeconds,
      Map<Integer, Color> expectedPositionAndColor) throws Exception {

    Timer gettingStats = new Timer();

    MediaPipeline mp = kurentoClient.createMediaPipeline();
    PlayerEndpoint playerEp = new PlayerEndpoint.Builder(mp, mediaUrl).build();
    WebRtcEndpoint webRtcEp = new WebRtcEndpoint.Builder(mp).build();
    playerEp.connect(webRtcEp);

    final CountDownLatch eosLatch = new CountDownLatch(1);
    final CountDownLatch flowingLatch = new CountDownLatch(1);

    playerEp.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.debug("[Kms.PlayerEndpoint.EndOfStream] Event received");
        eosLatch.countDown();
      }
    });

    webRtcEp.addMediaFlowInStateChangeListener(new EventListener<MediaFlowInStateChangeEvent>() {
      @Override
      public void onEvent(MediaFlowInStateChangeEvent event) {
        log.debug(
            "[Kms.WebRtcEndpoint.MediaFlowInStateChange] -> endpoint: {}, mediaType: {}, state: {}",
            webRtcEp.getId(), event.getMediaType(), event.getState());
        if (event.getState().equals(MediaFlowState.FLOWING)) {
          flowingLatch.countDown();
        }
      }
    });

    // Test execution
    getPage().subscribeEvents("playing");
    getPage().initWebRtc(webRtcEp, webRtcChannel, WebRtcMode.RCV_ONLY);
    playerEp.play();

    Assert.assertTrue(
        "Not received media (timeout waiting playing event): " + mediaUrl + " " + webRtcChannel,
        getPage().waitForEvent("playing"));

    final CountDownLatch audioReceptionLatch = new CountDownLatch(1);
    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      // Checking continuity of the audio
      getPage().activatePeerConnectionInboundStats("webRtcPeer.peerConnection");

      gettingStats.schedule(new CheckAudioTimerTask(audioReceptionLatch, getPage()), 100,
          200);
    }

    Assert.assertTrue(
        "Not received FLOWING IN event in webRtcEp: " + mediaUrl + " " + webRtcChannel,
        flowingLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    // TODO: Check with playerEp.getVideoInfo().getIsSeekable() if the video is seekable. If not,
    // assert with exception from KMS

    // Assertions

    Thread.sleep(TimeUnit.SECONDS.toMillis(pauseTimeSeconds));
    for (Integer position : expectedPositionAndColor.keySet()) {
      log.debug("Try to set position in {}", position);
      playerEp.setPosition(position);
      if (webRtcChannel != WebRtcChannel.AUDIO_ONLY) {
        Assert.assertTrue(
            "After set position to " + position + "ms, the color of the video should be "
                + expectedPositionAndColor.get(position),
            getPage().similarColor(expectedPositionAndColor.get(position)));
      }
    }

    if (webRtcChannel == WebRtcChannel.AUDIO_ONLY
        || webRtcChannel == WebRtcChannel.AUDIO_AND_VIDEO) {
      Assert.assertTrue("Check audio. There were more than 2 seconds without receiving packets",
          audioReceptionLatch.getCount() == 1);
    }

    Assert.assertTrue("Not received EOS event in player: " + mediaUrl + " " + webRtcChannel,
        eosLatch.await(getPage().getTimeout(), TimeUnit.SECONDS));

    gettingStats.cancel();

    // Release Media Pipeline
    playerEp.release();
    mp.release();
  }

}
