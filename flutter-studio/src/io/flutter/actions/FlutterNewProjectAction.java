/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.impl.NewProjectUtil;
import com.intellij.ide.projectWizard.NewProjectWizard;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.wm.impl.welcomeScreen.NewWelcomeScreen;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.OffsetIcon;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import javax.swing.Icon;

import io.flutter.FlutterUtils;
import org.jetbrains.annotations.NotNull;

public class FlutterNewProjectAction extends AnAction implements DumbAware {

  public FlutterNewProjectAction() {
    super(FlutterBundle.message("action.new.project.title"));
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    if (NewWelcomeScreen.isNewWelcomeScreen(e)) {
      //e.getPresentation().setIcon(getFlutterDecoratedIcon());
      e.getPresentation().setText(FlutterBundle.message("welcome.new.project.compact"));
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    if (FlutterUtils.isAndroidStudio()) {
      System.setProperty("studio.projectview", "true");
    }
    NewProjectWizard wizard = new NewProjectWizard(null, ModulesProvider.EMPTY_MODULES_PROVIDER, null);
    NewProjectUtil.createNewProject(wizard);
  }

  @Override
  @NotNull
  public ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @NotNull
  Icon getFlutterDecoratedIcon() {
    Icon icon = AllIcons.Welcome.CreateNewProject;
    Icon badgeIcon = new OffsetIcon(0, FlutterIcons.Flutter_badge).scale(0.666f);

    LayeredIcon decorated = new LayeredIcon(2);
    decorated.setIcon(badgeIcon, 0, 7, 7);
    decorated.setIcon(icon, 1, 0, 0);
    return decorated;
  }
}
