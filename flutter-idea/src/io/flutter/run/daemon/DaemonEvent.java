/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.daemon;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <p>A message received from a Flutter process that's not in response to a particular request.
 */
abstract class DaemonEvent {
  /**
   * Parses an event and sends it to the listener.
   */
  static void dispatch(@NotNull JsonObject obj, @NotNull Listener listener) {
    final JsonPrimitive primEvent = obj.getAsJsonPrimitive("event");
    if (primEvent == null) {
      LOG.info("Missing event field in JSON from flutter process: " + obj);
      return;
    }

    final String eventName = primEvent.getAsString();
    if (eventName == null) {
      LOG.info("Unexpected event field in JSON from flutter process: " + obj);
      return;
    }

    final JsonObject params = obj.getAsJsonObject("params");
    if (params == null) {
      LOG.info("Missing parameters in event from flutter process: " + obj);
      return;
    }

    final DaemonEvent event = create(eventName, params);
    if (event == null) {
      return; // Drop unknown event.
    }

    event.accept(listener);
  }

  @Nullable
  static DaemonEvent create(@NotNull String eventName, @NotNull JsonObject params) {
    try {
      return switch (eventName) {
        case "daemon.connected" -> GSON.fromJson(params, DaemonConnected.class);
        case "daemon.log" -> GSON.fromJson(params, DaemonLog.class);
        case "daemon.logMessage" -> GSON.fromJson(params, DaemonLogMessage.class);
        case "daemon.showMessage" -> GSON.fromJson(params, DaemonShowMessage.class);
        case "app.start" -> GSON.fromJson(params, AppStarting.class);
        case "app.debugPort" -> GSON.fromJson(params, AppDebugPort.class);
        case "app.started" -> GSON.fromJson(params, AppStarted.class);
        case "app.log" -> GSON.fromJson(params, AppLog.class);
        case "app.progress" -> GSON.fromJson(params, AppProgress.class);
        case "app.stop" -> GSON.fromJson(params, AppStopped.class);
        case "device.added" -> GSON.fromJson(params, DeviceAdded.class);
        case "device.removed" -> GSON.fromJson(params, DeviceRemoved.class);
        default -> null; // Drop an unknown event.
      };
    }
    catch (JsonSyntaxException e) {
      LOG.info("Unexpected parameters in event from flutter process: " + params);
      return null;
    }
  }

  abstract void accept(Listener listener);

  @Override
  public String toString() {
    return GSON.toJson(this, getClass());
  }

  /**
   * Receives events from a Flutter daemon process.
   */
  interface Listener {

    // process lifecycle

    default void processWillTerminate() {
    }

    default void processTerminated(int exitCode) {
    }

    // daemon domain

    default void onDaemonConnected(DaemonConnected event) {
    }

    default void onDaemonLog(DaemonLog event) {
    }

    default void onDaemonLogMessage(DaemonLogMessage event) {
    }

    default void onDaemonShowMessage(DaemonShowMessage event) {
    }

    // app domain

    default void onAppStarting(AppStarting event) {
    }

    default void onAppDebugPort(AppDebugPort event) {
    }

    default void onAppStarted(AppStarted event) {
    }

    default void onAppLog(AppLog event) {
    }

    default void onAppProgressStarting(AppProgress event) {
    }

    default void onAppProgressFinished(AppProgress event) {
    }

    default void onAppStopped(AppStopped event) {
    }

    // device domain

    default void onDeviceAdded(DeviceAdded event) {
    }

    default void onDeviceRemoved(DeviceRemoved event) {
    }
  }

  // daemon domain

  static class DaemonConnected extends DaemonEvent {
    // "event":"daemon.log"
    String version;
    long pid;

    void accept(Listener listener) {
      listener.onDaemonConnected(this);
    }
  }

  static class DaemonLog extends DaemonEvent {
    // "event":"daemon.log"
    String log;
    boolean error;

    void accept(Listener listener) {
      listener.onDaemonLog(this);
    }
  }

  static class DaemonLogMessage extends DaemonEvent {
    // "event":"daemon.logMessage"
    String level;
    String message;
    String stackTrace;

    void accept(Listener listener) {
      listener.onDaemonLogMessage(this);
    }
  }

  static class DaemonShowMessage extends DaemonEvent {
    // "event":"daemon.showMessage"
    String level;
    String title;
    String message;

    void accept(Listener listener) {
      listener.onDaemonShowMessage(this);
    }
  }

  // app domain

  static class AppStarting extends DaemonEvent {
    public static final String LAUNCH_MODE_RUN = "run";
    public static final String LAUNCH_MODE_ATTACH = "attach";

    // "event":"app.start"
    String appId;
    String deviceId;
    String directory;
    String launchMode;
    boolean supportsRestart;

    void accept(Listener listener) {
      listener.onAppStarting(this);
    }
  }

  static class AppDebugPort extends DaemonEvent {
    // "event":"app.eventDebugPort"
    String appId;
    // <code>port</code> is deprecated; prefer using <code>wsUri</code>.
    // int port;
    String wsUri;
    String baseUri;

    void accept(Listener listener) {
      listener.onAppDebugPort(this);
    }
  }

  static class AppStarted extends DaemonEvent {
    // "event":"app.started"
    String appId;

    void accept(Listener listener) {
      listener.onAppStarted(this);
    }
  }

  static class AppLog extends DaemonEvent {
    // "event":"app.log"
    String appId;
    String log;
    boolean error;

    void accept(Listener listener) {
      listener.onAppLog(this);
    }
  }

  static class AppProgress extends DaemonEvent {
    // "event":"app.progress"

    // (technically undocumented)
    String appId;
    String id;

    /**
     * Undocumented, optional field; seems to be a progress event subtype.
     * See <a href="https://github.com/flutter/flutter/search?q=startProgress+progressId">code</a>.
     */
    private String progressId;

    String message;

    private Boolean finished;

    @NotNull
    String getType() {
      return StringUtil.notNullize(progressId);
    }

    boolean isStarting() {
      return !isFinished();
    }

    boolean isFinished() {
      return finished != null && finished;
    }

    void accept(Listener listener) {
      if (isStarting()) {
        listener.onAppProgressStarting(this);
      }
      else {
        listener.onAppProgressFinished(this);
      }
    }
  }

  static class AppStopped extends DaemonEvent {
    // "event":"app.stop"
    String appId;
    String error;

    void accept(Listener listener) {
      listener.onAppStopped(this);
    }
  }

  // device domain

  static class DeviceAdded extends DaemonEvent {
    // "event":"device.added"
    String id;
    String name;
    String platform;
    String emulatorId;
    Boolean emulator;

    @Nullable String category;
    @Nullable String platformType;
    @Nullable Boolean ephemeral;

    void accept(Listener listener) {
      listener.onDeviceAdded(this);
    }
  }

  static class DeviceRemoved extends DaemonEvent {
    // "event":"device.removed"
    String id;
    String name;
    String platform;
    boolean emulator;

    void accept(Listener listener) {
      listener.onDeviceRemoved(this);
    }
  }

  private static final Gson GSON = new Gson();
  private static final @NotNull Logger LOG = Logger.getInstance(DaemonEvent.class);
}
