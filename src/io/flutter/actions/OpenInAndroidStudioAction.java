/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import io.flutter.FlutterBundle;
import io.flutter.FlutterMessages;
import io.flutter.FlutterUtils;
import io.flutter.pub.PubRoot;
import io.flutter.pub.PubRoots;
import io.flutter.sdk.FlutterSdk;
import io.flutter.utils.OpenApiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

/**
 * This action has been removed from the plugin.xml as a required dependent method call {GradleProjectImporter.importAndOpenProjectCore()
 * throws "configureNewProject should be used with new projects only".
 * See https://github.com/flutter/flutter-intellij/issues/7103
 */
public class OpenInAndroidStudioAction extends AnAction {
  private static final String LABEL_FILE = FlutterBundle.message("flutter.androidstudio.open.file.text");
  private static final String DESCR_FILE = FlutterBundle.message("flutter.androidstudio.open.file.description");
  private static final String LABEL_MODULE = FlutterBundle.message("flutter.androidstudio.open.module.text");
  private static final String DESCR_MODULE = FlutterBundle.message("flutter.androidstudio.open.module.description");

  @Override
  public void update(@NotNull AnActionEvent event) {
    updatePresentation(event, event.getPresentation());
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull final AnActionEvent event) {
    @Nullable final Project project = event.getProject();

    if (FlutterUtils.isAndroidStudio()) {
      try {
        //noinspection unchecked
        final Class<OpenInAndroidStudioAction> opener =
          (Class<OpenInAndroidStudioAction>)Class.forName("io.flutter.actions.OpenAndroidModule");
        opener.getDeclaredConstructor().newInstance().actionPerformed(event);
        return;
      }
      catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException |
             InvocationTargetException ignored) {
      }
    }

    OpenApiUtils.safeExecuteOnPooledThread(() -> {
      final String androidStudioPath = findAndroidStudio(project);
      if (androidStudioPath == null) {
        FlutterMessages.showError(
          "Unable to locate Android Studio",
          "You can configure the Android Studio location via 'flutter config --android-studio-dir path-to-android-studio'.",
          project);
        return;
      }

      final VirtualFile projectFile = findProjectFile(event);
      if (projectFile == null) {
        FlutterMessages.showError("Error Opening Android Studio", "Project not found.", project);
        return;
      }

      //noinspection DataFlowIssue
      final VirtualFile sourceFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
      final String sourceFilePath = sourceFile == null ? null : sourceFile.isDirectory() ? null : sourceFile.getPath();

      final Integer line;
      final Integer column;
      final Editor editor = getCurrentEditor(project, sourceFile);
      if (editor != null) {
        final CaretModel caretModel = editor.getCaretModel();
        line = caretModel.getLogicalPosition().line + 1;
        column = caretModel.getLogicalPosition().column;
      }
      else {
        line = column = null;
      }

      openFileInStudio(androidStudioPath, project, projectFile.getPath(), sourceFilePath, line, column);
    });
  }

  @Nullable
  private static Editor getCurrentEditor(@NotNull Project project, @Nullable VirtualFile file) {
    if (file == null) {
      return null;
    }
    final FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
    if (fileEditorManager == null) {
      return null;
    }
    final FileEditor fileEditor = fileEditorManager.getSelectedEditor(file);
    if (fileEditor instanceof TextEditor textEditor) {
      final Editor editor = textEditor.getEditor();
      if (!editor.isDisposed()) {
        return editor;
      }
    }
    return null;
  }

  private static void updatePresentation(AnActionEvent event, @NotNull Presentation state) {
    if (findProjectFile(event) == null) {
      state.setVisible(false);
    }
    else {
      //noinspection DataFlowIssue
      final VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
      final String label;
      final String descr;
      if (file != null && !file.isDirectory()) {
        // The file will be opened in an editor in the new IDE window.
        label = LABEL_FILE;
        descr = DESCR_FILE;
      }
      else {
        // The new IDE window will be opened on the Android module but there is no file selected for editing.
        label = LABEL_MODULE;
        descr = DESCR_MODULE;
      }
      state.setVisible(true);
      state.setText(label);
      state.setDescription(descr);
    }
  }

  protected static boolean isProjectFileName(@NotNull String name) {
    // Note: If the project content is rearranged to have the android module file within the android directory, this will fail.
    return name.endsWith("_android.iml");
  }

  // A plugin contains an example app, which needs to be opened when the native Android is to be edited.
  // In the case of an app that contains a plugin the flutter_app/flutter_plugin/example/android should be opened when
  // 'Open in Android Studio' is requested.
  protected static @Nullable VirtualFile findProjectFile(@Nullable AnActionEvent e) {
    if (e != null) {
      //noinspection DataFlowIssue
      final VirtualFile file = CommonDataKeys.VIRTUAL_FILE.getData(e.getDataContext());
      if (file != null && file.exists()) {
        // We have a selection. Check if it is within a plugin.
        final Project project = e.getProject();
        assert (project != null);

        // Return null if this is an ios folder.
        if (FlutterExternalIdeActionGroup.isWithinIOsDirectory(file, project)) {
          return null;
        }

        final VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
        for (PubRoot root : PubRoots.forProject(project)) {
          if (root.isFlutterPlugin()) {
            final VirtualFile rootFile = root.getRoot();
            if (rootFile == null) continue;
            VirtualFile aFile = file;
            while (aFile != null) {
              if (aFile.equals(rootFile)) {
                var children = rootFile.getChildren();
                if (children == null) continue;
                // We know a plugin resource is selected. Find the example app for it.
                for (VirtualFile child : children) {
                  if (child == null) continue;
                  if (isExampleWithAndroidWithApp(child)) {
                    return child.findChild("android");
                  }
                }
              }
              if (aFile.equals(projectDir)) {
                aFile = null;
              }
              else {
                aFile = aFile.getParent();
              }
            }
          }
        }
        if (isProjectFileName(file.getName())) {
          return getProjectForFile(file);
        }
      }

      final Project project = e.getProject();
      if (project != null) {
        return getProjectForFile(findStudioProjectFile(project));
      }
    }
    return null;
  }

  private static void openFileInStudio(@NotNull String androidStudioPath,
                                       @NotNull Project project,
                                       @NotNull String projectPath,
                                       @Nullable String sourceFile,
                                       @Nullable Integer line,
                                       @Nullable Integer column) {
    try {
      final GeneralCommandLine cmd;
      if (SystemInfo.isMac) {
        cmd = new GeneralCommandLine().withExePath("open")
          .withParameters("-a", androidStudioPath, "--args", projectPath);
      }
      else {
        if (SystemInfo.isWindows) {
          androidStudioPath += "\\bin\\studio.bat";
        }
        else {
          androidStudioPath += "/bin/studio.sh";
        }
        cmd = new GeneralCommandLine().withExePath(androidStudioPath)
          .withParameters(projectPath);
      }
      if (sourceFile != null) {
        if (line != null) {
          cmd.addParameters("--line", line.toString());
          if (column != null) {
            cmd.addParameters("--column", column.toString());
          }
        }
        cmd.addParameter(sourceFile);
      }
      final ColoredProcessHandler handler = new ColoredProcessHandler(cmd);
      handler.addProcessListener(new ProcessAdapter() {
        @Override
        public void processTerminated(@NotNull final ProcessEvent event) {
          if (event.getExitCode() != 0) {
            FlutterMessages.showError("Error Opening", projectPath, project);
          }
        }
      });
      handler.startNotify();
    }
    catch (ExecutionException ex) {
      FlutterMessages.showError(
        "Error Opening",
        "Exception: " + ex.getMessage(),
        project);
    }
  }

  @Nullable
  private static VirtualFile findStudioProjectFile(@NotNull Project project) {
    for (PubRoot root : PubRoots.forProject(project)) {
      var children = root.getRoot().getChildren();
      if (children == null) continue;
      for (VirtualFile child : children) {
        if (child == null) continue;
        if (isProjectFileName(child.getName())) {
          return child;
        }
        if (FlutterExternalIdeActionGroup.isAndroidDirectory(child)) {
          var androidChildren = child.getChildren();
          if (androidChildren == null) continue;
          for (VirtualFile androidChild : androidChildren) {
            if (androidChild == null) continue;
            if (isProjectFileName(androidChild.getName())) {
              return androidChild;
            }
          }
        }
      }
    }
    return null;
  }

  @Nullable
  private static String findAndroidStudio(@Nullable Project project) {
    if (project == null) {
      return null;
    }

    final FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(project);
    if (flutterSdk != null) {
      String androidSdkLocation = flutterSdk.queryFlutterConfig("android-studio-dir", true);
      if (androidSdkLocation != null && !new File(androidSdkLocation).exists()) {
        androidSdkLocation = flutterSdk.queryFlutterConfig("android-studio-dir", false);
      }
      if (androidSdkLocation != null) {
        if (androidSdkLocation.contains("/Android Studio 2.")) {
          //noinspection DataFlowIssue
          Messages.showErrorDialog(FlutterBundle.message("old.android.studio.message", File.separator),
                                   FlutterBundle.message("old.android.studio.title"));
          return null;
        }

        if (androidSdkLocation.endsWith("/")) {
          androidSdkLocation = androidSdkLocation.substring(0, androidSdkLocation.length() - 1);
        }

        // On a mac, trim off "/Contents".
        final String contents = "/Contents";
        if (SystemInfo.isMac && androidSdkLocation.endsWith(contents)) {
          return androidSdkLocation.substring(0, androidSdkLocation.length() - contents.length());
        }
        return androidSdkLocation;
      }
    }
    return null;
  }

  @Nullable
  private static VirtualFile getProjectForFile(@Nullable VirtualFile file) {
    // Expect true: isProjectFileName(file.getName()), but some flexibility is allowed.
    if (file == null) {
      return null;
    }
    if (file.isDirectory()) {
      return isAndroidWithApp(file) ? file : null;
    }
    final VirtualFile dir = file.getParent();
    if (dir == null) return null;

    if (isAndroidWithApp(dir)) {
      // In case someone moves the .iml file, or the project organization gets rationalized.
      return dir;
    }
    VirtualFile project = dir.findChild("android");
    if (project != null && isAndroidWithApp(project)) {
      return project;
    }
    project = dir.findChild(".android");
    if (project != null && isAndroidWithApp(project)) {
      return project;
    }
    return null;
  }

  // Return true if the directory is named android and contains either an app (for applications) or a src (for plugins) directory.
  private static boolean isAndroidWithApp(@NotNull VirtualFile file) {
    return FlutterExternalIdeActionGroup.isAndroidDirectory(file) && (file.findChild("app") != null || file.findChild("src") != null);
  }

  // Return true if the directory has the structure of a plugin example application: a pubspec.yaml and an
  // android directory with an app. The example app directory name is not specified in case it gets renamed.
  private static boolean isExampleWithAndroidWithApp(@NotNull VirtualFile file) {
    var children = file.getChildren();
    if (children == null) return false;

    boolean hasPubspec = false;
    boolean hasAndroid = false;
    for (VirtualFile candidate : children) {
      if (candidate == null) continue;
      if (isAndroidWithApp(candidate)) hasAndroid = true;
      if (candidate.getName().equals(PubRoot.PUBSPEC_YAML)) hasPubspec = true;
      if (hasAndroid && hasPubspec) {
        return true;
      }
    }
    return false;
  }
}
