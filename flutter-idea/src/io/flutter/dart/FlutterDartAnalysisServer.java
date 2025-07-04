/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.dart;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.dart.server.AnalysisServerListenerAdapter;
import com.google.dart.server.ResponseListener;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import io.flutter.utils.JsonUtils;
import org.dartlang.analysis.server.protocol.AnalysisError;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.dartlang.analysis.server.protocol.FlutterService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class FlutterDartAnalysisServer implements Disposable {
  private static final String FLUTTER_NOTIFICATION_OUTLINE = "flutter.outline";

  @NotNull final Project project;

  /**
   * Each key is a notification identifier.
   * Each value is the set of files subscribed to the notification.
   */
  private final @NotNull Map<String, List<String>> subscriptions = new HashMap<>();

  @VisibleForTesting
  protected final Map<String, List<FlutterOutlineListener>> fileOutlineListeners = new HashMap<>();

  /**
   * Each key is a request identifier.
   * Each value is the {@link Consumer} for the response.
   */
  private final Map<String, Consumer<JsonObject>> responseConsumers = new HashMap<>();
  private boolean isDisposed = false;

  @NotNull
  public static FlutterDartAnalysisServer getInstance(@NotNull final Project project) {
    return Objects.requireNonNull(project.getService(FlutterDartAnalysisServer.class));
  }

  @NotNull
  public DartAnalysisServerService getAnalysisService() {
    return Objects.requireNonNull(DartPlugin.getInstance().getAnalysisService(project));
  }

  @NotNull
  public String getSdkVersion() {
    return getAnalysisService().getSdkVersion();
  }

  /**
   * @noinspection BooleanMethodIsAlwaysInverted
   */
  public boolean isServerConnected() {
    return !getSdkVersion().isEmpty();
  }

  @VisibleForTesting
  public FlutterDartAnalysisServer(@NotNull Project project) {
    this.project = project;
    DartAnalysisServerService analysisService = getAnalysisService();
    analysisService.addResponseListener(new CompatibleResponseListener());
    analysisService.addAnalysisServerListener(new AnalysisServerListenerAdapter() {
      private boolean hasComputedErrors = false;

      @Override
      public void serverConnected(String s) {
        // If the server reconnected, we need to let it know that we still care
        // about our subscriptions.
        if (!subscriptions.isEmpty()) {
          sendSubscriptions();
        }
        // TODO(jwren) at this point the Dart Analysis Server Service is connected and isServerConnected() will return true, however
        // the Flutter Plugin may have already called addOutlineListener (or other methods).  If addOutlineListener is called before the
        // server is connected, this method should follow up with those calls to undo the race condition.
      }

      @Override
      public void computedErrors(String file, List<AnalysisError> errors) {
        if (!hasComputedErrors && project.isOpen()) {
          hasComputedErrors = true;
        }

        super.computedErrors(file, errors);
      }
    });
  }

  public void addOutlineListener(@NotNull final String filePath, @NotNull final FlutterOutlineListener listener) {
    if (!isServerConnected()) {
      return;
    }
    synchronized (fileOutlineListeners) {
      final List<FlutterOutlineListener> listeners =
        fileOutlineListeners.computeIfAbsent(getAnalysisService().getLocalFileUri(filePath), k -> new ArrayList<>());
      listeners.add(listener);
    }
    addSubscription(FlutterService.OUTLINE, filePath);
  }

  public void removeOutlineListener(@NotNull final String filePath, @NotNull final FlutterOutlineListener listener) {
    if (!isServerConnected()) {
      return;
    }
    final boolean removeSubscription;
    synchronized (fileOutlineListeners) {
      final List<FlutterOutlineListener> listeners = fileOutlineListeners.get(filePath);
      removeSubscription = listeners != null && listeners.remove(listener);
    }
    if (removeSubscription) {
      removeSubscription(FlutterService.OUTLINE, filePath);
    }
  }

  /**
   * Adds a flutter event subscription to the analysis server.
   * <p>
   * Note that <code>filePath</code> must be an absolute path.
   */
  private void addSubscription(@NotNull final String service, @NotNull final String filePath) {
    if (!isServerConnected()) {
      return;
    }
    final List<String> files = subscriptions.computeIfAbsent(service, k -> new ArrayList<>());
    final String filePathOrUri = getAnalysisService().getLocalFileUri(filePath);
    if (!files.contains(filePathOrUri)) {
      files.add(filePathOrUri);
      sendSubscriptions();
    }
  }

  /**
   * Removes a flutter event subscription from the analysis server.
   * <p>
   * Note that <code>filePath</code> must be an absolute path.
   */
  private void removeSubscription(@NotNull final String service, @NotNull final String filePath) {
    if (!isServerConnected()) {
      return;
    }
    final String filePathOrUri = getAnalysisService().getLocalFileUri(filePath);
    final List<String> files = subscriptions.get(service);
    if (files != null && files.remove(filePathOrUri)) {
      sendSubscriptions();
    }
  }

  private void sendSubscriptions() {
    if (!isServerConnected()) {
      return;
    }
    DartAnalysisServerService analysisService = getAnalysisService();
    final String id = analysisService.generateUniqueId();
    if (id != null) {
      analysisService.sendRequest(id, FlutterRequestUtilities.generateAnalysisSetSubscriptions(id, subscriptions));
    }
  }

  private void processString(@Nullable String jsonString) {
    if (jsonString == null) return;
    if (isDisposed) return;
    Application application = ApplicationManager.getApplication();
    if (application != null) {
      application.executeOnPooledThread(() -> {
        // Short circuit just in case we have been disposed in the time it took
        // for us to get around to listening for the response.
        if (isDisposed) return;
        JsonElement jsonElement = JsonUtils.parseString(jsonString);
        if (jsonElement != null) {
          processResponse(jsonElement.getAsJsonObject());
        }
      });
    }
  }

  /**
   * Handle the given {@link JsonObject} response.
   */
  private void processResponse(@Nullable JsonObject response) {
    if (response == null) return;

    final JsonElement eventName = response.get("event");
    if (eventName != null && eventName.isJsonPrimitive()) {
      processNotification(response, eventName);
      return;
    }

    if (response.has("error")) {
      return;
    }

    final JsonObject resultObject = response.getAsJsonObject("result");
    if (resultObject == null) {
      return;
    }

    final JsonPrimitive idJsonPrimitive = (JsonPrimitive)response.get("id");
    if (idJsonPrimitive == null) {
      return;
    }
    final String idString = idJsonPrimitive.getAsString();

    final Consumer<JsonObject> consumer;
    synchronized (responseConsumers) {
      consumer = responseConsumers.remove(idString);
    }
    if (consumer == null) {
      return;
    }

    consumer.consume(resultObject);
  }

  /**
   * Attempts to handle the given {@link JsonObject} as a notification.
   */
  @SuppressWarnings("DataFlowIssue") // Ignore for de-marshalling JSON objects.
  private void processNotification(JsonObject response, @NotNull JsonElement eventName) {
    // If we add code to handle the more event types below, update the filter in processString().
    final String event = eventName.getAsString();
    if (Objects.equals(event, FLUTTER_NOTIFICATION_OUTLINE)) {
      final JsonObject paramsObject = response.get("params").getAsJsonObject();
      final String file = paramsObject.get("file").getAsString();

      final JsonElement instrumentedCodeElement = paramsObject.get("instrumentedCode");
      final String instrumentedCode = instrumentedCodeElement != null ? instrumentedCodeElement.getAsString() : null;

      final JsonObject outlineObject = paramsObject.get("outline").getAsJsonObject();
      final FlutterOutline outline = FlutterOutline.fromJson(outlineObject);

      final List<FlutterOutlineListener> listenersUpdated;
      synchronized (fileOutlineListeners) {
        final List<FlutterOutlineListener> listeners = fileOutlineListeners.get(file);
        listenersUpdated = listeners != null ? Lists.newArrayList(listeners) : null;
      }
      if (listenersUpdated != null) {
        for (FlutterOutlineListener listener : listenersUpdated) {
          if (listener != null && file != null) {
            listener.outlineUpdated(file, outline, instrumentedCode);
          }
        }
      }
    }
  }

  class CompatibleResponseListener implements ResponseListener {
    @SuppressWarnings({"override", "RedundantSuppression"})
    public void onResponse(String jsonString) {
      processString(jsonString);
    }
  }

  @Override
  public void dispose() {
    isDisposed = true;
  }
}
