
package org.kurento.test.utils;

import java.text.DecimalFormat;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;

import org.kurento.test.browser.WebRtcTestPage;
import org.kurento.test.monitor.PeerConnectionStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TimerTask for checking that packets received are correct when there is audio
 *
 * @author rbenitez (rbenitez@gsyc.es)
 * @since 6.5.1
 */
public class CheckAudioTimerTask extends TimerTask {

  public static Logger log = LoggerFactory.getLogger(CheckAudioTimerTask.class);

  private final CountDownLatch audioReceptionLatch;
  private final WebRtcTestPage page;

  private long lastPacketsReceived = 0;
  private double lastTimestamp = 0.0;
  private long currentPacketsReceived = 0;
  private double currentTimestamp = 0.0;
  private double diffTimestamp = 0.0;
  private int count = 0;
  private double packetsMissed = 0.0;

  // Audio size (in milliseconds) of each individual packet.
  // In the context of WebRTC, the OPUS audio format specifies that:
  // A) Frames can represent 2.5, 5, 10, 20, 40, or 60 ms of audio data.
  // B) An RTP packet MUST contain exactly one Opus frame.
  // https://tools.ietf.org/html/rfc7587#section-4.2
  //
  // The most commonly used frame size if 20 ms, so that's what we'll assume here:
  // OPUS audio frame size = 20
  // OPUS RTP packets per frame = 1
  // Expected packets per second = 1000 / 20 = 50
  private final double PacketsPerSecond = 50.0;

  // Maximum time threshold that audio can be missing without triggering an error.
  private double thresholdTime = 4.0; // seconds

  public CheckAudioTimerTask(CountDownLatch audioReceptionLatch, WebRtcTestPage page) {
    this.audioReceptionLatch = audioReceptionLatch;
    this.page = page;
  }

  public CheckAudioTimerTask(CountDownLatch audioReceptionLatch, WebRtcTestPage page,
      double thresholdTime) {
    this.audioReceptionLatch = audioReceptionLatch;
    this.page = page;
    this.thresholdTime = thresholdTime;
  }

  @Override
  public void run() {
    PeerConnectionStats stats = page.getRtcStats();
    if (count != 0) {
      lastPacketsReceived = currentPacketsReceived;
      lastTimestamp = currentTimestamp;
    }

    currentPacketsReceived = page.getPeerConnAudioPacketsRecv(stats);
    currentTimestamp = page.getPeerConnAudioInboundTimestamp(stats);

    diffTimestamp = currentTimestamp - lastTimestamp;
    count++;

    if (lastTimestamp > 0.0) {
      log.debug("Audio packets received: {} in {} ms",
          (currentPacketsReceived - lastPacketsReceived),
          new DecimalFormat("0.00").format(diffTimestamp));
    }

    if (((currentPacketsReceived - lastPacketsReceived) == 0) && (lastTimestamp > 0.0)) {
      // Packets that must be received in (currentTimestamp - lastTimestamp)
      final double packetsExpected = diffTimestamp * PacketsPerSecond / 1000.0;
      packetsMissed += packetsExpected;
      log.warn("Received {} less audio packets than expected!",
          new DecimalFormat("0").format(packetsMissed));
    } else {
      // Reset to 0 because we are looking for the continuity of the audio, and
      // if (current - last) > 0, then we must be receiving audio packets
      packetsMissed = 0;
    }

    final double thresholdPackets = PacketsPerSecond * thresholdTime;
    if (packetsMissed >= thresholdPackets) {
      log.error("Reached limit of missed audio packets: {} in {} ms",
          new DecimalFormat("0").format(thresholdPackets),
          new DecimalFormat("0.00").format(thresholdTime * 1000.0));
      audioReceptionLatch.countDown();
    }
  }
}
