package com.sedmelluq.discord.lavaplayer.player;

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameProvider;

/**
 * An audio player that is capable of playing audio tracks and provides audio frames from the currently playing track.
 */
public interface AudioPlayer extends AudioFrameProvider {
  default AudioConfiguration getConfiguration() {
    throw new UnsupportedOperationException();
  }

  /**
   * @return Currently playing track, or null
   */
  AudioTrack getPlayingTrack();

  /**
   * @return Currently scheduled track, or null
   */
  default AudioTrack getScheduledTrack() {
    throw new UnsupportedOperationException();
  }

  /**
   * @param track The track to start playing
   */
  void playTrack(AudioTrack track);

  /**
   * @param track The track to start playing, passing null will stop the current track and return false
   * @param noInterrupt Whether to only start if nothing else is playing
   * @return True if the track was started
   */
  boolean startTrack(AudioTrack track, boolean noInterrupt);

  /**
   * Schedules the next track to be played. This will not trigger the track to be immediately played,
   * but rather schedules it to play after the current track has finished. If there is no playing track,
   * this function will return false
   * @param track The track to schedule. This will overwrite the currently scheduled track, if one exists.
   *              Passing null will clear the current scheduled track.
   * @return True if the track was scheduled
   */
  default boolean scheduleTrack(AudioTrack track) {
    throw new UnsupportedOperationException();
  }

  /**
   * Identical to {@link #scheduleTrack(AudioTrack)} but the replaceExisting parameter will determine
   * whether an existing scheduled track will be overwritten.
   * If replaceExisting is false and a track is scheduled, this will return false.
   * If there is no playing track, this will return false.
   * @param track The track to schedule.
   * @param replaceExisting Whether to replace the current scheduled track, if one exists.
   * @return True if the track was scheduled.
   */
  default boolean scheduleTrack(AudioTrack track, boolean replaceExisting) {
    if (!replaceExisting && getScheduledTrack() != null) {
      return false;
    }

    return scheduleTrack(track);
  }

  /**
   * Stop currently playing track. This will also clear any scheduled tracks.
   */
  void stopTrack();

  /**
   * Stop currently playing track.
   */
  default void stopCurrentTrack() {
    throw new UnsupportedOperationException();
  }

  int getVolume();

  void setVolume(int volume);

  void setFilterFactory(PcmFilterFactory factory);

  void setFrameBufferDuration(Integer duration);

  /**
   * @return Whether the player is paused
   */
  boolean isPaused();

  /**
   * @param value True to pause, false to resume
   */
  void setPaused(boolean value);

  /**
   * Destroy the player and stop playing track.
   */
  void destroy();

  /**
   * Add a listener to events from this player.
   * @param listener New listener
   */
  void addListener(AudioEventListener listener);

  /**
   * Remove an attached listener using identity comparison.
   * @param listener The listener to remove
   */
  void removeListener(AudioEventListener listener);

  /**
   * Check if the player should be "cleaned up" - stopped due to nothing using it, with the given threshold.
   * @param threshold Threshold in milliseconds to use
   */
  void checkCleanup(long threshold);
}
