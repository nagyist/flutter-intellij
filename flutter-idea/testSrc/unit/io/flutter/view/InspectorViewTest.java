/*
 * Copyright 2020 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import io.flutter.ObservatoryConnector;
import io.flutter.devtools.DevToolsIdeFeature;
import io.flutter.jxbrowser.FailureType;
import io.flutter.jxbrowser.InstallationFailedReason;
import io.flutter.jxbrowser.JxBrowserManager;
import io.flutter.jxbrowser.JxBrowserStatus;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.JxBrowserUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeoutException;

import static io.flutter.view.InspectorView.*;
import static org.mockito.Mockito.*;

//@PrepareForTest({ThreadUtil.class, FlutterInitializer.class, JxBrowserUtils.class,
//  InspectorGroupManagerService.class, SwingUtilities.class})
public class InspectorViewTest {
  Project mockProject = mock(Project.class);
  @Mock FlutterApp mockApp;
  @Mock ToolWindow mockToolWindow;
  @Mock ObservatoryConnector mockObservatoryConnector;
  @Mock Application mockApplication;
  @Mock ContentManager mockContentManager;
  @Mock ContentFactory mockContentFactory;

  JxBrowserUtils mockUtils = mock(JxBrowserUtils.class);
  JxBrowserManager mockJxBrowserManager = mock(JxBrowserManager.class);

  MockedStatic<ApplicationManager> mockedStaticApplicationManager;
  ViewUtils viewUtilsSpy;

  @Before
  public void setUp() throws Exception {
    try (var mocks = MockitoAnnotations.openMocks(this)) {

      // Static mocking for ApplicationManager.
      mockedStaticApplicationManager = Mockito.mockStatic(ApplicationManager.class);
      //noinspection ResultOfMethodCallIgnored
      mockedStaticApplicationManager.when(ApplicationManager::getApplication).thenReturn(mockApplication);
      doAnswer(invocation -> {
        Runnable runnable = invocation.getArgument(0);
        if (runnable != null) {
          runnable.run();
        }
        return null;
      }).when(mockApplication).invokeLater(any(Runnable.class));

      // Mocking for ContentManager.
      when(mockToolWindow.getContentManager()).thenReturn(mockContentManager);
      when(mockContentManager.getFactory()).thenReturn(mockContentFactory);

      // Spy on the ViewUtils class.
      final ViewUtils viewUtils = new ViewUtils();
      viewUtilsSpy = spy(viewUtils);
    }
  }

  @After
  public void tearDown() {
    if (mockedStaticApplicationManager != null) {
      mockedStaticApplicationManager.close();
    }
  }

  @Test
  public void testHandleJxBrowserInstalled() {
    // If JxBrowser has been installed, we should use the DevTools instance to open the embedded browser.
    final JxBrowserUtils mockJxBrowserUtils = mock(JxBrowserUtils.class);
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockJxBrowserUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);

    doNothing().when(spy).verifyEventDispatchThread();
    doNothing().when(spy).openInspectorWithDevTools(any(), any(), anyBoolean(), any());
    doNothing().when(spy).setUpToolWindowListener(any(), any(), anyBoolean(), any());

    spy.handleJxBrowserInstalled(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);

    verify(spy, times(1)).openInspectorWithDevTools(mockApp, mockToolWindow, true, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1)).setUpToolWindowListener(mockApp, mockToolWindow, true, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testHandleJxBrowserInstallationFailed() {
    final JxBrowserUtils mockJxBrowserUtils = mock(JxBrowserUtils.class);
    when(mockJxBrowserUtils.licenseIsSet()).thenReturn(true);

    when(mockJxBrowserManager.getLatestFailureReason()).thenReturn(new InstallationFailedReason(FailureType.FILE_DOWNLOAD_FAILED));

    // If JxBrowser failed to install, we should show a failure message that allows the user to manually retry.
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockJxBrowserUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);
    doNothing().when(viewUtilsSpy).presentClickableLabel(
      eq(mockToolWindow),
      anyList()
    );

    spy.handleJxBrowserInstallationFailed(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
    verify(viewUtilsSpy, times(1)).presentClickableLabel(
      eq(mockToolWindow),
      anyList()
    );
  }

  @Test
  public void testHandleJxBrowserInstallationInProgressWithSuccessfulInstall() {
    when(mockJxBrowserManager.getStatus()).thenReturn(JxBrowserStatus.INSTALLED);

    // If the JxBrowser installation is initially in progress, we should show a message about the installation.
    // If the installation quickly finishes (on the first re-check), then we should call the function to handle successful installation.
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);

    doNothing().when(spy).presentOpenDevToolsOptionWithMessage(any(), any(), any(), any());
    doNothing().when(spy).handleJxBrowserInstalled(any(), any(), any());

    spy.handleJxBrowserInstallationInProgress(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1))
      .presentOpenDevToolsOptionWithMessage(mockApp, mockToolWindow, INSTALLATION_IN_PROGRESS_LABEL, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1)).handleJxBrowserInstalled(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testHandleJxBrowserInstallationInProgressWaiting() {
    when(mockJxBrowserManager.getStatus()).thenReturn(JxBrowserStatus.INSTALLATION_IN_PROGRESS);

    // If the JxBrowser installation is in progress and is not finished on the first re-check, we should start a thread to wait for the
    // installation to finish.
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);

    doNothing().when(spy).presentOpenDevToolsOptionWithMessage(any(), any(), any(), any());
    doNothing().when(spy).startJxBrowserInstallationWaitingThread(any(), any(), any());

    spy.handleJxBrowserInstallationInProgress(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1))
      .presentOpenDevToolsOptionWithMessage(mockApp, mockToolWindow, INSTALLATION_IN_PROGRESS_LABEL, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1)).startJxBrowserInstallationWaitingThread(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testWaitForJxBrowserInstallationWithoutTimeout() throws TimeoutException {
    when(mockJxBrowserManager.getStatus()).thenReturn(JxBrowserStatus.INSTALLATION_IN_PROGRESS);
    when(mockJxBrowserManager.waitForInstallation(INSTALLATION_WAIT_LIMIT_SECONDS)).thenReturn(JxBrowserStatus.INSTALLATION_FAILED);

    // If waiting for JxBrowser installation completes without timing out, then we should return to event thread.
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);

    doNothing().when(spy).handleUpdatedJxBrowserStatusOnEventThread(any(), any(), any(), any());

    spy.waitForJxBrowserInstallation(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1))
      .handleUpdatedJxBrowserStatusOnEventThread(mockApp, mockToolWindow, JxBrowserStatus.INSTALLATION_FAILED,
                                                 DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Ignore
  @Test
  public void testWaitForJxBrowserInstallationWithTimeout() throws TimeoutException {
    when(mockJxBrowserManager.getStatus()).thenReturn(JxBrowserStatus.INSTALLATION_IN_PROGRESS);
    when(mockJxBrowserManager.waitForInstallation(INSTALLATION_WAIT_LIMIT_SECONDS)).thenThrow(new TimeoutException());

    // If the JxBrowser installation doesn't complete on time, we should show a timed out message.
    final InspectorView inspectorView = new InspectorView(mockProject, mockJxBrowserManager, mockUtils, viewUtilsSpy);
    final InspectorView spy = spy(inspectorView);

    doNothing().when(spy).presentOpenDevToolsOptionWithMessage(any(), any(), any(), any());

    spy.waitForJxBrowserInstallation(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
    verify(spy, times(1))
      .presentOpenDevToolsOptionWithMessage(mockApp, mockToolWindow, INSTALLATION_TIMED_OUT_LABEL, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testHandleUpdatedJxBrowserStatusWithFailure() {
    // If waiting for JxBrowser installation completes with failure, then we should redirect to the function that handles failure.
    final InspectorView partialMockInspectorView = mock(InspectorView.class);
    doCallRealMethod().when(partialMockInspectorView)
      .handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.INSTALLATION_FAILED, DevToolsIdeFeature.TOOL_WINDOW);
    partialMockInspectorView.handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.INSTALLATION_FAILED,
                                                          DevToolsIdeFeature.TOOL_WINDOW);
    verify(partialMockInspectorView, times(1)).handleJxBrowserInstallationFailed(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testHandleUpdatedJxBrowserStatusWithSuccess() {
    // If waiting for JxBrowser installation completes with failure, then we should redirect to the function that handles failure.
    final InspectorView partialMockInspectorView = mock(InspectorView.class);
    doCallRealMethod().when(partialMockInspectorView)
      .handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.INSTALLED, DevToolsIdeFeature.TOOL_WINDOW);
    partialMockInspectorView.handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.INSTALLED,
                                                          DevToolsIdeFeature.TOOL_WINDOW);
    verify(partialMockInspectorView, times(1)).handleJxBrowserInstalled(mockApp, mockToolWindow, DevToolsIdeFeature.TOOL_WINDOW);
  }

  @Test
  public void testHandleUpdatedJxBrowserStatusWithOtherstatus() {
    // If waiting for JxBrowser installation completes with any other status, then we should recommend opening non-embedded DevTools.
    final InspectorView partialMockInspectorView = mock(InspectorView.class);
    doCallRealMethod().when(partialMockInspectorView)
      .handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.NOT_INSTALLED, DevToolsIdeFeature.TOOL_WINDOW);
    partialMockInspectorView.handleUpdatedJxBrowserStatus(mockApp, mockToolWindow, JxBrowserStatus.NOT_INSTALLED,
                                                          DevToolsIdeFeature.TOOL_WINDOW);
    verify(partialMockInspectorView, times(1))
      .presentOpenDevToolsOptionWithMessage(mockApp, mockToolWindow, INSTALLATION_WAIT_FAILED, DevToolsIdeFeature.TOOL_WINDOW);
  }
}
