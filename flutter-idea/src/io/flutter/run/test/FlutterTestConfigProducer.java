/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.test;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.jetbrains.lang.dart.psi.DartFile;
import io.flutter.FlutterUtils;
import io.flutter.dart.DartPlugin;
import io.flutter.pub.PubRoot;
import io.flutter.run.FlutterRunConfigurationProducer;
import org.jetbrains.annotations.NotNull;

/**
 * Determines when we can run a test using "flutter test".
 */
public class FlutterTestConfigProducer extends RunConfigurationProducer<TestConfig> {
  private final TestConfigUtils testConfigUtils = TestConfigUtils.getInstance();

  protected FlutterTestConfigProducer() {
    super(FlutterTestConfigType.getInstance());
  }

  private static boolean isFlutterContext(@NotNull ConfigurationContext context) {
    final PsiElement location = context.getPsiLocation();
    return location != null && FlutterUtils.isInFlutterProject(context.getProject(), location);
  }

  /**
   * If the current file looks like a Flutter test, initializes the run config to run it.
   * <p>
   * Returns true if successfully set up.
   */
  @Override
  protected boolean setupConfigurationFromContext(@NotNull TestConfig config,
                                                  @NotNull ConfigurationContext context,
                                                  @NotNull Ref<PsiElement> sourceElement) {
    if (!isFlutterContext(context)) return false;

    final PsiElement elt = context.getPsiLocation();
    if (elt instanceof PsiDirectory) {
      return setupForDirectory(config, (PsiDirectory)elt);
    }

    final DartFile file = FlutterRunConfigurationProducer.getDartFile(context);
    if (file == null) {
      return false;
    }

    final String testName = testConfigUtils.findTestName(elt);
    if (testName != null && elt != null) {
      final boolean hasVariant = "testWidgets".equals(elt.getText());
      return setupForSingleTest(config, context, file, testName, hasVariant);
    }

    return setupForDartFile(config, context, file);
  }

  private boolean setupForSingleTest(TestConfig config, ConfigurationContext context, DartFile file, String testName, boolean hasVariant) {
    final VirtualFile testFile = verifyFlutterTestFile(config, context, file);
    if (testFile == null) return false;

    config.setFields(TestFields.forTestName(testName, testFile.getPath()).useRegexp(hasVariant));
    config.setGeneratedName();

    return true;
  }

  private boolean setupForDartFile(TestConfig config, ConfigurationContext context, DartFile file) {
    final VirtualFile testFile = verifyFlutterTestFile(config, context, file);
    if (testFile == null) return false;

    config.setFields(TestFields.forFile(testFile.getPath()));
    config.setGeneratedName();

    return true;
  }

  private VirtualFile verifyFlutterTestFile(TestConfig config, ConfigurationContext context, DartFile file) {
    final VirtualFile candidate = FlutterRunConfigurationProducer.getFlutterEntryFile(context, false, false);
    if (candidate == null) return null;

    return FlutterUtils.isInTestDir(file) ? candidate : null;
  }

  private boolean setupForDirectory(TestConfig config, PsiDirectory dir) {
    final PubRoot root = PubRoot.forDescendant(dir.getVirtualFile(), dir.getProject());
    if (root == null) return false;

    if (!root.hasTests(dir.getVirtualFile())) return false;

    config.setFields(TestFields.forDir(dir.getVirtualFile().getPath()));
    config.setGeneratedName();
    return true;
  }

  /**
   * Returns true if a run config was already created for this file. If so we will reuse it.
   */
  @Override
  public boolean isConfigurationFromContext(TestConfig config, @NotNull ConfigurationContext context) {
    final VirtualFile fileOrDir = config.getFields().getFileOrDir();
    if (fileOrDir == null) return false;

    final PsiElement target = context.getPsiLocation();
    if (target instanceof PsiDirectory) {
      return ((PsiDirectory)target).getVirtualFile().equals(fileOrDir);
    }

    if (!FlutterRunConfigurationProducer.hasDartFile(context, fileOrDir.getPath())) return false;

    final String testName = testConfigUtils.findTestName(context.getPsiLocation());
    if (config.getFields().getScope() == TestFields.Scope.NAME) {
      return testName != null && testName.equals(config.getFields().getTestName());
    }
    else {
      return testName == null;
    }
  }

  @Override
  public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
    return DartPlugin.isDartTestConfiguration(other.getConfigurationType());
  }
}
