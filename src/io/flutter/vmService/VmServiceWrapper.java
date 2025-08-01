package io.flutter.vmService;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.net.PercentEscaper;
import com.google.gson.JsonObject;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.Version;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.jetbrains.lang.dart.DartFileType;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.sdk.FlutterSdk;
import io.flutter.sdk.FlutterSdkVersion;
import io.flutter.utils.OpenApiUtils;
import io.flutter.vmService.frame.DartAsyncMarkerFrame;
import io.flutter.vmService.frame.DartVmServiceEvaluator;
import io.flutter.vmService.frame.DartVmServiceStackFrame;
import io.flutter.vmService.frame.DartVmServiceValue;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.consumer.*;
import org.dartlang.vm.service.element.*;
import org.dartlang.vm.service.element.Stack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VmServiceWrapper implements Disposable {
  @NotNull private static final Logger LOG = Logger.getInstance(VmServiceWrapper.class.getName());

  private static final long RESPONSE_WAIT_TIMEOUT = 3000; // millis

  @NotNull private final DartVmServiceDebugProcess myDebugProcess;
  @NotNull private final VmService myVmService;
  @NotNull private final DartVmServiceListener myVmServiceListener;
  @NotNull private final IsolatesInfo myIsolatesInfo;
  @NotNull private final DartVmServiceBreakpointHandler myBreakpointHandler;
  @NotNull private final Alarm myRequestsScheduler;
  @NotNull private final Map<Integer, CanonicalBreakpoint> breakpointNumbersToCanonicalMap;
  @NotNull private final Set<CanonicalBreakpoint> canonicalBreakpoints;

  private long myVmServiceReceiverThreadId;

  @Nullable private StepOption myLatestStep;

  public VmServiceWrapper(@NotNull DartVmServiceDebugProcess debugProcess,
                          @NotNull VmService vmService,
                          @NotNull DartVmServiceListener vmServiceListener,
                          @NotNull IsolatesInfo isolatesInfo,
                          @NotNull DartVmServiceBreakpointHandler breakpointHandler) {
    myDebugProcess = debugProcess;
    myVmService = vmService;
    myVmServiceListener = vmServiceListener;
    myIsolatesInfo = isolatesInfo;
    myBreakpointHandler = breakpointHandler;
    myRequestsScheduler = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    breakpointNumbersToCanonicalMap = new HashMap<>();
    canonicalBreakpoints = new HashSet<>();
  }

  @NotNull
  public VmService getVmService() {
    return myVmService;
  }

  @Override
  public void dispose() {
  }

  private void addRequest(@NotNull Runnable runnable) {
    if (!myRequestsScheduler.isDisposed()) {
      myRequestsScheduler.addRequest(runnable, 0);
    }
  }

  @NotNull
  public List<IsolateRef> getExistingIsolates() {
    List<IsolateRef> isolateRefs = new ArrayList<>();
    for (IsolatesInfo.IsolateInfo isolateInfo : myIsolatesInfo.getIsolateInfos()) {
      isolateRefs.add(isolateInfo.getIsolateRef());
    }
    return isolateRefs;
  }

  @Nullable
  public StepOption getLatestStep() {
    return myLatestStep;
  }

  private void assertSyncRequestAllowed() {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      LOG.error("EDT should not be blocked by waiting for for the answer from the Dart debugger");
    }
    if (ApplicationManager.getApplication().isReadAccessAllowed()) {
      LOG.error("Waiting for the answer from the Dart debugger under read action may lead to EDT freeze");
    }
    if (myVmServiceReceiverThreadId == Thread.currentThread().getId()) {
      LOG.error("Synchronous requests must not be made in Web Socket listening thread: answer will never be received");
    }
  }

  public void handleDebuggerConnected() {
    streamListen(VmService.DEBUG_STREAM_ID, new VmServiceConsumers.SuccessConsumerWrapper() {
      @Override
      public void received(final Success success) {
        myVmServiceReceiverThreadId = Thread.currentThread().getId();
        streamListen(VmService.ISOLATE_STREAM_ID, new VmServiceConsumers.SuccessConsumerWrapper() {
          @Override
          public void received(final Success success) {
            getVm(new VmServiceConsumers.VmConsumerWrapper() {
              @Override
              public void received(final VM vm) {
                for (final IsolateRef isolateRef : vm.getIsolates()) {
                  getIsolate(isolateRef.getId(), new VmServiceConsumers.GetIsolateConsumerWrapper() {
                    @Override
                    public void received(final Isolate isolate) {
                      final Event event = isolate.getPauseEvent();
                      final EventKind eventKind = event.getKind();

                      // Ignore isolates that are very early in their lifecycle. You can't set breakpoints on them
                      // yet, and we'll get lifecycle events for them later.
                      if (eventKind == EventKind.None) {
                        return;
                      }

                      // This is the entry point for attaching a debugger to a running app.
                      if (eventKind == EventKind.Resume) {
                        attachIsolate(isolateRef, isolate);
                        return;
                      }
                      // if event is not PauseStart it means that PauseStart event will follow later and will be handled by listener
                      handleIsolate(isolateRef, eventKind == EventKind.PauseStart);

                      // Handle the case of isolates paused when we connect (this can come up in remote debugging).
                      if (eventKind == EventKind.PauseBreakpoint ||
                          eventKind == EventKind.PauseException ||
                          eventKind == EventKind.PauseInterrupted) {
                        myDebugProcess.isolateSuspended(isolateRef);

                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                          final ElementList<Breakpoint> breakpoints =
                            eventKind == EventKind.PauseBreakpoint ? event.getPauseBreakpoints() : null;
                          final InstanceRef exception = eventKind == EventKind.PauseException ? event.getException() : null;
                          myVmServiceListener
                            .onIsolatePaused(isolateRef, breakpoints, exception, event.getTopFrame(), event.getAtAsyncSuspension());
                        });
                      }
                    }
                  });
                }
              }
            });
          }
        });
      }
    });

    FlutterSdkVersion flutterSdkVersion = null;
    if (myDebugProcess.getSession() != null) {
      final FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(myDebugProcess.getSession().getProject());
      if (flutterSdk != null) {
        flutterSdkVersion = flutterSdk.getVersion();
      }
    }

    if (flutterSdkVersion != null && flutterSdkVersion.canUseToolEventStream()) {
      streamListen("ToolEvent", new SuccessConsumer() {
        @Override
        public void received(Success response) {
        }

        @Override
        public void onError(RPCError error) {
          LOG.error("Error listening to ToolEvent stream: " + error);
        }
      });
    }
  }

  private void streamListen(@NotNull String streamId, @NotNull SuccessConsumer consumer) {
    addRequest(() -> myVmService.streamListen(streamId, consumer));
  }

  private void getVm(@NotNull VMConsumer consumer) {
    addRequest(() -> myVmService.getVM(consumer));
  }

  @NotNull
  public CompletableFuture<Isolate> getCachedIsolate(@NotNull String isolateId) {
    return Objects.requireNonNull(myIsolatesInfo.getCachedIsolate(isolateId, () -> {
      CompletableFuture<Isolate> isolateFuture = new CompletableFuture<>();
      getIsolate(isolateId, new GetIsolateConsumer() {

        @Override
        public void onError(RPCError error) {
          isolateFuture.completeExceptionally(new RuntimeException(error.getMessage()));
        }

        @Override
        public void received(Isolate response) {
          isolateFuture.complete(response);
        }

        @Override
        public void received(Sentinel response) {
          // Unable to get the isolate.
          isolateFuture.complete(null);
        }
      });
      return isolateFuture;
    }));
  }

  private void getIsolate(@NotNull String isolateId, @NotNull GetIsolateConsumer consumer) {
    addRequest(() -> myVmService.getIsolate(isolateId, consumer));
  }

  public void handleIsolate(@NotNull IsolateRef isolateRef, boolean isolatePausedStart) {
    // We should auto-resume on a StartPaused event, if we're not remote debugging, and after breakpoints have been set.

    final boolean newIsolate = myIsolatesInfo.addIsolate(isolateRef);

    if (isolatePausedStart) {
      myIsolatesInfo.setShouldInitialResume(isolateRef);
    }

    // Just to make sure that the main isolate is not handled twice, both from handleDebuggerConnected() and DartVmServiceListener.received(PauseStart)
    if (newIsolate) {
      setIsolatePauseMode(isolateRef.getId(), myDebugProcess.getBreakOnExceptionMode(), isolateRef);
    }
    else {
      checkInitialResume(isolateRef);
    }
  }

  private void setIsolatePauseMode(@NotNull String isolateId, @NotNull ExceptionPauseMode mode, @NotNull IsolateRef isolateRef) {
    if (supportsSetIsolatePauseMode()) {
      SetIsolatePauseModeConsumer sipmc = new SetIsolatePauseModeConsumer() {
        @Override
        public void onError(RPCError error) {
        }

        @Override
        public void received(Sentinel response) {
        }

        @Override
        public void received(Success response) {
          setInitialBreakpointsAndResume(isolateRef);
        }
      };
      addRequest(() -> myVmService.setIsolatePauseMode(isolateId, mode, false, sipmc));
    }
    else {
      SetExceptionPauseModeConsumer wrapper = new SetExceptionPauseModeConsumer() {
        @Override
        public void onError(RPCError error) {
        }

        @Override
        public void received(Sentinel response) {
        }

        @Override
        public void received(Success response) {
          setInitialBreakpointsAndResume(isolateRef);
        }
      };
      //noinspection deprecation
      addRequest(() -> myVmService.setExceptionPauseMode(isolateId, mode, wrapper));
    }
  }

  public void attachIsolate(@NotNull IsolateRef isolateRef, @NotNull Isolate isolate) {
    boolean newIsolate = myIsolatesInfo.addIsolate(isolateRef);
    // Just to make sure that the main isolate is not handled twice, both from handleDebuggerConnected() and DartVmServiceListener.received(PauseStart)
    if (newIsolate) {
      XDebugSessionImpl session = (XDebugSessionImpl)myDebugProcess.getSession();
      OpenApiUtils.safeRunReadAction(() -> {
        session.reset();
        session.initBreakpoints();
      });
      setIsolatePauseMode(isolateRef.getId(), myDebugProcess.getBreakOnExceptionMode(), isolateRef);
    }
    else {
      checkInitialResume(isolateRef);
    }
  }

  private void checkInitialResume(IsolateRef isolateRef) {
    if (myIsolatesInfo.getShouldInitialResume(isolateRef)) {
      resumeIsolate(isolateRef.getId(), null);
    }
  }

  private void setInitialBreakpointsAndResume(@NotNull IsolateRef isolateRef) {
    if (myDebugProcess.myRemoteProjectRootUri == null) {
      // need to detect remote project root path before setting breakpoints
      getIsolate(isolateRef.getId(), new VmServiceConsumers.GetIsolateConsumerWrapper() {
        @Override
        public void received(final Isolate isolate) {
          myDebugProcess.guessRemoteProjectRoot(isolate.getLibraries());
          doSetInitialBreakpointsAndResume(isolateRef);
        }
      });
    }
    else {
      doSetInitialBreakpointsAndResume(isolateRef);
    }
  }

  private void setInitialBreakpointsAndCheckExtensions(@NotNull IsolateRef isolateRef, @NotNull Isolate isolate) {
    doSetBreakpointsForIsolate(myBreakpointHandler.getXBreakpoints(), isolateRef.getId(), () -> {
      myIsolatesInfo.setBreakpointsSet(isolateRef);
    });
    FlutterApp app = FlutterApp.fromEnv(myDebugProcess.getExecutionEnvironment());
    // TODO(messick) Consider replacing this test with an assert; could interfere with setExceptionPauseMode().
    if (app != null) {
      VMServiceManager service = app.getVMServiceManager();
      if (service != null) {
        service.addRegisteredExtensionRPCs(isolate);
      }
    }
  }

  private void doSetInitialBreakpointsAndResume(@NotNull IsolateRef isolateRef) {
    doSetBreakpointsForIsolate(myBreakpointHandler.getXBreakpoints(), isolateRef.getId(), () -> {
      myIsolatesInfo.setBreakpointsSet(isolateRef);
      checkInitialResume(isolateRef);
    });
  }

  private void doSetBreakpointsForIsolate(@NotNull Set<XLineBreakpoint<XBreakpointProperties>> xBreakpoints,
                                          @NotNull String isolateId,
                                          @Nullable Runnable onFinished) {
    if (xBreakpoints.isEmpty()) {
      if (onFinished != null) {
        onFinished.run();
      }
      return;
    }

    final AtomicInteger counter = new AtomicInteger(xBreakpoints.size());

    for (final XLineBreakpoint<XBreakpointProperties> xBreakpoint : xBreakpoints) {
      addBreakpoint(isolateId, xBreakpoint.getSourcePosition(), new VmServiceConsumers.BreakpointsConsumer() {
        @Override
        void sourcePositionNotApplicable() {
          myBreakpointHandler.breakpointFailed(xBreakpoint);

          checkDone();
        }

        @Override
        void received(List<Breakpoint> breakpointResponses, List<RPCError> errorResponses) {
          if (!breakpointResponses.isEmpty()) {
            for (Breakpoint breakpoint : breakpointResponses) {
              myBreakpointHandler.vmBreakpointAdded(xBreakpoint, isolateId, breakpoint);
            }
          }
          else if (!errorResponses.isEmpty()) {
            myBreakpointHandler.breakpointFailed(xBreakpoint);
          }

          checkDone();
        }

        private void checkDone() {
          if (counter.decrementAndGet() == 0 && onFinished != null) {
            onFinished.run();

            myVmService.getIsolate(isolateId, new GetIsolateConsumer() {
              @Override
              public void received(Isolate response) {
                Set<String> libraryUris = new HashSet<>();
                Set<String> fileNames = new HashSet<>();
                for (LibraryRef library : response.getLibraries()) {
                  String uri = library.getUri();
                  libraryUris.add(uri);
                  String[] split = uri.split("/");
                  fileNames.add(split[split.length - 1]);
                }

                ElementList<Breakpoint> breakpoints = response.getBreakpoints();
                if (breakpoints.isEmpty() && canonicalBreakpoints.isEmpty()) {
                  return;
                }

                Set<CanonicalBreakpoint> mappedCanonicalBreakpoints = new HashSet<>();
                assert breakpoints != null;
                for (Breakpoint breakpoint : breakpoints) {
                  Object location = breakpoint.getLocation();
                  // In JIT mode, locations will be unresolved at this time since files aren't compiled until they are used.
                  if (location instanceof UnresolvedSourceLocation) {
                    ScriptRef script = ((UnresolvedSourceLocation)location).getScript();
                    if (script != null && libraryUris.contains(script.getUri())) {
                      mappedCanonicalBreakpoints.add(breakpointNumbersToCanonicalMap.get(breakpoint.getBreakpointNumber()));
                    }
                  }
                }

                Sets.SetView<CanonicalBreakpoint> initialDifference =
                  Sets.difference(canonicalBreakpoints, mappedCanonicalBreakpoints);
                Set<CanonicalBreakpoint> finalDifference = new HashSet<>();

                for (CanonicalBreakpoint missingBreakpoint : initialDifference) {
                  // If the file name doesn't exist in loaded library files, then most likely it's not part of the dependencies of what was
                  // built. So it's okay to ignore these breakpoints in our count.
                  if (fileNames.contains(missingBreakpoint.fileName)) {
                    finalDifference.add(missingBreakpoint);
                  }
                }
              }

              @Override
              public void received(Sentinel response) {
              }

              @Override
              public void onError(RPCError error) {
              }
            });
          }
        }
      });
    }
  }

  public void addBreakpoint(@NotNull String isolateId,
                            @Nullable XSourcePosition position,
                            @NotNull VmServiceConsumers.BreakpointsConsumer consumer) {
    myVmService.getVersion(new VersionConsumer() {
      @Override
      public void received(org.dartlang.vm.service.element.Version response) {
        if (isVmServiceMappingSupported(response)) {
          addBreakpointWithVmService(isolateId, position, consumer);
        }
        else {
          addBreakpointWithMapper(isolateId, position, consumer);
        }
      }

      @Override
      public void onError(RPCError error) {
        addBreakpointWithMapper(isolateId, position, consumer);
      }
    });
  }

  private boolean isVmServiceMappingSupported(org.dartlang.vm.service.element.Version version) {
    assert version != null;

    if (WorkspaceCache.getInstance(myDebugProcess.getSession().getProject()).isBazel()) {
      return true;
    }

    FlutterSdk sdk = FlutterSdk.getFlutterSdk(myDebugProcess.getSession().getProject());
    return VmServiceVersion.hasMapping(version);
  }

  // This is the old way of mapping breakpoints, which uses analyzer.
  public void addBreakpointWithMapper(@NotNull String isolateId,
                                      @Nullable XSourcePosition position,
                                      @NotNull VmServiceConsumers.BreakpointsConsumer consumer) {
    if (position == null || position.getFile().getFileType() != DartFileType.INSTANCE) {
      consumer.sourcePositionNotApplicable();
      return;
    }

    addRequest(() -> {
      int line = position.getLine() + 1;

      Collection<String> scriptUris = myDebugProcess.getUrisForFile(position.getFile());
      CanonicalBreakpoint canonicalBreakpoint =
        new CanonicalBreakpoint(position.getFile().getName(), position.getFile().getCanonicalPath(), line);
      canonicalBreakpoints.add(canonicalBreakpoint);
      List<Breakpoint> breakpointResponses = new ArrayList<>();
      List<RPCError> errorResponses = new ArrayList<>();

      for (String uri : scriptUris) {
        myVmService.addBreakpointWithScriptUri(isolateId, uri, line, new AddBreakpointWithScriptUriConsumer() {
          @Override
          public void received(Breakpoint response) {
            breakpointResponses.add(response);
            breakpointNumbersToCanonicalMap.put(response.getBreakpointNumber(), canonicalBreakpoint);

            checkDone();
          }

          @Override
          public void received(Sentinel response) {
            checkDone();
          }

          @Override
          public void onError(RPCError error) {
            errorResponses.add(error);

            checkDone();
          }

          private void checkDone() {
            if (scriptUris.size() == breakpointResponses.size() + errorResponses.size()) {
              consumer.received(breakpointResponses, errorResponses);
            }
          }
        });
      }
    });
  }

  public void addBreakpointWithVmService(@NotNull String isolateId,
                                         @Nullable XSourcePosition position,
                                         @NotNull VmServiceConsumers.BreakpointsConsumer consumer) {
    if (position == null || position.getFile().getFileType() != DartFileType.INSTANCE) {
      consumer.sourcePositionNotApplicable();
      return;
    }

    addRequest(() -> {
      int line = position.getLine() + 1;

      String resolvedUri = getResolvedUri(position);
      LOG.info("Computed resolvedUri: " + resolvedUri);
      List<String> resolvedUriList = List.of(percentEscapeUri(resolvedUri));

      CanonicalBreakpoint canonicalBreakpoint =
        new CanonicalBreakpoint(position.getFile().getName(), position.getFile().getCanonicalPath(), line);
      canonicalBreakpoints.add(canonicalBreakpoint);
      List<Breakpoint> breakpointResponses = new ArrayList<>();
      List<RPCError> errorResponses = new ArrayList<>();

      myVmService.lookupPackageUris(isolateId, resolvedUriList, new UriListConsumer() {
        @Override
        public void received(UriList response) {
          LOG.info("in received of lookupPackageUris");
          if (myDebugProcess.getSession().getProject().isDisposed()) {
            return;
          }

          List<String> uris = response.getUris();

          if (uris == null || uris.get(0) == null) {
            LOG.info("Uri was not found");
            JsonObject error = new JsonObject();
            error.addProperty("error", "Breakpoint could not be mapped to package URI");
            errorResponses.add(new RPCError(error));
            consumer.received(breakpointResponses, errorResponses);
            return;
          }

          String scriptUri = uris.get(0);
          LOG.info("in received of lookupPackageUris. scriptUri: " + scriptUri);
          myVmService.addBreakpointWithScriptUri(isolateId, scriptUri, line, new AddBreakpointWithScriptUriConsumer() {
            @Override
            public void received(Breakpoint response) {
              breakpointResponses.add(response);
              breakpointNumbersToCanonicalMap.put(response.getBreakpointNumber(), canonicalBreakpoint);

              checkDone();
            }

            @Override
            public void received(Sentinel response) {
              checkDone();
            }

            @Override
            public void onError(RPCError error) {
              errorResponses.add(error);

              checkDone();
            }

            private void checkDone() {
              consumer.received(breakpointResponses, errorResponses);
            }
          });
        }

        @Override
        public void onError(RPCError error) {
          LOG.error(error.toString());
          LOG.error(error.getMessage());
          LOG.error(error.getRequest());
          LOG.error(error.getDetails());
          errorResponses.add(error);
          consumer.received(breakpointResponses, errorResponses);
        }
      });
    });
  }

  private String getResolvedUri(@NotNull XSourcePosition position) {
    XDebugSession session = myDebugProcess.getSession();
    assert session != null;
    VirtualFile file =
      WorkspaceCache.getInstance(session.getProject()).isBazel() ? position.getFile() : position.getFile().getCanonicalFile();
    assert file != null;
    String url = file.getUrl();
    LOG.info("in getResolvedUri. url: " + url);

    if (WorkspaceCache.getInstance(myDebugProcess.getSession().getProject()).isBazel()) {
      String root = WorkspaceCache.getInstance(myDebugProcess.getSession().getProject()).get().getRoot().getPath();
      String resolvedUriRoot = "google3:///";

      // Look for a generated file path.
      String genFilePattern = root + "/blaze-.*?/(.*)";
      Pattern pattern = Pattern.compile(genFilePattern);
      Matcher matcher = pattern.matcher(url);
      if (matcher.find()) {
        String path = matcher.group(1);
        return resolvedUriRoot + path;
      }

      // Look for root.
      int rootIdx = url.indexOf(root);
      if (rootIdx >= 0) {
        return resolvedUriRoot + url.substring(rootIdx + root.length() + 1);
      }
    }

    if (SystemInfo.isWindows) {
      // Dart and the VM service use three /'s in file URIs: https://api.dart.dev/stable/2.16.1/dart-core/Uri-class.html.
      return url.replace("file://", "file:///");
    }

    return url;
  }

  private String percentEscapeUri(String uri) {
    PercentEscaper escaper = new PercentEscaper("!#$&'()*+,-./:;=?@_~", false);
    return escaper.escape(uri);
  }

  public void addBreakpointForIsolates(@NotNull XLineBreakpoint<XBreakpointProperties> xBreakpoint,
                                       @NotNull Collection<IsolatesInfo.IsolateInfo> isolateInfos) {
    for (final IsolatesInfo.IsolateInfo isolateInfo : isolateInfos) {
      addBreakpoint(isolateInfo.getIsolateId(), xBreakpoint.getSourcePosition(), new VmServiceConsumers.BreakpointsConsumer() {
        @Override
        void sourcePositionNotApplicable() {
          myBreakpointHandler.breakpointFailed(xBreakpoint);
        }

        @Override
        void received(List<Breakpoint> breakpointResponses, List<RPCError> errorResponses) {
          for (Breakpoint breakpoint : breakpointResponses) {
            myBreakpointHandler.vmBreakpointAdded(xBreakpoint, isolateInfo.getIsolateId(), breakpoint);
          }
        }
      });
    }
  }

  /**
   * Reloaded scripts need to have their breakpoints re-applied; re-set all existing breakpoints.
   */
  public void restoreBreakpointsForIsolate(@NotNull String isolateId, @Nullable Runnable onFinished) {
    // Cached information about the isolate may now be stale.
    myIsolatesInfo.invalidateCache(isolateId);

    // Remove all existing VM breakpoints for this isolate.
    myBreakpointHandler.removeAllVmBreakpoints(isolateId);
    // Re-set existing breakpoints.
    doSetBreakpointsForIsolate(myBreakpointHandler.getXBreakpoints(), isolateId, onFinished);
  }

  public void addTemporaryBreakpoint(@NotNull XSourcePosition position, @NotNull String isolateId) {
    addBreakpoint(isolateId, position, new VmServiceConsumers.BreakpointsConsumer() {
      @Override
      void sourcePositionNotApplicable() {
      }

      @Override
      void received(List<Breakpoint> breakpointResponses, List<RPCError> errorResponses) {
        for (Breakpoint breakpoint : breakpointResponses) {
          myBreakpointHandler.temporaryBreakpointAdded(isolateId, breakpoint);
        }
      }
    });
  }

  public void removeBreakpoint(@NotNull String isolateId, @NotNull String vmBreakpointId) {
    addRequest(() -> myVmService.removeBreakpoint(isolateId, vmBreakpointId, new RemoveBreakpointConsumer() {
      @Override
      public void onError(RPCError error) {
      }

      @Override
      public void received(Sentinel response) {
      }

      @Override
      public void received(Success response) {
      }
    }));
  }

  public void resumeIsolate(@NotNull String isolateId, @Nullable StepOption stepOption) {
    addRequest(() -> {
      myLatestStep = stepOption;
      myVmService.resume(isolateId, stepOption, null, new VmServiceConsumers.EmptyResumeConsumer() {
      });
    });
  }

  public void setExceptionPauseMode(@NotNull ExceptionPauseMode mode) {
    for (IsolatesInfo.IsolateInfo isolateInfo : myIsolatesInfo.getIsolateInfos()) {
      if (supportsSetIsolatePauseMode()) {
        addRequest(() -> myVmService.setIsolatePauseMode(isolateInfo.getIsolateId(), mode, false, new SetIsolatePauseModeConsumer() {
          @Override
          public void onError(RPCError error) {
          }

          @Override
          public void received(Sentinel response) {
          }

          @Override
          public void received(Success response) {
          }
        }));
      }
      else {
        //noinspection deprecation
        addRequest(() -> myVmService.setExceptionPauseMode(isolateInfo.getIsolateId(), mode, new SetExceptionPauseModeConsumer() {
          @Override
          public void onError(RPCError error) {
          }

          @Override
          public void received(Sentinel response) {
          }

          @Override
          public void received(Success response) {
          }
        }));
      }
    }
  }

  /**
   * Drop to the indicated frame.
   * <p>
   * frameIndex specifies the stack frame to rewind to. Stack frame 0 is the currently executing
   * function, so frameIndex must be at least 1.
   */
  public void dropFrame(@NotNull String isolateId, int frameIndex) {
    addRequest(() -> {
      myLatestStep = StepOption.Rewind;
      myVmService.resume(isolateId, StepOption.Rewind, frameIndex, new VmServiceConsumers.EmptyResumeConsumer() {
        @Override
        public void onError(RPCError error) {
          myDebugProcess.getSession().getConsoleView()
            .print("Error from drop frame: " + error.getMessage() + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        }
      });
    });
  }

  public void pauseIsolate(@NotNull String isolateId) {
    addRequest(() -> myVmService.pause(isolateId, new PauseConsumer() {
      @Override
      public void onError(RPCError error) {
      }

      @Override
      public void received(Sentinel response) {
      }

      @Override
      public void received(Success response) {
      }
    }));
  }

  public void computeStackFrames(@NotNull String isolateId,
                                 int firstFrameIndex,
                                 @NotNull XExecutionStack.XStackFrameContainer container,
                                 @Nullable InstanceRef exception) {
    addRequest(() -> myVmService.getStack(isolateId, new GetStackConsumer() {
      @Override
      public void received(Stack vmStack) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
          InstanceRef exceptionToAddToFrame = exception;

          // Check for async causal frames; fall back to using regular sync frames.
          ElementList<Frame> elementList = vmStack.getAsyncCausalFrames();
          if (elementList == null) {
            elementList = vmStack.getFrames();
          }

          final List<Frame> vmFrames = Lists.newArrayList(elementList);
          final List<XStackFrame> xStackFrames = new ArrayList<>(vmFrames.size());

          for (final Frame vmFrame : vmFrames) {
            if (vmFrame.getKind() == FrameKind.AsyncSuspensionMarker) {
              // Render an asynchronous gap.
              final XStackFrame markerFrame = new DartAsyncMarkerFrame();
              xStackFrames.add(markerFrame);
            }
            else {
              final DartVmServiceStackFrame stackFrame =
                new DartVmServiceStackFrame(myDebugProcess, isolateId, vmFrame, vmFrames, exceptionToAddToFrame);
              stackFrame.setIsDroppableFrame(vmFrame.getKind() == FrameKind.Regular);
              xStackFrames.add(stackFrame);

              if (!stackFrame.isInDartSdkPatchFile()) {
                // The exception (if any) is added to the frame where debugger stops and to the upper frames.
                exceptionToAddToFrame = null;
              }
            }
          }
          container.addStackFrames(firstFrameIndex == 0 ? xStackFrames : xStackFrames.subList(firstFrameIndex, xStackFrames.size()), true);
        });
      }

      @Override
      public void onError(RPCError error) {
        container.errorOccurred(error.getMessage());
      }

      @Override
      public void received(Sentinel response) {
        container.errorOccurred(response.getValueAsString());
      }
    }));
  }

  @Nullable
  public Script getScriptSync(@NotNull String isolateId, @NotNull String scriptId) {
    assertSyncRequestAllowed();

    final Semaphore semaphore = new Semaphore();
    semaphore.down();

    final Ref<Script> resultRef = Ref.create();

    addRequest(() -> myVmService.getObject(isolateId, scriptId, new GetObjectConsumer() {
      @Override
      public void received(Obj script) {
        resultRef.set((Script)script);
        semaphore.up();
      }

      @Override
      public void received(Sentinel response) {
        semaphore.up();
      }

      @Override
      public void onError(RPCError error) {
        semaphore.up();
      }
    }));

    semaphore.waitFor(RESPONSE_WAIT_TIMEOUT);
    return resultRef.get();
  }

  public void getObject(@NotNull String isolateId, @NotNull String objectId, @NotNull GetObjectConsumer consumer) {
    addRequest(() -> myVmService.getObject(isolateId, objectId, consumer));
  }

  public void getCollectionObject(@NotNull String isolateId,
                                  @NotNull String objectId,
                                  int offset,
                                  int count,
                                  @NotNull GetObjectConsumer consumer) {
    addRequest(() -> myVmService.getObject(isolateId, objectId, offset, count, consumer));
  }

  public void evaluateInFrame(@NotNull String isolateId,
                              @NotNull Frame vmFrame,
                              @NotNull String expression,
                              @NotNull XDebuggerEvaluator.XEvaluationCallback callback) {
    addRequest(() -> myVmService.evaluateInFrame(isolateId, vmFrame.getIndex(), expression, new EvaluateInFrameConsumer() {
      @Override
      public void received(InstanceRef instanceRef) {
        callback.evaluated(new DartVmServiceValue(myDebugProcess, isolateId, "result", instanceRef, null, null, false));
      }

      @Override
      public void received(Sentinel sentinel) {
        callback.errorOccurred(sentinel.getValueAsString());
      }

      @Override
      public void received(ErrorRef errorRef) {
        callback.errorOccurred(DartVmServiceEvaluator.getPresentableError(errorRef.getMessage()));
      }

      @Override
      public void onError(RPCError error) {
        callback.errorOccurred(error.getMessage());
      }
    }));
  }

  @SuppressWarnings("SameParameterValue")
  public void evaluateInTargetContext(@NotNull String isolateId,
                                      @NotNull String targetId,
                                      @NotNull String expression,
                                      @NotNull EvaluateConsumer consumer) {
    addRequest(() -> myVmService.evaluate(isolateId, targetId, expression, consumer));
  }

  public void evaluateInTargetContext(@NotNull String isolateId,
                                      @NotNull String targetId,
                                      @NotNull String expression,
                                      @NotNull XDebuggerEvaluator.XEvaluationCallback callback) {
    evaluateInTargetContext(isolateId, targetId, expression, new EvaluateConsumer() {
      @Override
      public void received(InstanceRef instanceRef) {
        callback.evaluated(new DartVmServiceValue(myDebugProcess, isolateId, "result", instanceRef, null, null, false));
      }

      @Override
      public void received(Sentinel sentinel) {
        callback.errorOccurred(sentinel.getValueAsString());
      }

      @Override
      public void received(ErrorRef errorRef) {
        callback.errorOccurred(DartVmServiceEvaluator.getPresentableError(errorRef.getMessage()));
      }

      @Override
      public void onError(RPCError error) {
        callback.errorOccurred(error.getMessage());
      }
    });
  }

  public void callToString(@NotNull String isolateId, @NotNull String targetId, @NotNull InvokeConsumer callback) {
    callMethodOnTarget(isolateId, targetId, "toString", callback);
  }

  public void callToList(@NotNull String isolateId, @NotNull String targetId, @NotNull InvokeConsumer callback) {
    callMethodOnTarget(isolateId, targetId, "toList", callback);
  }

  public void callMethodOnTarget(@NotNull String isolateId,
                                 @NotNull String targetId,
                                 @NotNull String methodName,
                                 @NotNull InvokeConsumer callback) {
    addRequest(() -> myVmService.invoke(isolateId, targetId, methodName, Collections.emptyList(), true, callback));
  }

  public CompletableFuture<String> findResolvedFile(@NotNull String isolateId, @NotNull String scriptUri) {
    CompletableFuture<String> uriFuture = new CompletableFuture<>();
    myVmService.lookupResolvedPackageUris(isolateId, List.of(scriptUri), true, new UriListConsumer() {
      @Override
      public void received(UriList response) {
        if (response == null) {
          LOG.info("lookupResolvedPackageUris returned null response");
          uriFuture.complete(null);
          return;
        }

        List<String> uris = response.getUris();
        if (uris == null) {
          LOG.info("lookupResolvedPackageUris returned null uris");
          uriFuture.complete(null);
          return;
        }

        uriFuture.complete(uris.get(0));
      }

      @Override
      public void onError(RPCError error) {
        assert error != null;
        LOG.info("lookupResolvedPackageUris error: " + error.getMessage());

        uriFuture.complete(null);
      }
    });

    return uriFuture;
  }

  private boolean supportsSetIsolatePauseMode() {
    org.dartlang.vm.service.element.Version version = myVmService.getRuntimeVersion();
    return version.getMajor() > 3 || version.getMajor() == 3 && version.getMinor() >= 53;
  }
}

class CanonicalBreakpoint {
  @NotNull final String fileName;
  @Nullable final String path;
  final int line;

  CanonicalBreakpoint(@NotNull String name, @Nullable String path, int line) {
    this.fileName = name;
    this.path = path;
    this.line = line;
  }
}

class VmServiceVersion {
  // VM service protocol versions: https://github.com/dart-lang/sdk/blob/master/runtime/vm/service/service.md#revision-history.
  @NotNull private static Version URI_MAPPING_VERSION = new Version(VmService.versionMajor, VmService.versionMinor, 0);

  public static boolean hasMapping(@NotNull org.dartlang.vm.service.element.Version version) {
    return (new Version(version.getMajor(), version.getMinor(), 0)).isOrGreaterThan(URI_MAPPING_VERSION.major, URI_MAPPING_VERSION.minor);
  }
}
